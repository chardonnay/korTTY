package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One Mosh session of the built-in client (mosh4j): the UDP transport, its encryption and the state
 * synchronization, loaded from the mosh4j release JARs through reflection, plus the loop that hands the
 * server's output on, keeps the session alive with heartbeats and notices interruptions. Free of
 * JavaFX, so it runs in korTTY ({@link Mosh4jTtyConnector}) and in a session worker alike.
 *
 * <p>Threading: {@link #start()} spawns one output thread; {@link #sendInput}, {@link #resize} and
 * {@link #setTerminalActive} may be called from any thread.
 */
public final class Mosh4jEngine {

    private static final Logger logger = LoggerFactory.getLogger(Mosh4jEngine.class);

    /** Mosh is UTF-8 only by protocol. */
    public static final Charset CHARSET = StandardCharsets.UTF_8;
    private static final long KEEPALIVE_INTERVAL_MS = 2500L;
    /** No host bytes for this long → "connection interrupted" (longer than the keepalive to avoid false positives). */
    private static final long NO_HOST_BYTES_INTERRUPTION_MS = 15_000L;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getenv("KORTTY_MOSH_DEBUG"));

    /** How a session ended. */
    public enum End {
        /** The server ended the session. */
        ENDED,
        /** The session was stopped from this side. */
        STOPPED,
        /** The user logged out with Ctrl+D shortly before. */
        REMOTE_LOGOUT,
        /** The client failed; see the message. */
        FAILED
    }

    /** What the engine reports. Called on the engine's output thread. */
    public interface Listener {
        /** Output of the server for the terminal. */
        void output(String chunk) throws IOException;

        /** The network went quiet or the transport stalled. */
        void interrupted();

        /** Output arrived again after an interruption. */
        void recovered();

        /** The session ended; {@code message} is set for {@link End#FAILED}. */
        void ended(End end, String message);
    }

    private final List<Path> classpath;
    private final String host;
    private final int udpPort;
    private final String keyBase64;
    private final int columns;
    private final int rows;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile URLClassLoader classLoader;
    private volatile Object frontend;
    private volatile Method frontendSendUserInput;
    private volatile Method frontendSendResize;
    private volatile Method frontendTakeRenderedOutput;
    private volatile Method frontendTakeHostBytes;
    private volatile Method frontendSendHeartbeat;
    private volatile Method frontendStart;
    private volatile Method frontendClose;
    private volatile Method frontendIsRunning;
    private volatile Thread outputThread;
    private volatile long interruptionStartedAtMs = -1L;
    private volatile long logoutRequestedAtMs = -1L;
    private volatile boolean terminalActive = true;
    private volatile long lastActivatedAtMs = System.currentTimeMillis();

    /**
     * @param classpath the mosh4j release JARs and their protobuf runtime
     * @param host      the server
     * @param udpPort   the port mosh-server reported
     * @param keyBase64 the session key mosh-server reported
     */
    public Mosh4jEngine(List<Path> classpath, String host, int udpPort, String keyBase64, int columns, int rows,
                        Listener listener) {
        this.classpath = List.copyOf(classpath);
        this.host = Objects.requireNonNull(host, "host");
        this.udpPort = udpPort;
        this.keyBase64 = Objects.requireNonNull(keyBase64, "keyBase64");
        this.columns = columns > 0 ? columns : 80;
        this.rows = rows > 0 ? rows : 24;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Loads mosh4j, opens the session and starts handing output to the listener. */
    public void start() throws Exception {
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < classpath.size(); i++) {
            urls[i] = classpath.get(i).toUri().toURL();
        }
        classLoader = new URLClassLoader(urls, Mosh4jEngine.class.getClassLoader());

        Class<?> moshKeyClass = classLoader.loadClass("org.mosh4j.crypto.MoshKey");
        Object moshKey = moshKeyClass.getMethod("fromBase64", String.class).invoke(null, keyBase64);
        Class<?> sessionClass = classLoader.loadClass("org.mosh4j.core.MoshClientSession");
        Constructor<?> ctor = sessionClass.getConstructor(InetSocketAddress.class, moshKeyClass, int.class, int.class);
        Object session = ctor.newInstance(new InetSocketAddress(host, udpPort), moshKey, columns, rows);

        Class<?> frontendClass = classLoader.loadClass("org.mosh4j.core.MoshTerminalFrontend");
        frontend = frontendClass.getConstructor(sessionClass).newInstance(session);
        frontendSendUserInput = frontendClass.getMethod("sendUserInput", byte[].class);
        frontendSendResize = frontendClass.getMethod("sendResize", int.class, int.class);
        frontendTakeRenderedOutput = frontendClass.getMethod("takeRenderedOutput", long.class);
        try {
            frontendTakeHostBytes = frontendClass.getMethod("takeHostBytes", long.class);
        } catch (NoSuchMethodException ignored) {
            frontendTakeHostBytes = null;
        }
        Method sendInitialWakeUp = frontendClass.getMethod("sendInitialWakeUp");
        try {
            frontendSendHeartbeat = frontendClass.getMethod("sendHeartbeat");
        } catch (NoSuchMethodException ignored) {
            frontendSendHeartbeat = null;
        }
        frontendStart = frontendClass.getMethod("start");
        frontendClose = frontendClass.getMethod("close");
        frontendIsRunning = frontendClass.getMethod("isRunning");

        sendInitialWakeUp.invoke(frontend);
        frontendStart.invoke(frontend);
        // Native mosh-client sends an early resize; do the same so the server-side PTY is set up
        // before the first prompt is rendered.
        frontendSendResize.invoke(frontend, columns, rows);
        running.set(true);
        outputThread = new Thread(this::outputLoop, "MOSH4J-Frontend");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    /** Sends what the user typed; Ctrl+D is remembered so the end can be told apart from a failure. */
    public void sendInput(byte[] bytes) throws IOException {
        if (!running.get() || bytes == null || bytes.length == 0) {
            return;
        }
        for (byte b : bytes) {
            if (b == 0x04) {
                logoutRequestedAtMs = System.currentTimeMillis();
                break;
            }
        }
        Object localFrontend = frontend;
        Method send = frontendSendUserInput;
        if (localFrontend == null || send == null) {
            return;
        }
        try {
            send.invoke(localFrontend, (Object) bytes);
        } catch (Exception e) {
            throw new IOException("mosh4j could not send the input", e);
        }
    }

    public void resize(int newColumns, int newRows) {
        Object localFrontend = frontend;
        Method localResize = frontendSendResize;
        if (!running.get() || localFrontend == null || localResize == null) {
            return;
        }
        try {
            localResize.invoke(localFrontend, newColumns, newRows);
        } catch (Exception e) {
            logger.debug("Failed to send mosh4j resize: {}", e.getMessage());
        }
    }

    /**
     * Whether the tab is the one the user looks at. While it is not, a quiet server is not taken for an
     * interruption; after it becomes active again a short grace period applies.
     */
    public void setTerminalActive(boolean active) {
        this.terminalActive = active;
        if (active) {
            this.lastActivatedAtMs = System.currentTimeMillis();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isInterrupted() {
        return running.get() && interruptionStartedAtMs > 0;
    }

    public long interruptionStartedAtMs() {
        return interruptionStartedAtMs;
    }

    /** Stops the session; the listener hears {@link End#STOPPED} unless it ended already. */
    public void close() {
        running.set(false);
        Thread thread = outputThread;
        outputThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        closeFrontend();
        URLClassLoader loader = classLoader;
        classLoader = null;
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // Nothing left to release.
            }
        }
    }

    /** Waits for the output thread to end. */
    public void join() throws InterruptedException {
        Thread thread = outputThread;
        if (thread != null) {
            thread.join();
        }
    }

    private void closeFrontend() {
        Object localFrontend = frontend;
        frontend = null;
        if (localFrontend != null && frontendClose != null) {
            try {
                frontendClose.invoke(localFrontend);
            } catch (Exception ignored) {
                // Closing anyway.
            }
        }
    }

    private void outputLoop() {
        End end = End.ENDED;
        String message = null;
        long lastHostBytesAt = System.currentTimeMillis();
        long lastKeepaliveAt = 0;
        boolean promptNudgeSent = false;
        try {
            while (running.get()) {
                try {
                    if (!isFrontendRunning()) {
                        if (interruptionStartedAtMs < 0) {
                            interruptionStartedAtMs = System.currentTimeMillis();
                            listener.interrupted();
                        }
                        // Network glitches must not end the session: revive the receive loop.
                        frontendStart.invoke(frontend);
                        Thread.sleep(100);
                        continue;
                    }
                    if (frontendTakeHostBytes != null) {
                        byte[] hostBytes = (byte[]) frontendTakeHostBytes.invoke(frontend, 250L);
                        if (hostBytes != null && hostBytes.length > 0) {
                            listener.output(new String(hostBytes, CHARSET));
                            lastHostBytesAt = System.currentTimeMillis();
                            long wasInterrupted = interruptionStartedAtMs;
                            interruptionStartedAtMs = -1L;
                            if (wasInterrupted > 0) {
                                listener.recovered();
                            }
                            if (!promptNudgeSent) {
                                frontendSendUserInput.invoke(frontend, (Object) "\r".getBytes(CHARSET));
                                promptNudgeSent = true;
                            }
                            continue;
                        }
                        long now = System.currentTimeMillis();
                        // "Interrupted" only for the active tab, and not right after it became active.
                        if (terminalActive && now - lastActivatedAtMs > 2_000L
                                && now - lastHostBytesAt >= NO_HOST_BYTES_INTERRUPTION_MS && interruptionStartedAtMs < 0) {
                            interruptionStartedAtMs = now;
                            listener.interrupted();
                        }
                        // Keep the session alive with the protocol heartbeat, never with input: a NUL
                        // would show up as ^@ and corrupt the prompt.
                        if (now - lastHostBytesAt >= KEEPALIVE_INTERVAL_MS
                                && now - lastKeepaliveAt >= KEEPALIVE_INTERVAL_MS && frontendSendHeartbeat != null) {
                            frontendSendHeartbeat.invoke(frontend);
                            lastKeepaliveAt = now;
                        }
                        continue;
                    }
                    // Older frontends without raw host bytes.
                    String frame = (String) frontendTakeRenderedOutput.invoke(frontend, 250L);
                    if (frame != null && !frame.isEmpty()) {
                        listener.output(frame);
                    }
                } catch (Exception loopError) {
                    Throwable cause = loopError instanceof InvocationTargetException ite ? ite.getCause() : loopError;
                    if (cause instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        end = End.STOPPED;
                        break;
                    }
                    if (loopError instanceof IOException && !(loopError instanceof java.io.InterruptedIOException)
                            && cause == loopError) {
                        // The listener could not take the output: the terminal side is gone.
                        end = End.STOPPED;
                        break;
                    }
                    if (interruptionStartedAtMs < 0) {
                        interruptionStartedAtMs = System.currentTimeMillis();
                        listener.interrupted();
                    }
                    if (DEBUG) {
                        logger.info("MOSH4J transient frontend loop issue: {}",
                            cause != null ? cause.getMessage() : loopError.getMessage());
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        end = End.STOPPED;
                        break;
                    }
                }
            }
            if (!running.get() && end == End.ENDED) {
                end = End.STOPPED;
            }
        } catch (RuntimeException e) {
            end = End.FAILED;
            message = e.getMessage();
            logger.warn("mosh4j frontend failed: {}", e.getMessage(), e);
        } finally {
            if (end != End.FAILED && logoutRequestedAtMs > 0
                    && System.currentTimeMillis() - logoutRequestedAtMs <= 5000L) {
                // Ctrl+D shortly before: a logout, so the tab may close.
                end = End.REMOTE_LOGOUT;
            }
            running.set(false);
            closeFrontend();
            listener.ended(end, message);
        }
    }

    private boolean isFrontendRunning() {
        try {
            Object state = frontendIsRunning.invoke(frontend);
            return state instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }
}
