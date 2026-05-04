package de.kortty.jobscheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class DefaultRsyncProcessExecutor implements RsyncProcessExecutor {

    private static final long POLL_MILLIS = 250L;
    private static final long TERMINATION_WAIT_SECONDS = 2L;

    @Override
    public RsyncProcessResult execute(List<String> command, Map<String, String> environment) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (environment != null && !environment.isEmpty()) {
            processBuilder.environment().putAll(environment);
        }
        Process process = processBuilder.start();
        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread stdoutReader = readerThread("JobScheduler-Rsync-stdout", process.getInputStream(), stdout, readFailure);
        Thread stderrReader = readerThread("JobScheduler-Rsync-stderr", process.getErrorStream(), stderr, readFailure);
        stdoutReader.start();
        stderrReader.start();
        int exitCode = waitForProcess(process);
        stdoutReader.join();
        stderrReader.join();
        IOException failure = readFailure.get();
        if (failure != null) {
            throw failure;
        }
        return new RsyncProcessResult(exitCode, stdout.get(), stderr.get());
    }

    private int waitForProcess(Process process) throws Exception {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    terminate(process);
                    throw new IOException("JobScheduler rsync cancelled.");
                }
                if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return process.exitValue();
                }
            }
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("JobScheduler rsync cancelled.", e);
        }
    }

    private void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private Thread readerThread(
        String name,
        InputStream stream,
        AtomicReference<String> output,
        AtomicReference<IOException> readFailure) {

        Thread thread = new Thread(() -> {
            try (InputStream in = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                in.transferTo(buffer);
                output.set(buffer.toString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                readFailure.compareAndSet(null, e);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
}
