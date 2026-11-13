package de.kortty.core.agent;

import de.kortty.core.TerminalAgentService;
import de.kortty.core.agent.AgentCommandRunner.ExecResult;
import de.kortty.core.remote.RemoteShell;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.future.CancelOption;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** An isolated remote terminal whose sudo ticket is shared by the privileged steps of one run. */
public final class SshAgentSudoPty implements AgentSudoPty {

    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(15);

    private static final String SCRIPT = """
        set +x
        if ! [ -t 0 ] || ! [ -t 1 ]; then
            printf '\\036%s:124\\n' "$4"
            exit 124
        fi
        if ! /bin/stty raw -echo -echonl min 1 time 0; then
            printf '\\036%s:125\\n' "$4"
            exit 125
        fi
        sudo_executable=$5
        if [ -z "$sudo_executable" ]; then
            for candidate in /usr/bin/sudo /bin/sudo /usr/local/bin/sudo; do
                if [ -x "$candidate" ]; then
                    sudo_executable=$candidate
                    break
                fi
            done
        fi
        if [ -z "$sudo_executable" ]; then
            printf '\\036%s:127\\n' "$4"
            exit 127
        fi
        printf '%s' "$2" >&2
        "$sudo_executable" -S -p "$1" -v
        status=$?
        if [ "$status" -ne 0 ]; then
            printf '\\036%s:%s\\n' "$4" "$status"
            exit "$status"
        fi
        if ! /bin/stty raw -echo -echonl min 1 time 0; then
            printf '\\036%s:125\\n' "$4"
            exit 125
        fi
        printf '%s' "$3" >&2
        printf '\\036%s:0\\n' "$4"
        shift 5
        unset sudo_executable

        while IFS=' ' read -r request encoded; do
            # The client encodes every byte, so the terminal never parses planner text as protocol.
            # The sentinel preserves newlines at the end of an approved command.
            command=$(printf '%b.' "$encoded")
            /bin/sh -c "${command%.}" </dev/null
            status=$?
            # A command may have changed the terminal modes via /dev/tty.
            if ! /bin/stty raw -echo -echonl min 1 time 0; then
                exit 125
            fi
            printf '\\036%s:%s\\n' "$request" "$status"
        done
        """;

    private final ChannelExec channel;
    private final Supplier<String> workingDirectory;
    private final TerminalOutput terminalOutput = new TerminalOutput();
    private final AtomicBoolean closed = new AtomicBoolean();

    private SshAgentSudoPty(ChannelExec channel, Supplier<String> workingDirectory) {
        this.channel = channel;
        this.workingDirectory = workingDirectory;
    }

    public static SshAgentSudoPty open(
        ClientSession session, Supplier<String> workingDirectory,
        AgentSudoAuthentication auth, BooleanSupplier cancelled) throws Exception {
        return open(session, workingDirectory, auth, cancelled, "");
    }

    static SshAgentSudoPty open(
        ClientSession session, Supplier<String> workingDirectory,
        AgentSudoAuthentication auth, BooleanSupplier cancelled, String sudoExecutable) throws Exception {
        ensureNotCancelled(cancelled);
        String request = UUID.randomUUID().toString();
        String command = "exec /bin/sh -c " + RemoteShell.quote(SCRIPT) + " kortty-sudo "
            + RemoteShell.quote(auth.promptMarker()) + " " + RemoteShell.quote(auth.armedMarker()) + " "
            + RemoteShell.quote(auth.readyMarker()) + " " + RemoteShell.quote(request) + " "
            + RemoteShell.quote(sudoExecutable);
        SshAgentSudoPty terminal = new SshAgentSudoPty(session.createExecChannel(command), workingDirectory);
        boolean opened = false;
        try {
            ChannelExec channel = terminal.channel;
            channel.setUsePty(true);
            channel.setPtyType("dumb");
            channel.setPtyColumns(80);
            channel.setPtyLines(24);
            channel.setPtyModes(Map.of(PtyMode.ECHO, 0, PtyMode.ECHONL, 0, PtyMode.ICANON, 0, PtyMode.OPOST, 0));
            Response response = terminal.terminalOutput.begin(request, false);
            // Validate through the same controlling terminal, but do not close the terminal's
            // input on READY. The monitor is detached before any planner command is dispatched.
            terminal.terminalOutput.setValidation(auth.monitor(channel::getInvertedIn, response, false));
            channel.setOut(terminal.terminalOutput);
            channel.setErr(terminal.terminalOutput);
            openChannel(channel, cancelled);
            Integer status = terminal.waitFor(response, cancelled, auth);
            auth.checkFailure();
            if (status == null) {
                throw new IOException("Timed out while validating sudo on the agent's SSH terminal.");
            }
            switch (status) {
                case 0 -> { }
                case 124 -> throw new IOException("The SSH server did not allocate a terminal for agent sudo.");
                case 125 -> throw new IOException("Could not disable echo on the agent's SSH terminal.");
                case 127 -> throw new IOException("Could not find sudo in a system directory.");
                default -> throw new IOException("Sudo validation failed on the agent's SSH terminal (exit " + status + ").");
            }
            terminal.terminalOutput.finishValidation();
            opened = true;
            return terminal;
        } finally {
            if (!opened) {
                terminal.close();
            }
        }
    }

    @Override
    public ExecResult exec(String command, Consumer<String> output, BooleanSupplier cancelled) throws Exception {
        try {
            ensureNotCancelled(cancelled);
            if (closed.get() || !channel.isOpen()) {
                throw new IOException("The agent's SSH terminal is closed.");
            }
            String request = UUID.randomUUID().toString();
            String wrapped = TerminalAgentService.wrapCommandForWorkingDirectory(
                "{\n" + command + "\n}", workingDirectory.get());
            byte[] frame = encode(request, wrapped);
            Response response = terminalOutput.begin(request, true);
            OutputStream input = channel.getInvertedIn();
            input.write(frame);
            input.flush();
            Integer status = waitFor(response, cancelled, null);
            boolean timedOut = status == null;
            if (timedOut) {
                close();
            }
            String text = response.text();
            if (output != null && !text.isEmpty()) {
                output.accept(text);
                if (!text.endsWith("\n")) {
                    output.accept("\n");
                }
            }
            return new ExecResult(text, "", timedOut ? -1 : status, false, timedOut);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private Integer waitFor(Response response, BooleanSupplier cancelled, AgentSudoAuthentication auth) throws Exception {
        long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ensureNotCancelled(cancelled);
            if (auth != null) {
                auth.checkFailure();
            }
            try {
                return response.status.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                if (channel.isClosed() && !response.status.isDone()) {
                    throw new IOException("The agent's SSH terminal closed before it reported an exit status.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TerminalAgentService.AgentCancelledException("Terminal agent run cancelled");
            }
        }
        return null;
    }

    private static void openChannel(ChannelExec channel, BooleanSupplier cancelled) throws IOException {
        var opening = channel.open();
        long deadline = System.nanoTime() + OPEN_TIMEOUT.toNanos();
        try {
            while (!opening.isDone() && System.nanoTime() < deadline) {
                ensureNotCancelled(cancelled);
                opening.await(200, CancelOption.CANCEL_ON_INTERRUPT);
            }
            ensureNotCancelled(cancelled);
            if (!opening.isDone()) {
                throw new IOException("Timed out while opening the agent's SSH terminal.");
            }
            opening.verify(OPEN_TIMEOUT);
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new TerminalAgentService.AgentCancelledException("Terminal agent run cancelled");
        } finally {
            if (!opening.isDone()) {
                opening.cancel();
            }
        }
    }

    private static void ensureNotCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new TerminalAgentService.AgentCancelledException("Terminal agent run cancelled");
        }
    }

    private static byte[] encode(String request, String command) {
        if (command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The agent command cannot contain a NUL character.");
        }
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        StringBuilder frame = new StringBuilder(request.length() + 2 + bytes.length * 5);
        frame.append(request).append(' ');
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            frame.append("\\0")
                .append((char) ('0' + (unsigned >> 6)))
                .append((char) ('0' + ((unsigned >> 3) & 7)))
                .append((char) ('0' + (unsigned & 7)));
        }
        return frame.append('\n').toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Apache SSHD's immediate close can skip SSH_MSG_CHANNEL_CLOSE.
            // Ask the server to tear down the terminal before forcing local cleanup.
            var closeFuture = channel.close(false);
            if (!closeFuture.awaitUninterruptibly(1_000)) {
                channel.close(true);
            }
        }
    }

    private static final class TerminalOutput extends OutputStream {
        private Response response;
        private OutputStream validation;

        synchronized Response begin(String request, boolean capture) {
            if (response != null && !response.status.isDone()) {
                throw new IllegalStateException("Another command is running on the agent's SSH terminal.");
            }
            response = new Response(request, capture);
            return response;
        }

        synchronized void setValidation(OutputStream monitor) {
            validation = monitor;
        }

        synchronized void finishValidation() throws IOException {
            if (validation != null) {
                validation.close();
                validation = null;
            }
        }

        @Override
        public synchronized void write(int value) throws IOException {
            (validation != null ? validation : response).write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            (validation != null ? validation : response).write(bytes, offset, length);
        }
    }

    /** Separates a command's byte output from its opaque exit-status frame. */
    private static final class Response extends OutputStream {
        private final byte[] marker;
        private final ByteArrayOutputStream output;
        private final ByteArrayOutputStream digits = new ByteArrayOutputStream(3);
        private final CompletableFuture<Integer> status = new CompletableFuture<>();
        private int matched;

        Response(String request, boolean capture) {
            marker = ("\u001e" + request + ":").getBytes(StandardCharsets.US_ASCII);
            output = capture ? new ByteArrayOutputStream() : null;
        }

        @Override
        public synchronized void write(int value) {
            if (status.isDone()) {
                return;
            }
            int next = value & 0xff;
            if (matched == marker.length) {
                if (next >= '0' && next <= '9' && digits.size() < 3) {
                    digits.write(next);
                    return;
                }
                if (next == '\n' && digits.size() > 0) {
                    status.complete(Integer.parseInt(digits.toString(StandardCharsets.US_ASCII)));
                    return;
                }
            } else if (next == marker[matched]) {
                matched++;
                return;
            }
            if (output != null) {
                output.write(marker, 0, matched);
                output.writeBytes(digits.toByteArray());
            }
            matched = 0;
            digits.reset();
            if (next == marker[0]) {
                matched = 1;
            } else if (output != null) {
                output.write(next);
            }
        }

        synchronized String text() {
            if (output == null) {
                return "";
            }
            if (!status.isDone()) {
                output.write(marker, 0, matched);
                output.writeBytes(digits.toByteArray());
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
