package de.kortty.codingagent.desktop;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development macOS notifications owned by a small app bundle instead of Script Editor.
 * Requests and click identifiers travel through private process pipes; no port is opened.
 */
final class MacNotificationHelperBackend implements DesktopNotifierBackend {
    static final String EXECUTABLE_PROPERTY = "kortty.devNotificationHelper";
    private static final Logger logger = LoggerFactory.getLogger(MacNotificationHelperBackend.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_ACTIONS = 64;
    private final List<String> command;
    private final LinkedHashMap<String, Runnable> actions = new LinkedHashMap<>();
    private volatile Process process;
    private BufferedWriter requests;
    private long nextIdentifier;
    private volatile boolean closed;

    MacNotificationHelperBackend(Path executable) {
        this(List.of(executable.toString()));
    }

    /** Explicit command for process-pipe integration tests. */
    MacNotificationHelperBackend(List<String> command) {
        this.command = List.copyOf(command);
    }

    static DesktopNotifierBackend configuredHelper() {
        String configured = System.getProperty(EXECUTABLE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path executable = Path.of(configured);
        return Files.isRegularFile(executable) && Files.isExecutable(executable)
            ? new MacNotificationHelperBackend(executable) : null;
    }

    @Override
    public boolean isSupported() {
        return !closed;
    }

    @Override
    public boolean supportsActivation() {
        return isSupported();
    }

    @Override
    public void notify(String title, String body) throws IOException {
        notify(title, body, null);
    }

    @Override
    public synchronized void notify(String title, String body, Runnable onActivate) throws IOException {
        if (closed) {
            throw new IOException("macOS notification helper is closed");
        }
        if (process == null || !process.isAlive()) {
            synchronized (actions) {
                actions.clear();
            }
            process = new ProcessBuilder(command).start();
            if (closed) {
                process.destroy();
                throw new IOException("macOS notification helper was closed while starting");
            }
            requests = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Process started = process;
            Thread.ofVirtual().name("kortty-mac-notification-clicks").start(() -> readClicks(started));
            Thread.ofVirtual().name("kortty-mac-notification-errors").start(() -> readErrors(started));
        }
        String identifier = "kortty-" + ProcessHandle.current().pid() + "-" + ++nextIdentifier;
        if (onActivate != null) {
            synchronized (actions) {
                if (closed) {
                    throw new IOException("macOS notification helper is closed");
                }
                actions.put(identifier, onActivate);
                while (actions.size() > MAX_ACTIONS) {
                    actions.remove(actions.keySet().iterator().next());
                }
            }
        }
        try {
            requests.write(GSON.toJson(new Message(identifier, title, body)));
            requests.newLine();
            requests.flush();
        } catch (IOException e) {
            synchronized (actions) {
                actions.remove(identifier);
            }
            throw e;
        }
    }

    private record Message(String identifier, String title, String body) { }

    private void readClicks(Process started) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("activate:")) {
                    Runnable action;
                    synchronized (actions) {
                        action = !closed && process == started ? actions.remove(line.substring(9)) : null;
                    }
                    if (action != null) {
                        action.run();
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("macOS notification helper click pipe closed", e);
        }
    }

    private void readErrors(Process started) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getErrorStream(),
                StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                logger.warn("macOS notification helper could not handle a notification; check notification permissions");
            }
        } catch (IOException e) {
            logger.debug("macOS notification helper error pipe closed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (actions) {
            actions.clear();
        }
        Process started = process;
        if (started != null) {
            started.destroy();
        }
    }
}
