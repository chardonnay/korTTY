package de.kortty.core.worker;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyMode;
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
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;

/**
 * A session channel of the worker's loopback endpoint that is really a channel on the upstream
 * server: korTTY's shell, an {@code exec} (the AI agent's commands) or a subsystem (SFTP) is opened
 * upstream with the same terminal type, size, modes and environment, and the bytes are copied both
 * ways. Window changes go upstream. When the upstream channel ends with an exit status, korTTY's
 * channel ends with the same; when it ends without one (the upstream transport died), korTTY's
 * channel is closed without one too, so korTTY reports "Connection lost" exactly as it does for a
 * direct session.
 */
final class RelayCommand implements Command {

    private static final Logger logger = LoggerFactory.getLogger(RelayCommand.class);

    enum Kind { SHELL, EXEC, SUBSYSTEM }

    private final ClientSession upstream;
    private final Kind kind;
    /** The command for {@link Kind#EXEC}, the subsystem name for {@link Kind#SUBSYSTEM}. */
    private final String argument;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;
    private volatile ClientChannel upstreamChannel;
    private volatile ChannelSession downstream;

    RelayCommand(ClientSession upstream, Kind kind, String argument) {
        this.upstream = upstream;
        this.kind = kind;
        this.argument = argument;
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
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        this.downstream = channel;
        Map<String, String> vars = env.getEnv();
        String term = vars.get(Environment.ENV_TERM);
        ClientChannel ch = switch (kind) {
            case SHELL -> {
                ChannelShell shell = upstream.createShellChannel();
                configurePty(shell, env, term);
                yield shell;
            }
            case EXEC -> {
                ChannelExec exec = upstream.createExecChannel(argument);
                if (term != null) {
                    configurePty(exec, env, term);
                } else {
                    exec.setUsePty(false);
                }
                yield exec;
            }
            case SUBSYSTEM -> upstream.createSubsystemChannel(argument);
        };
        if (ch instanceof org.apache.sshd.client.channel.ChannelSession session) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String key = entry.getKey();
                if (!Environment.ENV_TERM.equals(key) && !Environment.ENV_COLUMNS.equals(key)
                        && !Environment.ENV_LINES.equals(key) && !Environment.ENV_USER.equals(key)) {
                    session.setEnv(key, entry.getValue());
                }
            }
        }
        ch.setIn(in);
        ch.setOut(out);
        ch.setErr(err);
        upstreamChannel = ch;
        ch.open().verify(Duration.ofSeconds(30));
        if (ch instanceof PtyCapableChannelSession pty) {
            env.addSignalListener((signalChannel, signal) -> {
                try {
                    int columns = Integer.parseInt(env.getEnv().getOrDefault(Environment.ENV_COLUMNS, "80"));
                    int lines = Integer.parseInt(env.getEnv().getOrDefault(Environment.ENV_LINES, "24"));
                    pty.sendWindowChange(columns, lines);
                } catch (IOException | RuntimeException e) {
                    logger.debug("Window change could not be relayed: {}", e.getMessage());
                }
            }, Signal.WINCH);
        }
        Thread waiter = new Thread(this::awaitUpstreamEnd, "Relay-" + kind);
        waiter.setDaemon(true);
        waiter.start();
    }

    private static void configurePty(PtyCapableChannelSession channel, Environment env, String term) {
        Map<String, String> vars = env.getEnv();
        if (term != null) {
            channel.setPtyType(term);
        }
        channel.setPtyColumns(parse(vars.get(Environment.ENV_COLUMNS), 80));
        channel.setPtyLines(parse(vars.get(Environment.ENV_LINES), 24));
        Map<PtyMode, Integer> modes = env.getPtyModes();
        if (modes != null && !modes.isEmpty()) {
            channel.setPtyModes(modes);
        }
    }

    private static int parse(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void awaitUpstreamEnd() {
        ClientChannel ch = upstreamChannel;
        try {
            ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
        } catch (RuntimeException e) {
            logger.debug("Waiting for the upstream channel failed: {}", e.getMessage());
        }
        Integer status = ch.getExitStatus();
        String signal = ch.getExitSignal();
        flushQuietly(out);
        flushQuietly(err);
        if (status != null) {
            exitCallback.onExit(status);
        } else if (signal != null && !signal.isEmpty()) {
            exitCallback.onExit(255, "signal " + signal);
        } else {
            // No exit status upstream: the transport died. Close without one, so korTTY says
            // "Connection lost" and offers a reconnect, as for a direct session.
            ChannelSession session = downstream;
            if (session != null) {
                session.close(false);
            }
        }
    }

    private static void flushQuietly(OutputStream stream) {
        try {
            if (stream != null) {
                stream.flush();
            }
        } catch (IOException ignored) {
            // The downstream side is closing.
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        ClientChannel ch = upstreamChannel;
        if (ch != null) {
            ch.close(false);
        }
    }
}
