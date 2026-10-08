package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

class LocalProcessInspectorTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final Predicate<ProcessHandle> IS_SLEEP = handle -> handle.info().command()
        .map(command -> LocalProcessInspector.executableBasename(command).equals("sleep"))
        .orElse(false);

    /** How long a fake agent must survive its start before the test trusts it to keep running. */
    private static final long EXEC_SETTLE_MILLIS = 250;

    // ---- executableBasename ---------------------------------------------------------------------

    @Test
    void executableBasenameStripsDirectoriesExtensionsAndCase() {
        assertThat(LocalProcessInspector.executableBasename("/usr/local/bin/claude")).isEqualTo("claude");
        assertThat(LocalProcessInspector.executableBasename("C:\\Users\\x\\AppData\\npm\\codex.cmd")).isEqualTo("codex");
        assertThat(LocalProcessInspector.executableBasename("CLAUDE.EXE")).isEqualTo("claude");
        assertThat(LocalProcessInspector.executableBasename("gemini.mjs")).isEqualTo("gemini");
        assertThat(LocalProcessInspector.executableBasename("/usr/bin/node")).isEqualTo("node");
    }

    @Test
    void executableBasenameIsEmptyForNullAndBlank() {
        assertThat(LocalProcessInspector.executableBasename(null)).isEmpty();
        assertThat(LocalProcessInspector.executableBasename("")).isEmpty();
        assertThat(LocalProcessInspector.executableBasename("   ")).isEmpty();
    }

    // ---- classify -----------------------------------------------------------------------------

    @Test
    void classifyRecognisesScriptHostByClaudeCodePathSegment() {
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js"), null))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
    }

    @Test
    void classifyFallsBackToCommandLineTokensWhenArgumentsAreEmpty() {
        assertThat(LocalProcessInspector.classify("node", List.of(),
            "node /home/u/.npm/_npx/1a2b3c/node_modules/.bin/codex"))
            .hasValue(CodingAgentKind.CODEX);
    }

    @Test
    void classifyRecognisesAgentExecutableDirectly() {
        assertThat(LocalProcessInspector.classify("/opt/homebrew/bin/gemini", List.of(), null))
            .hasValue(CodingAgentKind.GEMINI_CLI);
        assertThat(LocalProcessInspector.classify("C:\\Users\\x\\AppData\\npm\\claude.cmd", null, null))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
    }

    @Test
    void classifySkipsInterpreterFlagsAndMatchesKindIdSegments() {
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("--no-warnings", "/opt/homebrew/lib/node_modules/@google/gemini-cli/dist/index.js"), null))
            .hasValue(CodingAgentKind.GEMINI_CLI);
        assertThat(LocalProcessInspector.classify("/usr/bin/nodejs",
            List.of("/usr/lib/node_modules/@openai/codex/bin/codex.js"), null))
            .hasValue(CodingAgentKind.CODEX);
    }

    @Test
    void classifyHonoursQuotedWindowsCommandLines() {
        // npm's claude.cmd shim: node.exe under "Program Files", both paths quoted, arguments not reported.
        assertThat(LocalProcessInspector.classify("C:\\Program Files\\nodejs\\node.exe", List.of(),
            "\"C:\\Program Files\\nodejs\\node.exe\"  "
                + "\"C:\\Users\\jd\\AppData\\Roaming\\npm\\node_modules\\@anthropic-ai\\claude-code\\cli.js\""))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
        // Same without a reported command: the first (quoted) token is the executable.
        assertThat(LocalProcessInspector.classify(null, null,
            "\"C:\\Program Files\\nodejs\\node.exe\" \"C:\\Program Files\\nodejs\\node_modules\\@openai\\codex\\bin\\codex.js\""))
            .hasValue(CodingAgentKind.CODEX);
        // A user name with a space in the script path.
        assertThat(LocalProcessInspector.classify("node", List.of(),
            "\"node\"  \"C:\\Users\\John Doe\\AppData\\Roaming\\npm\\node_modules\\@google\\gemini-cli\\dist\\index.js\""))
            .hasValue(CodingAgentKind.GEMINI_CLI);
    }

    @Test
    void tokensSplitOnWhitespaceOutsideDoubleQuotes() {
        assertThat(LocalProcessInspector.tokens("a  b\tc")).containsExactly("a", "b", "c").inOrder();
        assertThat(LocalProcessInspector.tokens("\"C:\\Program Files\\node.exe\" x \"y z\"  \"\""))
            .containsExactly("C:\\Program Files\\node.exe", "x", "y z").inOrder();
        assertThat(LocalProcessInspector.tokens("say \"a \"\"quoted\"\" word\""))
            .containsExactly("say", "a \"quoted\" word").inOrder();
        assertThat(LocalProcessInspector.tokens("\"unterminated run")).containsExactly("unterminated run");
        assertThat(LocalProcessInspector.tokens(null)).isEmpty();
        assertThat(LocalProcessInspector.tokens("   ")).isEmpty();
    }

    @Test
    void classifySkipsInterpreterSubCommandsAndValueTakingFlags() {
        // deno install generates 'deno run <flags> <module>'.
        assertThat(LocalProcessInspector.classify("/usr/bin/deno",
            List.of("run", "-A", "/x/node_modules/@anthropic-ai/claude-code/cli.js"), null))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("-r", "dotenv/config", "/x/node_modules/@anthropic-ai/claude-code/cli.js"), null))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("--import", "tsx", "/opt/homebrew/lib/node_modules/@google/gemini-cli/dist/index.js"), null))
            .hasValue(CodingAgentKind.GEMINI_CLI);
        assertThat(LocalProcessInspector.classify("/usr/bin/bun",
            List.of("x", "@openai/codex"), null)).hasValue(CodingAgentKind.CODEX);
    }

    @Test
    void classifyIgnoresUnrelatedScriptsBelowDirectoriesNamedLikeAnAgent() {
        // An MCP server or Bash-tool script that merely lives under a directory called gemini/claude/codex.
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/home/u/projects/gemini/mcp/server.js"), null)).isEmpty();
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/home/u/claude/tools/server.js"), null)).isEmpty();
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/home/u/codex/index.js"), null)).isEmpty();
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/home/u/projects/claude-code/index.js"), null)).isEmpty();
        // But the package directory itself, below node_modules or its npm scope, still counts.
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/home/u/.bun/install/global/node_modules/claude-code/cli.js"), null))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(LocalProcessInspector.classify("/usr/bin/deno",
            List.of("run", "-A", "npm:@anthropic-ai/claude-code"), null)).hasValue(CodingAgentKind.CLAUDE_CODE);
    }

    @Test
    void classifyRecognisesMiniMaxCodeByBinNameAndScopedPackage() {
        // npm's bin link as invoked through the shebang: node /opt/homebrew/bin/mcode
        assertThat(LocalProcessInspector.classify("/opt/homebrew/bin/node",
            List.of("/opt/homebrew/bin/mcode"), null)).hasValue(CodingAgentKind.MINIMAX_CODE);
        // The package entry point itself is called cli.js; its scoped package directory identifies it.
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/usr/lib/node_modules/@minimax-ai/code/cli.js"), null)).hasValue(CodingAgentKind.MINIMAX_CODE);
        assertThat(LocalProcessInspector.classify("C:\\Program Files\\nodejs\\node.exe", List.of(),
            "\"C:\\Program Files\\nodejs\\node.exe\" "
                + "\"C:\\Users\\jd\\AppData\\Roaming\\npm\\node_modules\\@minimax-ai\\code\\cli.js\""))
            .hasValue(CodingAgentKind.MINIMAX_CODE);
        // Another package that happens to be called "code" is not MiniMax Code.
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/usr/lib/node_modules/@someone/code/cli.js"), null)).isEmpty();
    }

    @Test
    void classifyRecognisesQwenCodeByBinNameAndScopedPackage() {
        // Homebrew's launcher as invoked through its shebang: node /opt/homebrew/bin/qwen
        assertThat(LocalProcessInspector.classify("/opt/homebrew/opt/node/bin/node",
            List.of("/opt/homebrew/bin/qwen"), null)).hasValue(CodingAgentKind.QWEN_CODE);
        // npm's global install, and the --expose-gc child the entry spawns on Windows and under Bun.
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/usr/lib/node_modules/@qwen-code/qwen-code/cli-entry.js"), null))
            .hasValue(CodingAgentKind.QWEN_CODE);
        assertThat(LocalProcessInspector.classify("C:\\Program Files\\nodejs\\node.exe",
            List.of("--expose-gc", "C:\\Users\\jd\\AppData\\Roaming\\npm\\node_modules\\@qwen-code\\qwen-code\\cli.js"), null))
            .hasValue(CodingAgentKind.QWEN_CODE);
    }

    @Test
    void classifyRecognisesOpenCodeAsNativeBinaryAndThroughNpmWrapper() {
        // Homebrew ships a native (Bun-compiled) binary.
        assertThat(LocalProcessInspector.classify("/opt/homebrew/Cellar/opencode/2.0.20/bin/opencode", List.of(), null))
            .hasValue(CodingAgentKind.OPENCODE);
        // npm's opencode-ai wrapper script, and the platform package's binary it starts.
        assertThat(LocalProcessInspector.classify("/usr/bin/node",
            List.of("/usr/lib/node_modules/opencode-ai/bin/opencode"), null)).hasValue(CodingAgentKind.OPENCODE);
        assertThat(LocalProcessInspector.classify(
            "C:\\Users\\jd\\AppData\\Roaming\\npm\\node_modules\\opencode-ai\\node_modules\\opencode-windows-x64\\bin\\opencode.exe",
            null, null)).hasValue(CodingAgentKind.OPENCODE);
    }

    @Test
    void classifyFallsBackToTheProcessTitleWhenAScriptHostReportsNoScript() {
        // MiniMax Code sets process.title = "minimax-code"; on macOS ProcessHandle then reports only node.
        assertThat(LocalProcessInspector.classify("/opt/homebrew/bin/node", List.of(), null,
            () -> Optional.of("minimax-code"))).hasValue(CodingAgentKind.MINIMAX_CODE);
        assertThat(LocalProcessInspector.classify("/opt/homebrew/bin/node", null, "/opt/homebrew/bin/node",
            () -> Optional.of("minimax-code"))).hasValue(CodingAgentKind.MINIMAX_CODE);
        // An untitled node, or a title that is no agent, stays unclassified.
        assertThat(LocalProcessInspector.classify("/opt/homebrew/bin/node", List.of(), null,
            () -> Optional.of("/opt/homebrew/bin/node"))).isEmpty();
        assertThat(LocalProcessInspector.classify("/opt/homebrew/bin/node", List.of(), null,
            Optional::empty)).isEmpty();
    }

    @Test
    void classifyAsksForTheProcessTitleOnlyForAScriptHostWithoutArguments() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<Optional<String>> title = () -> {
            asked.incrementAndGet();
            return Optional.of("minimax-code");
        };
        assertThat(LocalProcessInspector.classify("/bin/zsh", List.of(), null, title)).isEmpty();
        assertThat(LocalProcessInspector.classify("/usr/bin/node", List.of("server.js"), null, title)).isEmpty();
        assertThat(LocalProcessInspector.classify("/usr/local/bin/claude", List.of(), null, title))
            .hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(asked.get()).isEqualTo(0);
    }

    @Test(timeOut = 30_000)
    void findAgentProcessRecognisesANodeProcessByItsTitle() throws Exception {
        skipOnWindows();
        Path node = absoluteBinary("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node");
        Process agent = new ProcessBuilder(node.toString(), "-e",
            "process.title = 'minimax-code'; setTimeout(() => {}, 30000)").start();
        try {
            Optional<AgentProcess> found = Optional.empty();
            long jvmPid = ProcessHandle.current().pid();
            for (int i = 0; i < 50 && found.isEmpty(); i++) {
                found = new LocalProcessInspector().findAgentProcess(jvmPid);
                if (found.isEmpty()) {
                    Thread.sleep(100);
                }
            }
            assertThat(found).isPresent();
            assertThat(found.get().pid()).isEqualTo(agent.pid());
            assertThat(found.get().kind()).isEqualTo(CodingAgentKind.MINIMAX_CODE);
        } finally {
            agent.destroyForcibly();
            agent.waitFor();
        }
    }

    @Test
    void classifyIsEmptyForShellsUnrelatedScriptsAndNull() {
        assertThat(LocalProcessInspector.classify("/bin/zsh", List.of("-l"), null)).isEmpty();
        assertThat(LocalProcessInspector.classify("node", List.of("server.js"), null)).isEmpty();
        assertThat(LocalProcessInspector.classify("node", List.of(), "node server.js")).isEmpty();
        assertThat(LocalProcessInspector.classify(null, null, null)).isEmpty();
        assertThat(LocalProcessInspector.classify("", List.of(), "")).isEmpty();
    }

    // ---- Live process tree ----------------------------------------------------------------------

    @Test(timeOut = 30_000)
    void newestLiveDescendantFindsAChildOfThisJvm() throws Exception {
        skipOnWindows();
        Process sleep = new ProcessBuilder(sleepBinary().toString(), "30").start();
        try {
            Optional<ProcessHandle> found =
                LocalProcessInspector.newestLiveDescendant(ProcessHandle.current().pid(), IS_SLEEP);
            assertThat(found).isPresent();
            assertThat(found.get().pid()).isEqualTo(sleep.pid());
        } finally {
            sleep.destroyForcibly();
            sleep.waitFor();
        }
    }

    @Test(timeOut = 30_000)
    void descendantsIncludeGrandChildren() throws Exception {
        skipOnWindows();
        // The trailing command keeps sh from exec-ing sleep in place, so sleep really is a grand-child.
        Process sh = new ProcessBuilder(shBinary().toString(), "-c", sleepBinary() + " 30; exit 0").start();
        Optional<ProcessHandle> found = Optional.empty();
        try {
            long jvmPid = ProcessHandle.current().pid();
            for (int i = 0; i < 50 && found.isEmpty(); i++) {
                found = LocalProcessInspector.newestLiveDescendant(jvmPid, IS_SLEEP);
                if (found.isEmpty()) {
                    Thread.sleep(100);
                }
            }
            assertThat(found).isPresent();
            assertThat(found.get().pid()).isNotEqualTo(sh.pid());
            assertThat(found.get().parent().map(ProcessHandle::pid)).hasValue(sh.pid());
            try (Stream<ProcessHandle> children = sh.toHandle().children()) {
                assertThat(children.map(ProcessHandle::pid).toList()).contains(found.get().pid());
            }

            sh.destroyForcibly();
            sh.waitFor();
            // The orphaned grand-child is reparented away from this JVM; kill it too so nothing lingers.
            found.get().destroyForcibly();
            boolean stillMatching = true;
            for (int i = 0; i < 50 && stillMatching; i++) {
                stillMatching = LocalProcessInspector.newestLiveDescendant(jvmPid, IS_SLEEP).isPresent();
                if (stillMatching) {
                    Thread.sleep(100);
                }
            }
            assertThat(stillMatching).isFalse();
        } finally {
            sh.destroyForcibly();
            sh.waitFor();
            // The orphan is init's child now: we can signal it but never reap it, so do not wait.
            found.ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test(timeOut = 30_000)
    void newestWinsWhenTwoMatch() throws Exception {
        skipOnWindows();
        Process first = new ProcessBuilder(sleepBinary().toString(), "30").start();
        Process second = null;
        try {
            Thread.sleep(300);
            second = new ProcessBuilder(sleepBinary().toString(), "30").start();
            Optional<ProcessHandle> found =
                LocalProcessInspector.newestLiveDescendant(ProcessHandle.current().pid(), IS_SLEEP);
            assertThat(found).isPresent();
            assertThat(found.get().pid()).isEqualTo(second.pid());
        } finally {
            first.destroyForcibly();
            first.waitFor();
            if (second != null) {
                second.destroyForcibly();
                second.waitFor();
            }
        }
    }

    @Test
    void newestLiveDescendantIsEmptyForUnknownPid() {
        assertThat(LocalProcessInspector.newestLiveDescendant(Long.MAX_VALUE, handle -> true)).isEmpty();
        assertThat(LocalProcessInspector.newestLiveDescendant(-1, handle -> true)).isEmpty();
    }

    @Test
    void newestLiveDescendantSwallowsThrowingPredicate() {
        Optional<ProcessHandle> found = LocalProcessInspector.newestLiveDescendant(
            ProcessHandle.current().pid(), handle -> {
                throw new IllegalStateException("boom");
            });
        assertThat(found).isEmpty();
    }

    // ---- findAgentProcess -----------------------------------------------------------------------

    @Test
    void findAgentProcessIsEmptyBelowTheTestJvmAndForBogusPids() {
        LocalProcessInspector inspector = new LocalProcessInspector();
        assertThat(inspector.findAgentProcess(ProcessHandle.current().pid())).isEmpty();
        assertThat(inspector.findAgentProcess(-1)).isEmpty();
        assertThat(inspector.findAgentProcess(0)).isEmpty();
        assertThat(inspector.findAgentProcess(Long.MAX_VALUE)).isEmpty();
    }

    // ---- sourceFor ------------------------------------------------------------------------------

    @Test
    void sourceForIsEmptyWithoutReadingThePidWhenTheSessionIsForeign() {
        AtomicInteger pidReads = new AtomicInteger();
        Supplier<Optional<AgentProcess>> source = new LocalProcessInspector().sourceFor(() -> {
            pidReads.incrementAndGet();
            return OptionalLong.of(ProcessHandle.current().pid());
        }, () -> true);

        assertThat(source.get()).isEmpty();
        assertThat(pidReads.get()).isEqualTo(0);
    }

    @Test
    void sourceForIsEmptyWhenTheShellPidIsUnknown() {
        AtomicInteger pidReads = new AtomicInteger();
        Supplier<Optional<AgentProcess>> source = new LocalProcessInspector().sourceFor(() -> {
            pidReads.incrementAndGet();
            return OptionalLong.empty();
        }, () -> false);

        assertThat(source.get()).isEmpty();
        assertThat(pidReads.get()).isEqualTo(1);
    }

    @Test(timeOut = 30_000)
    void sourceForDelegatesToFindAgentProcessWhenThePidIsPresent() throws Exception {
        skipOnWindows();
        // A copy of bash named "claude" is, by executable basename, a Claude Code process; blocked on
        // its stdin it runs until destroyed and never forks. Not a copy of sleep: a multicall coreutils
        // (uutils on Ubuntu 26.04, busybox) picks the utility from the executable's own name, so a
        // "claude" copy of it exits within a millisecond as an unknown utility.
        Path dir = Files.createTempDirectory("kortty-codingagent-inspector");
        Path fakeAgent = dir.resolve("claude");
        Files.copy(bashBinary(), fakeAgent);
        assertThat(fakeAgent.toFile().setExecutable(true)).isTrue();
        adHocSignOnMac(fakeAgent);
        Process agent;
        try {
            agent = new ProcessBuilder(fakeAgent.toString(), "-c", "read -r line").start();
        } catch (IOException e) {
            throw new SkipException("copied bash binary cannot be started here: " + e.getMessage());
        }
        try {
            requireStillRunning(agent, fakeAgent);
            AtomicInteger pidReads = new AtomicInteger();
            Supplier<Optional<AgentProcess>> source = new LocalProcessInspector().sourceFor(() -> {
                pidReads.incrementAndGet();
                return OptionalLong.of(ProcessHandle.current().pid());
            }, () -> false);

            // A freshly spawned child does not appear in this JVM's process tree the instant start()
            // returns, so poll the way the sibling descendant tests do rather than reading once.
            Optional<AgentProcess> result = Optional.empty();
            int polls = 0;
            while (polls < 50 && result.isEmpty()) {
                polls++;
                result = source.get();
                if (result.isEmpty()) {
                    Thread.sleep(100);
                }
            }
            assertWithMessage("the source must re-read the shell PID on every call, because the shell"
                    + " may have reconnected between two polls")
                .that(pidReads.get()).isEqualTo(polls);
            assertThat(result).isPresent();
            assertThat(result.get().pid()).isEqualTo(agent.pid());
            assertThat(result.get().kind()).isEqualTo(CodingAgentKind.CLAUDE_CODE);
            assertThat(LocalProcessInspector.executableBasename(result.get().command())).isEqualTo("claude");
            assertThat(result.get().isAlive()).isTrue();
        } finally {
            agent.destroyForcibly();
            agent.waitFor();
            Files.deleteIfExists(fakeAgent);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void sourceForSwallowsSupplierFailures() {
        Supplier<Optional<AgentProcess>> source = new LocalProcessInspector().sourceFor(() -> {
            throw new IllegalStateException("connector gone");
        }, () -> false);
        assertThat(source.get()).isEmpty();
    }

    // ---- toAgentProcess -------------------------------------------------------------------------

    @Test
    void toAgentProcessFallsBackWhenOnlyTheCommandLineIsReported() {
        Optional<AgentProcess> process = LocalProcessInspector.toAgentProcess(new FakeHandle(77L,
            Optional.empty(), Optional.of("/usr/local/bin/claude --resume"), Optional.empty()));

        assertThat(process).isPresent();
        assertThat(process.get().pid()).isEqualTo(77L);
        assertThat(process.get().kind()).isEqualTo(CodingAgentKind.CLAUDE_CODE);
        assertThat(process.get().command()).isEqualTo("?");
        assertThat(process.get().startedAt()).isNull();
    }

    @Test
    void toAgentProcessCopiesCommandAndStartInstant() {
        Instant started = Instant.parse("2026-09-13T10:15:30Z");
        Optional<AgentProcess> process = LocalProcessInspector.toAgentProcess(
            new FakeHandle(78L, Optional.of("/usr/local/bin/claude"), Optional.of(started)));

        assertThat(process).isPresent();
        assertThat(process.get().kind()).isEqualTo(CodingAgentKind.CLAUDE_CODE);
        assertThat(process.get().command()).isEqualTo("/usr/local/bin/claude");
        assertThat(process.get().startedAt()).isEqualTo(started);
    }

    @Test
    void toAgentProcessClassifiesAndSnapshotsFromOneInfoRead() {
        // The agent exits right after the first read; from then on the OS reports nothing about it.
        AtomicInteger infoReads = new AtomicInteger();
        ProcessHandle exiting = new FakeHandle(79L, Optional.of("/usr/local/bin/claude"), Optional.empty()) {
            @Override
            public Info info() {
                return infoReads.getAndIncrement() == 0
                    ? super.info()
                    : new FakeHandle(79L, Optional.empty(), Optional.empty()).info();
            }
        };

        Optional<AgentProcess> process = LocalProcessInspector.toAgentProcess(exiting);

        assertThat(process).isPresent();
        assertThat(process.get().command()).isEqualTo("/usr/local/bin/claude");
        assertThat(infoReads.get()).isEqualTo(1);
    }

    @Test
    void toAgentProcessIsEmptyForNullNonAgentsAndFailingInfo() {
        assertThat(LocalProcessInspector.toAgentProcess(null)).isEmpty();
        // An exited or zombie process reports neither a command nor a command line.
        assertThat(LocalProcessInspector.toAgentProcess(new FakeHandle(1L, Optional.empty(), Optional.empty())))
            .isEmpty();
        assertThat(LocalProcessInspector.toAgentProcess(new FakeHandle(3L, Optional.of("/bin/zsh"), Optional.empty())))
            .isEmpty();
        ProcessHandle throwing = new FakeHandle(2L, Optional.empty(), Optional.empty()) {
            @Override
            public Info info() {
                throw new UnsupportedOperationException("no process info");
            }
        };
        assertThat(LocalProcessInspector.toAgentProcess(throwing)).isEmpty();
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private static Path sleepBinary() {
        return absoluteBinary("sleep", "/bin/sleep", "/usr/bin/sleep");
    }

    private static Path shBinary() {
        return absoluteBinary("sh", "/bin/sh", "/usr/bin/sh");
    }

    private static Path bashBinary() {
        return absoluteBinary("bash", "/bin/bash", "/usr/bin/bash");
    }

    /**
     * The first of {@code candidates} that exists and is executable.
     *
     * <p>These tests spawn real children, and they name them by absolute path rather than letting the
     * OS search {@code PATH}: a relative command name is resolved against an environment the test does
     * not control, which is both a finding CodeQL reports and a real way for a test to run something
     * other than the binary it meant to.
     */
    private static Path absoluteBinary(String name, String... candidates) {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path;
            }
        }
        throw new SkipException("no " + name + " binary found at " + String.join(" or ", candidates));
    }

    /**
     * Turns a fake agent that died on start into an accurate skip rather than a puzzling "no agent
     * found" further down. On Apple Silicon a copied system binary can be refused by the code-signing
     * enforcement and killed immediately, which leaves the process tree with nothing to find.
     *
     * <p>Such a copy dies within milliseconds of starting, so this waits out that window once, before
     * the first poll. Checking liveness before each poll instead races the exit: a poll can classify
     * the copy while it lives and then read it back after it is gone.
     */
    private static void requireStillRunning(Process agent, Path fakeAgent) throws InterruptedException {
        if (!agent.waitFor(EXEC_SETTLE_MILLIS, TimeUnit.MILLISECONDS)) {
            return;
        }
        throw new SkipException("the copy of bash at " + fakeAgent + " exited immediately with "
            + agent.exitValue() + "; this platform will not run a copied system binary, so there is"
            + " no live process to impersonate an agent");
    }

    /**
     * Re-signs a copied binary ad hoc on macOS, where a plain copy of a system binary may no longer
     * satisfy code-signing enforcement. Best effort: a machine without the command-line tools simply
     * skips the test through {@link #requireStillRunning}.
     */
    private static void adHocSignOnMac(Path binary) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return;
        }
        Path codesign = Path.of("/usr/bin/codesign");
        if (!Files.isExecutable(codesign)) {
            // Best effort: without the command-line tools the copy simply will not run, and
            // requireStillRunning turns that into a skip that says so.
            return;
        }
        try {
            new ProcessBuilder(codesign.toString(), "--force", "--sign", "-", binary.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Without codesign the process simply will not start; requireStillRunning explains that.
        }
    }

    private static void skipOnWindows() {
        if (isWindows()) {
            throw new SkipException("live process-tree tests spawn POSIX sleep/sh children");
        }
    }

    /** Minimal ProcessHandle whose info() reports exactly the given command, command line and start instant. */
    private static class FakeHandle implements ProcessHandle {
        private final long pid;
        private final Optional<String> command;
        private final Optional<String> commandLine;
        private final Optional<Instant> startInstant;

        /** A handle whose command line is just its command, as for a process started without arguments. */
        FakeHandle(long pid, Optional<String> command, Optional<Instant> startInstant) {
            this(pid, command, command, startInstant);
        }

        FakeHandle(long pid, Optional<String> command, Optional<String> commandLine, Optional<Instant> startInstant) {
            this.pid = pid;
            this.command = command;
            this.commandLine = commandLine;
            this.startInstant = startInstant;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return new Info() {
                @Override
                public Optional<String> command() {
                    return command;
                }

                @Override
                public Optional<String> commandLine() {
                    return commandLine;
                }

                @Override
                public Optional<String[]> arguments() {
                    return Optional.empty();
                }

                @Override
                public Optional<Instant> startInstant() {
                    return startInstant;
                }

                @Override
                public Optional<Duration> totalCpuDuration() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> user() {
                    return Optional.empty();
                }
            };
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return new CompletableFuture<>();
        }

        @Override
        public boolean supportsNormalTermination() {
            return false;
        }

        @Override
        public boolean destroy() {
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }
}
