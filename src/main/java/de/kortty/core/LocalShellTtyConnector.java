package de.kortty.core;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.sithtermfx.core.util.TermSize;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connector that runs a LOCAL shell (no network) inside a pty4j PTY: Windows cmd.exe/PowerShell
 * or, on macOS/Linux, the user's {@code $SHELL}. There is no SSH bootstrap; the process is spawned
 * directly. Mirrors {@link NativeMoshTtyConnector}'s PTY mechanics and additionally implements the
 * shared {@link ObservableTtyConnector} hooks so terminal recording and the AI-agent prompt
 * detection work for local shells just like they do for SSH.
 */
public class LocalShellTtyConnector implements ObservableTtyConnector {

    private static final Logger logger = LoggerFactory.getLogger(LocalShellTtyConnector.class);

    private final ServerConnection connection;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private DisconnectListener disconnectListener;
    private final CopyOnWriteArrayList<DataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InputActivityListener> inputActivityListeners = new CopyOnWriteArrayList<>();
    private volatile InputInterceptor inputInterceptor;

    private volatile String startDirectory;
    private volatile PtyProcess ptyProcess;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;
    private volatile InputStreamReader reader;
    private volatile Thread monitorThread;

    // Last OS- or prompt-confirmed local path. It deliberately does not expire: on platforms where
    // no live OS query is available (notably Windows), an absolute prompt path remains useful until
    // submitted input indicates that the shell may have changed directory.
    private volatile String cachedWorkingDirectory;
    private final AtomicBoolean unresolvedWorkingDirectoryChange = new AtomicBoolean(false);
    private final LocalShellDirectoryChangeTracker directoryChangeTracker =
        new LocalShellDirectoryChangeTracker();

    public LocalShellTtyConnector(ServerConnection connection) {
        this.connection = connection;
    }

    public ServerConnection getConnection() {
        return connection;
    }

    public boolean connect() throws IOException {
        if (connection.getProtocol() != ConnectionProtocol.LOCAL_SHELL) {
            throw new IllegalStateException(
                i18n("localShell.protocolMismatch", i18n("protocol.localShell")));
        }
        try {
            List<String> command = resolveShellCommand(connection.getLocalShellCommand());
            int cols = terminalColumns();
            int rows = terminalRows();

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", TerminalEmulationSupport.termName(connection));
            if (env.get("LANG") == null || env.get("LANG").isBlank()) {
                env.put("LANG", "en_US.UTF-8");
            }

            PtyProcessBuilder builder = new PtyProcessBuilder(command.toArray(new String[0]))
                .setEnvironment(env)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setConsole(false);

            String workingDirectory = resolveWorkingDirectory(connection.getLocalShellWorkingDirectory());
            if (workingDirectory != null) {
                builder.setDirectory(workingDirectory);
            }
            // Freeze the effective spawn directory: the live ServerConnection can be edited while
            // this tab is open, and the existence check above can flip later.
            this.startDirectory = workingDirectory != null ? workingDirectory : System.getProperty("user.dir");
            cachedWorkingDirectory = null;
            unresolvedWorkingDirectoryChange.set(false);
            directoryChangeTracker.reset();

            // Log only the executable: the user-configured command line is free-form and may embed
            // credentials in its arguments (e.g. sshpass -p, mysql -p) — CodeQL java/sensitive-log.
            logger.info("Starting local shell ({}x{}) cmd={}", cols, rows,
                command.isEmpty() ? "<none>" : command.get(0));
            ptyProcess = builder.start();
            inputStream = ptyProcess.getInputStream();
            outputStream = ptyProcess.getOutputStream();
            reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);

            connected.set(true);
            startMonitorThread();
            logger.info("Local shell started for {}", connection.getDisplayName());
            return true;
        } catch (Exception e) {
            logger.error("Failed to start local shell for {}: {}",
                connection.getDisplayName(), e.getMessage(), e);
            close();
            throw new IOException(i18n("localShell.startFailed", String.valueOf(e.getMessage())), e);
        }
    }

    /**
     * Resolves the command line to launch. When {@code configuredCommand} is set it is used
     * (quote-aware split into program + args, so paths with spaces survive); otherwise an
     * OS-appropriate default shell is chosen.
     */
    static List<String> resolveShellCommand(String configuredCommand) {
        if (configuredCommand != null && !configuredCommand.isBlank()) {
            List<String> tokens = ServerConnection.tokenizeLocalShellCommand(configuredCommand);
            if (!tokens.isEmpty()) {
                return tokens;
            }
        }
        return defaultShellCommand();
    }

    /** True when running on Windows. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Returns a ready-to-store command that launches Git Bash as a login shell, or {@code null} when
     * not on Windows or Git Bash is not installed. The path is quoted so spaces are preserved.
     */
    public static String findWindowsGitBashCommand() {
        String path = findWindowsGitBashPath();
        return path != null ? "\"" + path + "\" --login -i" : null;
    }

    /** Locates the Git for Windows {@code bash.exe} in the common install locations, or {@code null}. */
    static String findWindowsGitBashPath() {
        if (!isWindows()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        addGitBashCandidate(candidates, System.getenv("ProgramFiles"));
        addGitBashCandidate(candidates, System.getenv("ProgramW6432"));
        addGitBashCandidate(candidates, System.getenv("ProgramFiles(x86)"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(localAppData + "\\Programs\\Git\\bin\\bash.exe");
        }
        // Derive from a Git installation already on PATH (…\Git\cmd → …\Git\bin\bash.exe).
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(";")) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                File d = new File(dir.trim());
                File sibling = new File(d.getParentFile(), "bin\\bash.exe");
                candidates.add(sibling.getPath());
                candidates.add(new File(d, "bash.exe").getPath());
            }
        }
        for (String candidate : candidates) {
            if (candidate != null && new File(candidate).isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static void addGitBashCandidate(List<String> candidates, String programDir) {
        if (programDir != null && !programDir.isBlank()) {
            candidates.add(programDir + "\\Git\\bin\\bash.exe");
        }
    }

    /**
     * Returns a ready-to-store command that launches the Cygwin login shell, or {@code null} when not
     * on Windows or Cygwin is not installed. The path is quoted so spaces are preserved.
     */
    public static String findWindowsCygwinCommand() {
        String path = findWindowsCygwinPath();
        return path != null ? "\"" + path + "\" --login -i" : null;
    }

    /** Locates the Cygwin {@code bash.exe} in the common install locations, or {@code null}. */
    static String findWindowsCygwinPath() {
        if (!isWindows()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add("C:\\cygwin64\\bin\\bash.exe");
        candidates.add("C:\\cygwin\\bin\\bash.exe");
        String sysDrive = System.getenv("SystemDrive");
        if (sysDrive != null && !sysDrive.isBlank()) {
            candidates.add(sysDrive + "\\cygwin64\\bin\\bash.exe");
            candidates.add(sysDrive + "\\cygwin\\bin\\bash.exe");
        }
        for (String candidate : candidates) {
            if (new File(candidate).isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static volatile Boolean wslDistroAvailable;

    /**
     * Returns the command that launches the default WSL distribution, or {@code null} when not on
     * Windows, {@code wsl.exe} is missing, or no WSL distribution is installed.
     */
    public static String findWindowsWslCommand() {
        if (!isWindows()) {
            return null;
        }
        String wslExe = findWslExe();
        if (wslExe == null || !hasInstalledWslDistro(wslExe)) {
            return null;
        }
        return wslExe;
    }

    static String findWslExe() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            File f = new File(systemRoot, "System32\\wsl.exe");
            if (f.isFile()) {
                return f.getPath();
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(";")) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                File f = new File(dir.trim(), "wsl.exe");
                if (f.isFile()) {
                    return f.getPath();
                }
            }
        }
        return null;
    }

    /**
     * Checks (once, cached) whether at least one WSL distribution is installed by running
     * {@code wsl.exe -l -q} with a short timeout. {@code wsl.exe} ships with Windows even when the
     * feature is unused, so mere presence is not enough — a distribution must actually exist.
     */
    private static boolean hasInstalledWslDistro(String wslExe) {
        Boolean cached = wslDistroAvailable;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            Process process = new ProcessBuilder(wslExe, "-l", "-q").start();
            process.getOutputStream().close();
            if (process.waitFor(1200, TimeUnit.MILLISECONDS)) {
                byte[] out = process.getInputStream().readAllBytes();
                // `wsl -l -q` prints distro names in UTF-16LE; strip NULs and check for any content.
                String text = new String(out, java.nio.charset.StandardCharsets.UTF_16LE)
                    .replace(" ", "").trim();
                result = process.exitValue() == 0 && !text.isBlank();
            } else {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            // Treat any failure as "no WSL".
        }
        wslDistroAvailable = result;
        return result;
    }

    static List<String> defaultShellCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // PowerShell is the chosen default on Windows.
            return List.of("powershell.exe");
        }
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            return List.of(shell);
        }
        if (new File("/bin/zsh").exists()) {
            return List.of("/bin/zsh");
        }
        return List.of("/bin/bash");
    }

    private static String resolveWorkingDirectory(String configured) {
        if (configured != null && !configured.isBlank()) {
            File dir = new File(configured.trim());
            if (dir.isDirectory()) {
                return dir.getAbsolutePath();
            }
            logger.warn("Configured local shell working directory does not exist, "
                + "falling back to the home directory: {}", configured);
        }
        // No usable configured directory: default to the user's home directory so the shell never
        // spawns in "/" (the JVM cwd when the app is launched from the macOS Finder/Dock). A fresh
        // local terminal is expected to open in the user's home, not the filesystem root.
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            File homeDir = new File(home);
            if (homeDir.isDirectory()) {
                return homeDir.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * The directory the local shell actually started in: the configured start directory when it
     * existed at spawn time, otherwise the JVM's working directory — pty4j spawns the child there
     * when no directory is set on the builder. Captured once in {@link #connect()} so later edits
     * to the connection cannot make it drift; before connect it reflects the current
     * configuration. Used by features that resolve terminal selections against the shell's
     * filesystem (e.g. "Load as text file").
     */
    public String getStartDirectory() {
        String captured = startDirectory;
        if (captured != null) {
            return captured;
        }
        String resolved = resolveWorkingDirectory(connection.getLocalShellWorkingDirectory());
        return resolved != null ? resolved : System.getProperty("user.dir");
    }

    /**
     * The last trusted shell working directory. Deliberately NON-BLOCKING: it never invokes the OS
     * query itself, because run-context capture occurs on the JavaFX application thread. Once a
     * submitted command may have changed directory, the retained cache is hidden until a live
     * refresh or trusted absolute hint confirms the new value.
     */
    @Override
    public String getCurrentWorkingDirectory() {
        return connected.get() && !unresolvedWorkingDirectoryChange.get()
            ? cachedWorkingDirectory
            : null;
    }

    /** Legacy compatibility for existing remote-directory callers. */
    @Override
    public String getCurrentRemoteDirectory() {
        return getCurrentWorkingDirectory();
    }

    /** Performs the potentially blocking OS lookup and refreshes the trusted local-directory cache. */
    @Override
    public String refreshCurrentWorkingDirectory() {
        return readLiveWorkingDirectory();
    }

    /**
     * Reads the shell's live working directory straight from the OS (its PTY process' cwd), so it
     * reflects every {@code cd} the user has run, and refreshes the short cache read by
     * {@link #getCurrentRemoteDirectory()}. This is the ground truth that
     * {@link RemoteTextFileSelectionSupport#resolveLocalFilePath} needs: unlike SSH there is no
     * OSC-7 stream to track, and prompt parsing alone fails whenever the prompt shows only the
     * directory's basename (the macOS zsh default).
     *
     * <p>BLOCKING — on macOS this forks {@code lsof}; call it OFF the JavaFX thread (e.g. from a
     * background load task). Returns {@code null} when unavailable (Windows, or the query failing),
     * leaving callers to fall back to the prompt-derived path.</p>
     */
    public String readLiveWorkingDirectory() {
        PtyProcess localPty = ptyProcess;
        if (localPty == null || !connected.get()) {
            return null;
        }
        long pid;
        try {
            pid = localPty.pid();
        } catch (UnsupportedOperationException e) {
            return null;
        }
        String live = normalizeTrustedLocalDirectory(LocalProcessDirectory.read(pid));
        if (live != null) {
            cachedWorkingDirectory = live;
            unresolvedWorkingDirectoryChange.set(false);
        }
        return live;
    }

    /**
     * Accepts a prompt/probe-derived hint only when it names an existing absolute directory in the
     * JVM's local filesystem namespace. This rejects relative prompt fragments and foreign paths
     * such as WSL/Git-Bash POSIX paths on Windows.
     */
    @Override
    public void updateCurrentWorkingDirectoryHint(String directory) {
        String trusted = normalizeTrustedLocalDirectory(directory);
        if (trusted != null) {
            cachedWorkingDirectory = trusted;
            unresolvedWorkingDirectoryChange.set(false);
        }
    }

    /** Legacy compatibility for callers that still use the SSH-oriented method name. */
    @Override
    public void updateCurrentRemoteDirectoryHint(String directory) {
        updateCurrentWorkingDirectoryHint(directory);
    }

    /**
     * True after a submitted command may have changed the shell directory and neither a live OS
     * refresh nor a trusted absolute prompt hint has confirmed the new one yet.
     */
    public boolean hasUnresolvedWorkingDirectoryChange() {
        return unresolvedWorkingDirectoryChange.get();
    }

    /** Shared by local agent execution so every local feature applies the same path trust policy. */
    public static String normalizeTrustedLocalDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(directory.trim());
            if (!path.isAbsolute() || !Files.isDirectory(path)) {
                return null;
            }
            return path.toAbsolutePath().normalize().toString();
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    /**
     * On POSIX systems the local shell's home is the user home, which lets the terminal's
     * prompt-based working-directory tracking expand {@code ~}-prefixed prompt paths. On Windows
     * this stays {@code null}: the POSIX-path presets (Git Bash, Cygwin, WSL) have shell-private
     * home directories that do not map onto {@code user.home}.
     */
    @Override
    public String getHomeRemoteDirectory() {
        return isWindows() ? null : System.getProperty("user.home");
    }

    /**
     * True when the local shell currently has at least one live child process — i.e. a command (or a
     * backgrounded job) is running, as opposed to the shell sitting idle at its prompt. Used to decide
     * whether closing the tab needs a confirmation. Best-effort: returns {@code false} if the process
     * tree can't be read.
     */
    public boolean hasRunningChildProcess() {
        PtyProcess localPty = ptyProcess;
        if (localPty == null || !isConnected()) {
            return false;
        }
        try {
            long pid = localPty.pid();
            return ProcessHandle.of(pid)
                .map(handle -> handle.children().anyMatch(ProcessHandle::isAlive))
                .orElse(false);
        } catch (UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    private int terminalColumns() {
        int cols = connection.getSettings() != null ? connection.getSettings().getTerminalColumns() : 0;
        return cols > 0 ? cols : 80;
    }

    private int terminalRows() {
        int rows = connection.getSettings() != null ? connection.getSettings().getTerminalRows() : 0;
        return rows > 0 ? rows : 24;
    }

    public void setDisconnectListener(DisconnectListener disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    @Override
    public void setDataListener(DataListener listener) {
        dataListeners.clear();
        if (listener != null) {
            dataListeners.add(listener);
        }
    }

    @Override
    public void addDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.remove(listener);
        }
    }

    @Override
    public void addInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputActivityListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputActivityListeners.remove(listener);
        }
    }

    @Override
    public void setInputInterceptor(InputInterceptor inputInterceptor) {
        this.inputInterceptor = inputInterceptor;
    }

    private void startMonitorThread() {
        final PtyProcess localPty = ptyProcess;
        if (localPty == null) {
            connected.set(false);
            return;
        }
        monitorThread = new Thread(() -> {
            try {
                int exitCode = localPty.waitFor();
                connected.set(false);
                if (disconnectListener != null) {
                    boolean wasError = exitCode != 0;
                    String reason = wasError
                        ? i18n("localShell.disconnectExitCode", exitCode)
                        : i18n("localShell.disconnectNormal");
                    disconnectListener.onDisconnect(reason, wasError);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logger.warn("Local shell monitor thread error, notifying disconnect", t);
                connected.set(false);
                if (disconnectListener != null) {
                    String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    disconnectListener.onDisconnect(safeI18nFallback("localShell.disconnectError", detail), true);
                }
            }
        }, "LocalShell-Monitor-" + connection.getDisplayName());
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        InputStreamReader localReader = reader;
        if (!connected.get() || localReader == null) {
            return -1;
        }
        int count = localReader.read(buf, offset, length);
        if (count > 0) {
            notifyDataRead(new String(buf, offset, count));
        }
        return count;
    }

    private void notifyDataRead(String data) {
        for (DataListener dataListener : dataListeners) {
            try {
                dataListener.onData(data);
            } catch (Exception e) {
                logger.warn("Data listener error: {}", e.getMessage());
            }
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        OutputStream localOut = outputStream;
        if (connected.get() && localOut != null) {
            byte[] bytesToWrite = applyInputInterceptor(bytes);
            if (bytesToWrite == null || bytesToWrite.length == 0) {
                return;
            }
            notifyInputActivity(bytesToWrite.length);
            if (directoryChangeTracker.accept(bytesToWrite)) {
                unresolvedWorkingDirectoryChange.set(true);
            }
            localOut.write(bytesToWrite);
            localOut.flush();
        }
    }

    @Override
    public void write(String string) throws IOException {
        if (string == null) {
            return;
        }
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] applyInputInterceptor(byte[] bytes) throws IOException {
        InputInterceptor interceptor = inputInterceptor;
        if (interceptor == null || bytes == null || bytes.length == 0) {
            return bytes;
        }
        return interceptor.intercept(bytes);
    }

    private void notifyInputActivity(int byteCount) {
        for (InputActivityListener listener : inputActivityListeners) {
            try {
                listener.onInputActivity(byteCount);
            } catch (Exception e) {
                logger.warn("Input activity listener error: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean isConnected() {
        PtyProcess localPty = ptyProcess;
        return connected.get() && localPty != null && localPty.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        PtyProcess p = ptyProcess;
        if (p == null) {
            return 0;
        }
        return p.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        InputStream localIn = inputStream;
        return connected.get() && localIn != null && localIn.available() > 0;
    }

    @Override
    public void resize(TermSize termSize) {
        PtyProcess localPty = ptyProcess;
        if (connected.get() && localPty != null && localPty.isAlive()) {
            localPty.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
        }
    }

    @Override
    public String getName() {
        return connection.getDisplayName();
    }

    @Override
    public void close() {
        connected.set(false);
        directoryChangeTracker.reset();

        // Destroy the shell process FIRST. A terminal reader thread is typically blocked inside a
        // pty read(); closing the input stream before the process exits deadlocks, because the
        // stream's close() waits for that in-flight read, which only returns once the process dies.
        // Killing the process makes the blocked read() return EOF, so the stream closes below (and
        // the caller — possibly the JavaFX thread during tab/window close) never hang.
        Thread localMonitor = monitorThread;
        monitorThread = null;
        PtyProcess localPty = ptyProcess;
        ptyProcess = null;
        if (localPty != null) {
            localPty.destroy();
            try {
                if (!localPty.waitFor(2, TimeUnit.SECONDS)) {
                    localPty.destroyForcibly();
                    localPty.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                localPty.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (localMonitor != null) {
            localMonitor.interrupt();
        }

        InputStreamReader localReader = reader;
        InputStream localIn = inputStream;
        OutputStream localOut = outputStream;
        reader = null;
        inputStream = null;
        outputStream = null;

        if (localReader != null) {
            try { localReader.close(); } catch (IOException ignored) {}
        }
        if (localIn != null) {
            try { localIn.close(); } catch (IOException ignored) {}
        }
        if (localOut != null) {
            try { localOut.close(); } catch (IOException ignored) {}
        }
    }

    private static String i18n(String key, Object... args) {
        LanguageManager lm = LanguageManager.getInstance();
        if (lm == null) {
            return formatFallback(key, args);
        }
        String s = lm.getString(key, args);
        if (s != null && !s.isEmpty()) {
            return s;
        }
        return formatFallback(key, args);
    }

    private static String formatFallback(String key, Object... args) {
        if (args == null || args.length == 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key);
        for (Object arg : args) {
            sb.append(" ").append(arg);
        }
        return sb.toString();
    }

    /** Null-safe i18n for use in catch blocks where i18n might throw. */
    private static String safeI18nFallback(String key, Object... args) {
        try {
            LanguageManager lm = LanguageManager.getInstance();
            if (lm != null) {
                String s = args == null || args.length == 0 ? lm.getString(key) : lm.getString(key, args);
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return "Connection ended";
    }
}
