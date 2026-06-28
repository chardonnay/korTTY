package de.kortty.core.agent;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.ServerConnection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@link AgentCommandRunner} that executes each agent command as a fresh local process (mirroring
 * the one-shot SSH exec channel). Cross-platform: Windows PowerShell/cmd or a POSIX {@code sh} on
 * macOS/Linux. The agent's command language follows the connection's shell (Windows decision:
 * PowerShell connection → PowerShell, cmd connection → cmd, custom/unknown → PowerShell).
 */
public final class LocalAgentCommandRunner implements AgentCommandRunner {

    private static final Duration COMMAND_WAIT_TIMEOUT = Duration.ofMinutes(15);
    // Force UTF-8 stdout so captured output decodes correctly, regardless of console code page.
    private static final String PS_UTF8_PREFIX = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;";

    private final ServerConnection connection;
    private final ShellKind shellKind;
    private final String workingDirectory;

    public LocalAgentCommandRunner(ServerConnection connection) {
        this.connection = connection;
        this.shellKind = resolveShellKind(connection != null ? connection.getLocalShellCommand() : null);
        this.workingDirectory = resolveWorkingDirectory(
            connection != null ? connection.getLocalShellWorkingDirectory() : null);
    }

    static ShellKind resolveShellKind(String configuredCommand) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            return ShellKind.POSIX;
        }
        String base = configuredCommand == null ? "" : configuredCommand.trim().toLowerCase(Locale.ROOT);
        if (base.contains("cmd")) {
            return ShellKind.WINDOWS_CMD;
        }
        // powershell.exe, pwsh.exe, blank, or any custom command default to PowerShell on Windows.
        return ShellKind.WINDOWS_POWERSHELL;
    }

    private static String resolveWorkingDirectory(String configured) {
        if (configured != null && !configured.isBlank()) {
            File dir = new File(configured.trim());
            if (dir.isDirectory()) {
                return dir.getAbsolutePath();
            }
        }
        String home = System.getProperty("user.home");
        if (home != null && new File(home).isDirectory()) {
            return home;
        }
        return System.getProperty("user.dir");
    }

    /**
     * Builds the argv that runs {@code command} non-interactively in the agent shell. PowerShell uses
     * {@code -EncodedCommand} (Base64 UTF-16LE) so arbitrary commands with quotes/newlines survive
     * Windows' command-line argument quoting unscathed.
     */
    private List<String> wrap(String command) {
        List<String> argv = new ArrayList<>();
        switch (shellKind) {
            case WINDOWS_POWERSHELL -> {
                argv.add("powershell.exe");
                argv.add("-NoProfile");
                argv.add("-NonInteractive");
                argv.add("-EncodedCommand");
                argv.add(encodePowerShell(PS_UTF8_PREFIX + command));
            }
            case WINDOWS_CMD -> {
                argv.add("cmd.exe");
                argv.add("/c");
                argv.add(command);
            }
            default -> {
                argv.add("/bin/sh");
                argv.add("-c");
                argv.add(command);
            }
        }
        return argv;
    }

    private static String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    @Override
    public ExecResult exec(
        String command,
        byte[] stdin,
        Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier,
        boolean useTrackedWorkingDirectory) throws Exception {
        return runProcess(wrap(command), stdin, outputConsumer, cancellationSupplier);
    }

    @Override
    public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) throws Exception {
        if (shellKind == ShellKind.POSIX) {
            return runProcess(List.of("/bin/sh", "-c", AgentProbeScripts.POSIX), null, null, cancellationSupplier);
        }
        // Windows: always probe via PowerShell (encoded so quotes/newlines survive argv quoting),
        // regardless of whether the agent's command shell is PowerShell or cmd.
        String shellLabel = shellKind == ShellKind.WINDOWS_CMD ? "cmd.exe" : "powershell.exe";
        String script = PS_UTF8_PREFIX + AgentProbeScripts.windowsPowerShell(shellLabel);
        List<String> argv = List.of(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePowerShell(script));
        return runProcess(argv, null, null, cancellationSupplier);
    }

    private ExecResult runProcess(
        List<String> argv,
        byte[] stdin,
        Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier) throws Exception {

        ProcessBuilder builder = new ProcessBuilder(argv);
        if (workingDirectory != null) {
            File dir = new File(workingDirectory);
            if (dir.isDirectory()) {
                builder.directory(dir);
            }
        }
        Process process = builder.start();

        if (stdin != null && stdin.length > 0) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin);
                os.flush();
            } catch (Exception ignored) {
                // The process may not read stdin; ignore broken-pipe style failures.
            }
        } else {
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {
            }
        }

        StreamCollector outCollector = new StreamCollector(process.getInputStream());
        StreamCollector errCollector = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(outCollector, "local-agent-stdout");
        Thread errThread = new Thread(errCollector, "local-agent-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        long deadlineNanos = System.nanoTime() + COMMAND_WAIT_TIMEOUT.toNanos();
        boolean timedOut = false;
        while (true) {
            if ((cancellationSupplier != null && cancellationSupplier.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
                process.destroyForcibly();
                throw new TerminalAgentService.AgentCancelledException("Terminal agent run cancelled");
            }
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                break;
            }
            if (System.nanoTime() >= deadlineNanos) {
                process.destroyForcibly();
                timedOut = true;
                break;
            }
        }

        outThread.join(2000);
        errThread.join(2000);
        String stdoutText = outCollector.text();
        String stderrText = errCollector.text();
        emit(outputConsumer, stdoutText);
        emit(outputConsumer, stderrText);
        int exitCode = timedOut ? -1 : safeExitValue(process);
        return new ExecResult(stdoutText, stderrText, exitCode, false, timedOut);
    }

    private static int safeExitValue(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static void emit(Consumer<String> outputConsumer, String text) {
        if (outputConsumer != null && text != null && !text.isBlank()) {
            outputConsumer.accept(text);
            if (!text.endsWith("\n")) {
                outputConsumer.accept("\n");
            }
        }
    }

    @Override
    public ShellKind shellKind() {
        return shellKind;
    }

    @Override
    public String currentWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    public ServerConnection getConnection() {
        return connection;
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
            } catch (Exception ignored) {
                // Process ended / stream closed.
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
