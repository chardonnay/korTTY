package de.kortty.core.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Handles the password exchange off the exec channel's I/O thread. */
public final class AgentSudoAuthentication implements AutoCloseable {

    @FunctionalInterface
    public interface PasswordSource {
        /** Returns a UTF-8 password line to send once, or null to decline. */
        byte[] password(boolean retry) throws Exception;
    }

    private final String promptMarker = "KORTTY_SUDO_PROMPT_" + UUID.randomUUID();
    private final String armedMarker = "KORTTY_SUDO_ARMED_" + UUID.randomUUID();
    private final String readyMarker = "KORTTY_SUDO_READY_" + UUID.randomUUID();
    private final PasswordSource source;
    private final String displayCommand;
    private final UnaryOperator<String> redact;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private volatile Exception failure;

    public AgentSudoAuthentication(String displayCommand, PasswordSource source, UnaryOperator<String> redact) {
        this.displayCommand = displayCommand;
        this.source = source;
        this.redact = redact;
    }

    public String displayCommand() {
        return displayCommand;
    }

    public String redact(String output) {
        return redact.apply(output);
    }

    String promptMarker() {
        return promptMarker;
    }

    String armedMarker() {
        return armedMarker;
    }

    String readyMarker() {
        return readyMarker;
    }

    public OutputStream monitor(Supplier<OutputStream> commandInput, OutputStream commandError) {
        String[] markers = {promptMarker(), armedMarker(), readyMarker()};
        return new OutputStream() {
            private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
            private volatile boolean ready;
            private boolean armed;
            private boolean pendingPrompt;
            private boolean prompted;

            @Override
            public void write(int value) throws IOException {
                int ch = value & 0xff;
                if (pending.size() == 0 && ch != 'K') {
                    commandError.write(ch);
                    return;
                }
                pending.write(ch);
                String candidate = pending.toString(StandardCharsets.US_ASCII);
                for (int i = 0; i < markers.length; i++) {
                    if (markers[i].equals(candidate)) {
                        pending.reset();
                        handleMarker(i);
                        return;
                    }
                    if (markers[i].startsWith(candidate)) {
                        return;
                    }
                }
                byte[] bytes = pending.toByteArray();
                pending.reset();
                if (ch == 'K') {
                    commandError.write(bytes, 0, bytes.length - 1);
                    pending.write(ch);
                } else {
                    commandError.write(bytes);
                }
            }

            private void handleMarker(int marker) {
                if (ready) {
                    return;
                }
                if (marker == 2) {
                    // Disarm on the I/O thread, before processing any of the planned command's output.
                    ready = true;
                    schedule(() -> closeInput(commandInput));
                    return;
                }
                if (marker == 1) {
                    armed = true;
                } else {
                    pendingPrompt = true;
                }
                if (armed && pendingPrompt) {
                    pendingPrompt = false;
                    boolean retry = prompted;
                    prompted = true;
                    schedule(() -> {
                        if (!ready) {
                            answerPrompt(commandInput, retry, () -> ready);
                        }
                    });
                }
            }

            @Override
            public void close() throws IOException {
                pending.writeTo(commandError);
                pending.reset();
            }
        };
    }

    private void schedule(Runnable task) {
        try {
            worker.submit(task);
        } catch (RejectedExecutionException ignored) {
            // The runner has already closed the exchange.
        }
    }

    private void closeInput(Supplier<OutputStream> commandInput) {
        try {
            commandInput.get().close();
        } catch (IOException ignored) {
            // The channel may have finished before EOF was sent.
        }
    }

    private void answerPrompt(Supplier<OutputStream> commandInput, boolean retry, Supplier<Boolean> ready) {
        byte[] password = null;
        try {
            if (failure == null) {
                password = source.password(retry);
            }
        } catch (Exception e) {
            failure = e;
            return;
        }
        try {
            if (ready.get()) {
                return;
            }
            OutputStream input = commandInput.get();
            // A blank line declines this sudo prompt.
            input.write(password != null ? password : new byte[] {'\n'});
            input.flush();
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, (byte) 0);
            }
        }
    }

    public void checkFailure() throws Exception {
        if (failure != null) {
            throw failure;
        }
    }

    public boolean failed() {
        return failure != null;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        try {
            worker.awaitTermination(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
