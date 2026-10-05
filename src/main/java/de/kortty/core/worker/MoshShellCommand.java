package de.kortty.core.worker;

import com.google.gson.JsonObject;
import de.kortty.core.Mosh4jEngine;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The shell of a Mosh-mode session worker: korTTY's terminal channel on the loopback endpoint is a
 * built-in Mosh session ({@link Mosh4jEngine}) run in this process. What korTTY types goes to the
 * session, what the server shows comes back, window changes become Mosh resizes. Interruptions,
 * recoveries and the end of the session are reported to korTTY as {@code mosh.*} events on the
 * control channel, and korTTY tells the session whether its tab is active ({@code mosh.active}).
 */
final class MoshShellCommand implements Command {

    private static final Logger logger = LoggerFactory.getLogger(MoshShellCommand.class);

    private final WorkerInit init;
    private final WorkerEndpoint endpoint;
    private InputStream in;
    private OutputStream out;
    private ExitCallback exitCallback;
    private volatile Mosh4jEngine engine;

    /** The command korTTY's terminal channel is running, for {@code mosh.active} events. */
    private static volatile MoshShellCommand current;

    MoshShellCommand(WorkerInit init, WorkerEndpoint endpoint) {
        this.init = init;
        this.endpoint = endpoint;
    }

    /** Applies korTTY's {@code mosh.active} event to the running session. */
    static void setTerminalActive(boolean active) {
        MoshShellCommand command = current;
        Mosh4jEngine running = command != null ? command.engine : null;
        if (running != null) {
            running.setTerminalActive(active);
        }
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        int columns = parse(env.getEnv().get(Environment.ENV_COLUMNS), 80);
        int rows = parse(env.getEnv().get(Environment.ENV_LINES), 24);
        List<Path> classpath = init.moshClasspath.stream().map(Path::of).toList();
        Mosh4jEngine started = new Mosh4jEngine(classpath, init.host, init.moshPort, init.moshKey, columns, rows,
            new Mosh4jEngine.Listener() {
                @Override
                public void output(String chunk) throws IOException {
                    out.write(chunk.getBytes(Mosh4jEngine.CHARSET));
                    out.flush();
                }

                @Override
                public void interrupted() {
                    event("mosh.interrupted", null, null);
                }

                @Override
                public void recovered() {
                    event("mosh.recovered", null, null);
                }

                @Override
                public void ended(Mosh4jEngine.End end, String message) {
                    event("mosh.ended", end.name(), message);
                    exitCallback.onExit(end == Mosh4jEngine.End.FAILED ? 1 : 0);
                }
            });
        engine = started;
        current = this;
        try {
            started.start();
        } catch (Exception e) {
            logger.warn("mosh4j could not start: {}", e.toString());
            event("mosh.ended", Mosh4jEngine.End.FAILED.name(), e.getMessage());
            exitCallback.onExit(1);
            return;
        }
        env.addSignalListener((signalChannel, signal) -> {
            int newColumns = parse(env.getEnv().get(Environment.ENV_COLUMNS), columns);
            int newRows = parse(env.getEnv().get(Environment.ENV_LINES), rows);
            started.resize(newColumns, newRows);
        }, Signal.WINCH);
        Thread input = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (count > 0) {
                        started.sendInput(java.util.Arrays.copyOf(buffer, count));
                    }
                }
            } catch (IOException e) {
                logger.debug("Mosh input ended: {}", e.getMessage());
            } finally {
                started.close();
            }
        }, "Mosh-Input");
        input.setDaemon(true);
        input.start();
    }

    private void event(String type, String end, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        if (end != null) {
            event.addProperty("end", end);
        }
        if (message != null) {
            event.addProperty("message", message);
        }
        try {
            endpoint.send(event);
        } catch (IOException e) {
            logger.debug("Could not report {}: {}", type, e.getMessage());
        }
    }

    private static int parse(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        Mosh4jEngine running = engine;
        if (running != null) {
            running.close();
        }
    }
}
