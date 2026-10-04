package de.kortty.core.remote;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs one command on an exec channel of an SSH session, optionally as root through
 * {@link SudoCommand}, with stdin data, a timeout, an output cap and a cancel switch.
 *
 * <p>Rules this class enforces:
 * <ul>
 *   <li>Exec channels never get a pty, so sudo never echoes and binary stdin stays intact. A sudo
 *       that insists on a terminal fails with {@link SudoRequiresTtyException}.</li>
 *   <li>The sudo password is written to stdin only after sudo printed the command's prompt nonce,
 *       at most once; a second prompt aborts with {@link SudoAuthenticationException}. stdin data
 *       starts flowing only after the ready nonce, so with NOPASSWD it never mixes with a
 *       password.</li>
 *   <li>The password is encoded into a byte buffer that is wiped after use; the caller's
 *       {@code char[]} is never turned into a {@code String} and stays the caller's to wipe.</li>
 *   <li>Nonces and the password are removed from the captured stderr before callers see it, and
 *       log lines and exception messages show only the command template.</li>
 *   <li>Cancel, timeout, an exhausted output cap and a sink that wants no more output all close
 *       the channel immediately.</li>
 * </ul>
 * The session comes from a supplier at run time and is never closed here, so a borrowed terminal
 * session can be used as well as an SFTP tab's own one.
 */
public final class RemoteCommandRunner {

    private static final Logger logger = LoggerFactory.getLogger(RemoteCommandRunner.class);

    /** Default cap for captured stdout. */
    public static final long DEFAULT_OUTPUT_CAP = 16L * 1024 * 1024;
    /** How much stderr is kept for the caller; sudo's own messages fit easily. */
    static final int STDERR_CAP = 64 * 1024;
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final List<String> TTY_MARKERS = List.of("must have a tty", "a terminal is required");
    private static final byte[] SECRET_MASK = "***".getBytes(StandardCharsets.US_ASCII);

    private final Supplier<ClientSession> sessions;

    /** @param sessions resolves the session at run time; may return null when disconnected */
    public RemoteCommandRunner(Supplier<ClientSession> sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /** Receives stdout as it arrives; returning false stops the command and closes the channel. */
    @FunctionalInterface
    public interface StdoutSink {
        boolean accept(byte[] data, int offset, int length) throws IOException;
    }

    /**
     * What a finished command produced.
     *
     * @param exitCode the remote exit status, -1 when the channel closed without one
     * @param stdout captured stdout (empty when a {@link StdoutSink} consumed it)
     * @param stderr stderr with nonces and the password removed
     * @param outputTruncated stdout reached the output cap and the channel was closed there
     * @param stoppedBySink the {@link StdoutSink} asked to stop and the channel was closed
     */
    public record Result(int exitCode, byte[] stdout, String stderr, boolean outputTruncated, boolean stoppedBySink) {
        public String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /** A command plus its options; built with {@link #plain(String)} or {@link #sudo(SudoCommand, Optional)}. */
    public static final class Request {
        private final String command;
        private final SudoCommand sudo;
        private final char[] secret;
        private InputStream stdin;
        private Duration timeout = Duration.ofMinutes(5);
        private long outputCap = DEFAULT_OUTPUT_CAP;
        private RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        private StdoutSink stdoutSink;

        private Request(String command, SudoCommand sudo, char[] secret) {
            this.command = command;
            this.sudo = sudo;
            this.secret = secret;
        }

        /** A command that runs as the login user. It must not contain any secret. */
        public static Request plain(String command) {
            Objects.requireNonNull(command, "command");
            return new Request(command, null, null);
        }

        /** A command that runs as root; {@code secret} is sent only when sudo prompts for it. */
        public static Request sudo(SudoCommand command, Optional<char[]> secret) {
            Objects.requireNonNull(command, "command");
            return new Request(command.commandLine(), command, Objects.requireNonNull(secret, "secret").orElse(null));
        }

        /** Data streamed to the command's stdin (after sudo is through); the caller closes it. */
        public Request stdin(InputStream data) {
            this.stdin = data;
            return this;
        }

        public Request timeout(Duration value) {
            this.timeout = Objects.requireNonNull(value, "timeout");
            return this;
        }

        public Request outputCap(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("The output cap cannot be negative.");
            }
            this.outputCap = bytes;
            return this;
        }

        public Request cancellation(RemoteCommandCancellation value) {
            this.cancellation = Objects.requireNonNull(value, "cancellation");
            return this;
        }

        public Request stdoutSink(StdoutSink sink) {
            this.stdoutSink = sink;
            return this;
        }

        /** The command as shown in logs: plain commands as given, sudo commands without nonces. */
        String template() {
            return sudo != null ? sudo.toString() : command;
        }
    }

    /** Runs a command as the login user. */
    public Result run(String command, Optional<InputStream> stdinData, Duration timeout, long outputCapBytes,
                      RemoteCommandCancellation cancellation) throws IOException {
        Request request = Request.plain(command).timeout(timeout).outputCap(outputCapBytes).cancellation(cancellation);
        stdinData.ifPresent(request::stdin);
        return run(request);
    }

    /** Runs a command as root; the password goes out only after sudo's prompt nonce. */
    public Result run(SudoCommand command, Optional<char[]> sudoSecret, Optional<InputStream> stdinData,
                      Duration timeout, long outputCapBytes, RemoteCommandCancellation cancellation)
            throws IOException {
        Request request = Request.sudo(command, sudoSecret).timeout(timeout).outputCap(outputCapBytes)
            .cancellation(cancellation);
        stdinData.ifPresent(request::stdin);
        return run(request);
    }

    public Result run(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        ClientSession session = sessions.get();
        if (session == null || !session.isOpen()) {
            throw new RemoteCommandException("Not connected.");
        }
        return new Execution(request).execute(session);
    }

    /** Encodes the password plus a newline; every intermediate buffer is wiped. */
    static byte[] encodeSecretLine(char[] secret) {
        for (char c : secret) {
            if (c == '\n' || c == '\r' || c == '\0') {
                throw new IllegalArgumentException("The sudo password cannot contain line breaks or NUL.");
            }
        }
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.ceil(secret.length * (double) encoder.maxBytesPerChar()) + 1);
        try {
            CoderResult result = encoder.encode(CharBuffer.wrap(secret), buffer, true);
            if (!result.isUnderflow()) {
                result.throwException();
            }
            result = encoder.flush(buffer);
            if (!result.isUnderflow()) {
                result.throwException();
            }
            buffer.put((byte) '\n');
            return Arrays.copyOf(buffer.array(), buffer.position());
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("The sudo password is not valid text.");
        } finally {
            Arrays.fill(buffer.array(), (byte) 0);
        }
    }

    /** Replaces every occurrence of {@code needle} in {@code data}. */
    static byte[] replaceAll(byte[] data, byte[] needle, byte[] replacement) {
        if (needle == null || needle.length == 0 || data.length < needle.length) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int i = 0;
        while (i < data.length) {
            if (i <= data.length - needle.length && Arrays.equals(data, i, i + needle.length, needle, 0, needle.length)) {
                out.writeBytes(replacement);
                i += needle.length;
            } else {
                out.write(data[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    private enum Event { PROMPT, READY, TTY, STOP, CLOSED, CANCEL }

    /** State of one run. */
    private static final class Execution {
        private final Request request;
        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final StderrWatcher stderr;
        private final StdoutCollector stdout;

        Execution(Request request) {
            this.request = request;
            this.stderr = new StderrWatcher(request.sudo, events);
            this.stdout = new StdoutCollector(request.outputCap, request.stdoutSink, events);
        }

        Result execute(ClientSession session) throws IOException {
            String template = request.template();
            RemoteCommandCancellation cancellation = request.cancellation;
            byte[] secretLine = request.sudo != null && request.secret != null ? encodeSecretLine(request.secret) : null;
            ChannelExec channel = null;
            AutoCloseable cancelRegistration = null;
            Thread pump = null;
            boolean closed = false;
            try {
                if (cancellation.isCancelled()) {
                    throw new RemoteCommandCancelledException();
                }
                logger.debug("Running remote command: {}", template);
                channel = session.createExecChannel(request.command);
                channel.setUsePty(false);
                channel.setOut(stdout);
                channel.setErr(stderr);
                channel.addCloseFutureListener(future -> events.offer(Event.CLOSED));
                cancelRegistration = cancellation.onCancel(() -> events.offer(Event.CANCEL));
                try {
                    channel.open().verify(OPEN_TIMEOUT);
                } catch (IOException | RuntimeException e) {
                    throw new RemoteCommandException("Could not open an exec channel.", e);
                }
                OutputStream remoteIn = channel.getInvertedIn();
                boolean ready = request.sudo == null;
                if (ready) {
                    pump = startStdin(remoteIn, cancellation);
                }
                int prompts = 0;
                RemoteCommandException failure = null;
                long deadline = System.nanoTime() + request.timeout.toNanos();
                loop:
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        failure = new RemoteCommandTimeoutException();
                        break;
                    }
                    Event event = events.poll(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS);
                    if (cancellation.isCancelled()) {
                        failure = new RemoteCommandCancelledException();
                        break;
                    }
                    if (event == null) {
                        continue;
                    }
                    switch (event) {
                        case CLOSED -> {
                            closed = true;
                            break loop;
                        }
                        case CANCEL -> {
                            failure = new RemoteCommandCancelledException();
                            break loop;
                        }
                        case STOP -> {
                            break loop;
                        }
                        case TTY -> {
                            if (!ready) {
                                failure = new SudoRequiresTtyException();
                                break loop;
                            }
                        }
                        case PROMPT -> {
                            if (ready) {
                                continue;
                            }
                            prompts++;
                            if (prompts > 1) {
                                failure = new SudoAuthenticationException();
                                break loop;
                            }
                            if (secretLine == null) {
                                failure = new SudoPasswordRequiredException();
                                break loop;
                            }
                            remoteIn.write(secretLine);
                            remoteIn.flush();
                        }
                        case READY -> {
                            if (!ready) {
                                ready = true;
                                pump = startStdin(remoteIn, cancellation);
                            }
                        }
                    }
                }
                if (!closed) {
                    closeChannel(channel);
                    closed = true;
                }
                if (failure != null) {
                    logger.debug("Remote command aborted ({}): {}", failure.getClass().getSimpleName(), template);
                    throw failure;
                }
                if (stdout.sinkFailure != null) {
                    throw new RemoteCommandException("The command output could not be processed.", stdout.sinkFailure);
                }
                if (closed && !ready && stderr.ttyDetected()) {
                    throw new SudoRequiresTtyException();
                }
                Integer status = channel.getExitStatus();
                int exitCode = status != null ? status : -1;
                logger.debug("Remote command finished with exit code {}: {}", exitCode, template);
                return new Result(exitCode, stdout.bytes(), stderr.scrubbed(secretLine), stdout.truncated,
                    stdout.stoppedBySink);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RemoteCommandCancelledException();
            } finally {
                if (channel != null && !closed) {
                    closeChannel(channel);
                }
                if (cancelRegistration != null) {
                    try {
                        cancelRegistration.close();
                    } catch (Exception ignored) {
                        // removing a callback cannot fail
                    }
                }
                if (pump != null) {
                    joinQuietly(pump);
                }
                if (secretLine != null) {
                    Arrays.fill(secretLine, (byte) 0);
                }
            }
        }

        /** Streams the request's stdin to the command, then sends EOF; without data, sends EOF at once. */
        private Thread startStdin(OutputStream remoteIn, RemoteCommandCancellation cancellation) throws IOException {
            InputStream data = request.stdin;
            if (data == null) {
                remoteIn.close();
                return null;
            }
            return Thread.ofPlatform().daemon().name("remote-command-stdin").start(() -> {
                try (OutputStream out = remoteIn) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while (!cancellation.isCancelled() && (read = data.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                } catch (IOException e) {
                    logger.debug("Remote command stdin ended early: {}", e.getClass().getSimpleName());
                }
            });
        }

        /**
         * Closes the channel so the server learns about it: an immediate close only drops it
         * locally and would leave the remote command running. A peer that does not answer within
         * {@link #CLOSE_GRACE} gets the channel dropped anyway.
         */
        private static void closeChannel(ChannelExec channel) {
            try {
                if (!channel.close(false).await(CLOSE_GRACE)) {
                    channel.close(true);
                }
            } catch (IOException e) {
                channel.close(true);
            }
        }

        private static void joinQuietly(Thread thread) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Collects stdout up to the cap, or hands it to a sink. Runs on MINA's I/O threads. */
    private static final class StdoutCollector extends OutputStream {
        private final long cap;
        private final StdoutSink sink;
        private final BlockingQueue<Event> events;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long seen;
        private boolean stopped;
        volatile boolean truncated;
        volatile boolean stoppedBySink;
        volatile IOException sinkFailure;

        StdoutCollector(long cap, StdoutSink sink, BlockingQueue<Event> events) {
            this.cap = cap;
            this.sink = sink;
            this.events = events;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (stopped || len <= 0) {
                return;
            }
            long room = cap - seen;
            int take = (int) Math.min(len, Math.max(0, room));
            seen += take;
            if (take > 0) {
                if (sink != null) {
                    try {
                        if (!sink.accept(b, off, take)) {
                            stoppedBySink = true;
                            stop();
                            return;
                        }
                    } catch (IOException | RuntimeException e) {
                        sinkFailure = e instanceof IOException io ? io : new IOException(e);
                        stop();
                        return;
                    }
                } else {
                    buffer.write(b, off, take);
                }
            }
            if (take < len) {
                truncated = true;
                stop();
            }
        }

        private void stop() {
            stopped = true;
            events.offer(Event.STOP);
        }

        synchronized byte[] bytes() {
            return buffer.toByteArray();
        }
    }

    /** Keeps stderr and turns the sudo nonces and tty complaints into events. Runs on MINA's I/O threads. */
    private static final class StderrWatcher extends OutputStream {
        private record Needle(String text, Event event) {
        }

        private final BlockingQueue<Event> events;
        private final String promptNonce;
        private final String readyNonce;
        private final List<Needle> needles = new ArrayList<>();
        private final int window;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private String tail = "";
        private boolean readySeen;
        private volatile boolean ttyDetected;

        StderrWatcher(SudoCommand sudo, BlockingQueue<Event> events) {
            this.events = events;
            this.promptNonce = sudo != null ? sudo.promptNonce() : null;
            this.readyNonce = sudo != null ? sudo.readyNonce() : null;
            if (sudo != null) {
                needles.add(new Needle(promptNonce, Event.PROMPT));
                needles.add(new Needle(readyNonce, Event.READY));
                for (String marker : TTY_MARKERS) {
                    needles.add(new Needle(marker, Event.TTY));
                }
            }
            this.window = needles.stream().mapToInt(n -> n.text().length()).max().orElse(1) - 1;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (len <= 0) {
                return;
            }
            int keep = Math.min(len, STDERR_CAP - kept.size());
            if (keep > 0) {
                kept.write(b, off, keep);
            }
            if (needles.isEmpty() || readySeen) {
                return;
            }
            // ISO-8859-1 maps bytes 1:1 to chars, so positions stay byte positions.
            String scan = (tail + new String(b, off, len, StandardCharsets.ISO_8859_1)).toLowerCase(Locale.ROOT);
            int fresh = tail.length();
            List<int[]> hits = new ArrayList<>();
            for (int n = 0; n < needles.size(); n++) {
                String text = needles.get(n).text().toLowerCase(Locale.ROOT);
                int from = Math.max(0, fresh - text.length() + 1);
                int at;
                while ((at = scan.indexOf(text, from)) >= 0) {
                    hits.add(new int[] {at, n});
                    from = at + text.length();
                }
            }
            hits.sort(Comparator.comparingInt(hit -> hit[0]));
            for (int[] hit : hits) {
                Event event = needles.get(hit[1]).event();
                if (event == Event.TTY) {
                    ttyDetected = true;
                }
                events.offer(event);
                if (event == Event.READY) {
                    readySeen = true;
                    break;
                }
            }
            tail = scan.substring(Math.max(0, scan.length() - window));
        }

        boolean ttyDetected() {
            return ttyDetected;
        }

        /** stderr as text, without the nonces and the password. */
        synchronized String scrubbed(byte[] secretLine) {
            byte[] data = kept.toByteArray();
            if (readyNonce != null) {
                data = replaceAll(data, (readyNonce + "\n").getBytes(StandardCharsets.US_ASCII), new byte[0]);
                data = replaceAll(data, readyNonce.getBytes(StandardCharsets.US_ASCII), new byte[0]);
                data = replaceAll(data, promptNonce.getBytes(StandardCharsets.US_ASCII), new byte[0]);
            }
            if (secretLine != null && secretLine.length > 1) {
                byte[] secret = Arrays.copyOf(secretLine, secretLine.length - 1);
                try {
                    data = replaceAll(data, secret, SECRET_MASK);
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }
}
