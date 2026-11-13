package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentOutboundContext;
import de.kortty.core.agent.AgentSudoAuthentication;
import de.kortty.core.agent.AgentSudoPty;
import de.kortty.core.agent.AgentSudoTestSupport;
import de.kortty.core.agent.JournalingAgentCommandRunner;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.I18n;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * The terminal agent masks every prompt it sends to a cloud AI profile at its single AI choke
 * point — the system prompt with the probe snapshot, the history, the repair and turn-limit
 * prompts and the planning prompts — while integrated models and a trusted loopback endpoint get
 * the raw text and the run itself keeps it.
 */
class TerminalAgentServiceOutboundRedactionTest {

    private Path memoryRoot;

    /** The runs read the application's memory; an empty temporary one keeps the counts of these tests exact. */
    @BeforeMethod
    void useEmptyMemory() throws IOException {
        memoryRoot = Files.createTempDirectory("kortty-outbound-memory-");
        MemoryService.replaceInstanceForTesting(new MemoryService(new MemoryStore(memoryRoot.resolve("memory")),
            name -> null));
    }

    @AfterMethod(alwaysRun = true)
    void restoreMemory() throws IOException {
        MemoryService.replaceInstanceForTesting(null);
        try (Stream<Path> paths = Files.walk(memoryRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final String PASSWORD = "Conn-Passw0rd-4711";
    private static final String SUDO_PASSWORD = java.util.UUID.randomUUID() + " ä'\"$";
    private static final String WRONG_SUDO_PASSWORD = java.util.UUID.randomUUID().toString();
    private static final Pattern SUDO_MARKER = Pattern.compile("KORTTY_SUDO_(?:PROMPT|ARMED|READY)_[a-f0-9-]{36}");
    // Split so secret scanners do not flag the fixtures.
    private static final String AWS_KEY_ID = "AKIA" + "QWERTYUIOPASDFGH";
    private static final String GITHUB_TOKEN = "ghp" + "_" + "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String RUN_COMMAND = """
        {"status":"run_commands","summary":"Read the config","userMessage":"Reading.",
         "commands":[{"command":"cat /etc/app.conf","purpose":"Read the config","risk":"read_only"}],
         "needsReprobe":false}
        """;
    private static final String DONE = """
        {"status":"done","summary":"Done","userMessage":"Finished.","commands":[],"needsReprobe":false}
        """;

    private static String sudoDecision(String command) {
        return """
            {"status":"run_commands","summary":"Update the system","userMessage":"Updating.",
             "commands":[{"command":"%s","purpose":"Update the system","risk":"requires_confirmation"}],
             "needsReprobe":false}
            """.formatted(command);
    }

    // ---------------------------------------------------------------- fixtures

    private static AiProfile profile(AiConnectionMode mode, String apiUrl) {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(mode);
        profile.setApiUrl(apiUrl);
        return profile;
    }

    private static AiProfile cloud() {
        return profile(AiConnectionMode.HTTP_API, "https://api.example.com/v1");
    }

    private static AiProfile trustedLoopback() {
        AiProfile profile = profile(AiConnectionMode.HTTP_API, "http://127.0.0.1:1234/v1");
        profile.setTrustedLocalEndpoint(true);
        return profile;
    }

    private static TerminalAgentModels.Request request() {
        return new TerminalAgentModels.Request(
            "session-1", "profile-1", "Show the app config", "test-host", null,
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false, false, false, false, false, false);
    }

    private static SessionJournalRedactor secrets(String password) {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret(password);
        return redactor;
    }

    /** Records every prompt pair it receives and answers from a script indexed by call number. */
    private static final class CapturingAiService implements AiPromptService {
        final List<String> systemPrompts = new CopyOnWriteArrayList<>();
        final List<String> userPrompts = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<String> script;
        private final Runnable onCall;

        CapturingAiService(IntFunction<String> script) {
            this(script, null);
        }

        CapturingAiService(IntFunction<String> script, Runnable onCall) {
            this.script = script;
            this.onCall = onCall;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return executeJsonPrompt(systemPrompt, userPrompt);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            if (onCall != null) {
                onCall.run();
            }
            return new AiExecutionResult(script.apply(calls.incrementAndGet()), null);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        List<String> allPrompts() {
            List<String> all = new ArrayList<>(systemPrompts);
            all.addAll(userPrompts);
            return all;
        }
    }

    /** A connected POSIX runner whose probe and command output carry the fixture secrets. */
    private static final class SecretRunner implements AgentCommandRunner {
        final List<String> executed = new CopyOnWriteArrayList<>();
        private final String password;
        private final String probeExtra;
        private final String output;

        SecretRunner(String password, String probeExtra, String output) {
            this.password = password;
            this.probeExtra = probeExtra;
            this.output = output;
        }

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                               BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
            executed.add(command);
            if (outputConsumer != null) {
                outputConsumer.accept(output);
            }
            return new ExecResult(output, "", 0, false, false);
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            String stdout = String.join("\n",
                "osRelease=Ubuntu 24.04 " + probeExtra,
                "shell=/bin/bash",
                "currentUser=deploy",
                "homeDir=/home/deploy",
                "currentDir=/home/deploy",
                "sudoAvailable=false");
            return new ExecResult(stdout, "", 0, false, false);
        }

        @Override
        public ShellKind shellKind() {
            return ShellKind.POSIX;
        }

        @Override
        public String currentWorkingDirectory() {
            return "/home/deploy";
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public SessionJournalRedactor knownSecrets() {
            return password != null ? secrets(password) : null;
        }
    }

    private static final class RecordingUi implements TerminalAgentService.RunUi {
        final StringBuilder transcript = new StringBuilder();
        final List<TerminalAgentModels.AgentActivity> activities = new CopyOnWriteArrayList<>();
        final List<TerminalAgentModels.RunState> states = new CopyOnWriteArrayList<>();
        final List<TerminalAgentModels.Approval> approvals = new ArrayList<>();
        final List<TerminalAgentModels.PasswordRequest> passwordRequests = new ArrayList<>();
        private final TerminalAgentService.ApprovalDecision approvalDecision;
        private final IntFunction<TerminalAgentModels.PasswordResponse> passwordResponses;

        RecordingUi() {
            this(TerminalAgentService.ApprovalDecision.APPROVE_ONCE, prompt -> null);
        }

        RecordingUi(TerminalAgentService.ApprovalDecision approvalDecision,
                    IntFunction<TerminalAgentModels.PasswordResponse> passwordResponses) {
            this.approvalDecision = approvalDecision;
            this.passwordResponses = passwordResponses;
        }

        @Override
        public void updateState(TerminalAgentModels.RunState state) {
            states.add(state);
        }

        @Override
        public synchronized void appendTranscript(String text) {
            transcript.append(text);
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
            approvals.add(approval);
            return approvalDecision;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) {
            passwordRequests.add(request);
            return passwordResponses.apply(passwordRequests.size());
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void publishActivity(TerminalAgentModels.AgentActivity activity) {
            activities.add(activity);
        }

        List<TerminalAgentModels.AgentActivity> maskedActivities() {
            return activities.stream().filter(activity -> activity.id().endsWith(":outbound-masked")).toList();
        }
    }

    private static class SudoRunner implements AgentCommandRunner {
        final List<String> commands = new ArrayList<>();
        final List<String> credentials = new ArrayList<>();
        private final List<Integer> promptCounts;
        private final String commandError;

        SudoRunner(List<Integer> promptCounts, String commandError) {
            this.promptCounts = promptCounts;
            this.commandError = commandError;
        }

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                               BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
            throw new AssertionError("Expected sudo authentication for command " + command);
        }

        @Override
        public ExecResult execWithSudo(String command, AgentSudoAuthentication auth, Consumer<String> outputConsumer,
                                       BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) throws Exception {
            assertThat(command).doesNotContain(SUDO_PASSWORD);
            assertThat(command).doesNotContain(WRONG_SUDO_PASSWORD);
            int promptCount = promptCounts.get(commands.size());
            commands.add(command);
            Matcher marker = SUDO_MARKER.matcher(command);
            assertThat(marker.find()).isTrue();
            String promptMarker = marker.group();
            assertThat(marker.find()).isTrue();
            String armedMarker = marker.group();
            assertThat(marker.find()).isTrue();
            String readyMarker = marker.group();
            LinkedBlockingQueue<String> replies = new LinkedBlockingQueue<>();
            ByteArrayOutputStream stdin = new ByteArrayOutputStream() {
                @Override
                public synchronized void flush() {
                    replies.add(toString(StandardCharsets.UTF_8));
                    reset();
                }
            };
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            StringBuilder credentialsForCommand = new StringBuilder();
            try (OutputStream monitored = auth.monitor(() -> stdin, stderr)) {
                monitored.write(armedMarker.getBytes(StandardCharsets.US_ASCII));
                for (int i = 0; i < promptCount; i++) {
                    byte[] prompt = promptMarker.getBytes(StandardCharsets.US_ASCII);
                    int split = prompt.length / 2;
                    monitored.write(prompt, 0, split);
                    monitored.write(prompt, split, prompt.length - split);
                    monitored.flush();
                    String response = replies.poll(5, TimeUnit.SECONDS);
                    assertThat(response).isNotNull();
                    credentialsForCommand.append(response);
                }
                monitored.write(readyMarker.getBytes(StandardCharsets.US_ASCII));
                if (commandError != null) {
                    monitored.write((commandError + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            credentials.add(credentialsForCommand.toString());
            return new ExecResult("", stderr.toString(StandardCharsets.UTF_8), commandError == null ? 0 : 1,
                false, false);
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            return new ExecResult("""
                osRelease=Fedora Linux
                shell=/bin/bash
                currentUser=deploy
                homeDir=/home/deploy
                currentDir=/home/deploy
                sudoAvailable=true
                passwordlessSudo=true
                sudoNonInteractive=true
                rootEscalationMode=passwordless_sudo
                """, "", 0, false, false);
        }

        @Override
        public ShellKind shellKind() {
            return ShellKind.POSIX;
        }

        @Override
        public String currentWorkingDirectory() {
            return "/home/deploy";
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    private static final class PtySudoRunner extends SudoRunner {
        final List<List<String>> ptyCommands = new ArrayList<>();
        final List<String> ordinaryCommands = new ArrayList<>();
        final AtomicBoolean cancelCommand = new AtomicBoolean();
        int closed;

        PtySudoRunner() {
            super(List.of(), null);
        }

        @Override
        public boolean supportsSudoPty() {
            return true;
        }

        @Override
        public AgentSudoPty openSudoPty(AgentSudoAuthentication auth, BooleanSupplier cancelled) throws Exception {
            LinkedBlockingQueue<String> replies = new LinkedBlockingQueue<>();
            ByteArrayOutputStream stdin = new ByteArrayOutputStream() {
                @Override
                public synchronized void flush() {
                    replies.add(toString(StandardCharsets.UTF_8));
                    reset();
                }
            };
            try (OutputStream monitor = auth.monitor(() -> stdin, OutputStream.nullOutputStream(), false)) {
                AgentSudoTestSupport.writeArmedMarker(auth, monitor);
                monitor.write(AgentSudoTestSupport.promptMarker(auth).getBytes(StandardCharsets.US_ASCII));
                String response = replies.poll(5, TimeUnit.SECONDS);
                assertThat(response).isEqualTo(SUDO_PASSWORD + "\n");
                credentials.add(response);
                AgentSudoTestSupport.writeReadyMarker(auth, monitor);
            }

            List<String> steps = new ArrayList<>();
            ptyCommands.add(steps);
            return new AgentSudoPty() {
                @Override
                public ExecResult exec(String command, Consumer<String> output, BooleanSupplier cancelled) {
                    steps.add(command);
                    if (cancelCommand.get()) {
                        throw new TerminalAgentService.AgentCancelledException("cancelled on PTY");
                    }
                    String result = "step completed: " + steps.size() + "\n";
                    output.accept(result);
                    return new ExecResult(result, "", steps.size() == 2 ? 17 : 0, false, false);
                }

                @Override
                public void close() {
                    closed++;
                }
            };
        }

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> output,
                               BooleanSupplier cancelled, boolean useTrackedWorkingDirectory) {
            ordinaryCommands.add(command);
            return new ExecResult("ordinary output", "", 0, false, false);
        }
    }

    private static String commandOutput() {
        // The AWS key starts a line: in the JSON history it follows an escaped "\n", which the
        // token pattern's word-boundary check would miss if the JSON were masked as a whole.
        return "db_password " + PASSWORD + " token " + GITHUB_TOKEN + "\n" + AWS_KEY_ID + "\n";
    }

    private static void assertNoSecretIn(List<String> prompts) {
        for (String prompt : prompts) {
            assertThat(prompt).doesNotContain(PASSWORD);
            assertThat(prompt).doesNotContain(AWS_KEY_ID);
            assertThat(prompt).doesNotContain(GITHUB_TOKEN);
        }
    }

    // ---------------------------------------------------------------- tests
    @Test
    void sshSudoPtyUsesOnePasswordPerRunAndPreservesTheNormalCommandRunner() throws Exception {
        String first = "sudo -n growpart /dev/sda 3";
        String second = "sudo -n btrfs filesystem resize max /";
        CapturingAiService ai = new CapturingAiService(call -> switch (call) {
            case 1 -> sudoDecision(first);
            case 2 -> RUN_COMMAND;
            case 3 -> sudoDecision(second);
            default -> DONE;
        });
        CapturingAiService nextAi = new CapturingAiService(call -> call == 1 ? sudoDecision(first) : DONE);
        RecordingUi ui = new RecordingUi(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS,
            prompt -> new TerminalAgentModels.PasswordResponse(SUDO_PASSWORD, true));
        RecordingUi nextUi = new RecordingUi(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS,
            prompt -> new TerminalAgentModels.PasswordResponse(SUDO_PASSWORD, true));
        PtySudoRunner runner = new PtySudoRunner();
        TerminalAgentService service = new TerminalAgentService();

        service.runAgent(null, runner, cloud(), ai, request(), "pty-run", ui);
        service.runAgent(null, runner, cloud(), nextAi, request(), "next-pty-run", nextUi);

        assertThat(runner.ptyCommands).containsExactly(List.of(first, second), List.of(first)).inOrder();
        assertThat(runner.closed).isEqualTo(2);
        assertThat(runner.ordinaryCommands).containsExactly("cat /etc/app.conf");
        assertThat(runner.credentials).containsExactly(SUDO_PASSWORD + "\n", SUDO_PASSWORD + "\n");
        assertThat(ui.approvals).hasSize(1);
        assertThat(ui.passwordRequests).hasSize(1);
        assertThat(nextUi.passwordRequests).hasSize(1);
        assertThat(ui.passwordRequests.getFirst().allowSessionCache()).isFalse();
        assertThat(nextUi.passwordRequests.getFirst().allowSessionCache()).isFalse();
        assertThat(ai.userPrompts.getLast()).contains("step completed: 2");
        assertThat(ai.userPrompts.getLast()).contains("\"exitStatus\":17");
        assertThat(ui.transcript.toString()).doesNotContain(SUDO_PASSWORD);
        for (String prompt : ai.allPrompts()) {
            assertThat(prompt).doesNotContain(SUDO_PASSWORD);
        }
    }

    @Test
    void cancelledSudoCommandClosesPtyWithoutReplaying() {
        String first = "sudo -n growpart /dev/sda 3";
        CapturingAiService ai = new CapturingAiService(call -> sudoDecision(first));
        RecordingUi ui = new RecordingUi(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS,
            prompt -> new TerminalAgentModels.PasswordResponse(SUDO_PASSWORD, false));
        PtySudoRunner runner = new PtySudoRunner();
        runner.cancelCommand.set(true);

        expectThrows(TerminalAgentService.AgentCancelledException.class,
            () -> new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "cancelled-pty-run", ui));

        assertThat(runner.ptyCommands).containsExactly(List.of(first));
        assertThat(runner.closed).isEqualTo(1);
        assertThat(ai.userPrompts).hasSize(1);
    }

    @Test
    void decliningFirstSudoCommandDoesNotOpenThePtyOrRequestAPassword() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> sudoDecision("sudo -n growpart /dev/sda 3"));
        RecordingUi ui = new RecordingUi(TerminalAgentService.ApprovalDecision.CANCEL, prompt -> null);
        PtySudoRunner runner = new PtySudoRunner();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "declined-pty-run", ui);

        assertThat(ui.passwordRequests).isEmpty();
        assertThat(runner.ptyCommands).isEmpty();
        assertThat(runner.credentials).isEmpty();
        assertThat(runner.closed).isEqualTo(0);
    }

    @Test
    void alwaysApproveStillPromptsWhenSudoNeedsPasswordAndReusesItForTheNextCommand() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> switch (call) {
            case 1 -> sudoDecision("sudo -n growpart /dev/sda 3");
            case 2 -> sudoDecision("sudo -n btrfs filesystem resize max /");
            default -> DONE;
        });
        SudoRunner runner = new SudoRunner(List.of(1, 1, 1), null);
        RecordingUi ui = new RecordingUi(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS,
            prompt -> new TerminalAgentModels.PasswordResponse(SUDO_PASSWORD, true));
        RecordingUi nextUi = new RecordingUi();
        CapturingAiService nextAi = new CapturingAiService(call -> call == 1
            ? sudoDecision("sudo -n btrfs filesystem show /") : DONE);

        TerminalAgentService service = new TerminalAgentService();
        try {
            service.runAgent(null, runner, cloud(), ai, request(), "run-sudo-cached", ui);
            service.runAgent(null, runner, cloud(), nextAi, request(), "run-sudo-same-session", nextUi);
        } finally {
            service.clearCachedSudoPassword(request().sessionId());
        }

        assertThat(ui.approvals).hasSize(1);
        assertThat(ui.approvals.getFirst().allowAlways()).isTrue();
        assertThat(ui.passwordRequests).hasSize(1);
        assertThat(ui.passwordRequests.getFirst().command()).isEqualTo("sudo -n growpart /dev/sda 3");
        assertThat(nextUi.passwordRequests).isEmpty();
        assertThat(runner.credentials).containsExactly(SUDO_PASSWORD + "\n", SUDO_PASSWORD + "\n",
            SUDO_PASSWORD + "\n").inOrder();
        assertThat(runner.commands.get(0)).endsWith("'sudo -n growpart /dev/sda 3'");
        assertThat(runner.commands.get(1)).endsWith("'sudo -n btrfs filesystem resize max /'");
        assertThat(runner.commands.get(2)).endsWith("'sudo -n btrfs filesystem show /'");
        assertThat(ui.states.getLast().phase()).isEqualTo(TerminalAgentModels.Phase.DONE);
        assertThat(nextUi.states.getLast().phase()).isEqualTo(TerminalAgentModels.Phase.DONE);
        assertThat(ui.transcript.toString()).doesNotContain(SUDO_PASSWORD);
        assertThat(ui.transcript.toString()).doesNotContain("KORTTY_SUDO_");
        for (String prompt : ai.allPrompts()) {
            assertThat(prompt).doesNotContain(SUDO_PASSWORD);
            assertThat(prompt).doesNotContain("KORTTY_SUDO_");
        }
    }

    @Test
    void sudoCommandFailureWithAnApplicationPasswordMessageDoesNotPromptOrReplay() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> call == 1
            ? sudoDecision("sudo -n btrfs filesystem resize max /") : DONE);
        String commandError = "btrfs: authentication failure while opening a device";
        SudoRunner runner = new SudoRunner(List.of(0), commandError);
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-sudo-command-failure", ui);

        assertThat(ui.passwordRequests).isEmpty();
        assertThat(runner.credentials).containsExactly("");
        assertThat(ai.userPrompts.getLast()).contains(commandError);
    }

    @Test
    void rejectedSudoPasswordIsReplacedBeforeRunningTheNextCommand() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> switch (call) {
            case 1 -> sudoDecision("sudo -n growpart /dev/sda 3");
            case 2 -> sudoDecision("sudo -n btrfs filesystem resize max /");
            default -> DONE;
        });
        SudoRunner runner = new SudoRunner(List.of(2, 1), null);
        RecordingUi ui = new RecordingUi(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS,
            prompt -> new TerminalAgentModels.PasswordResponse(prompt == 1 ? WRONG_SUDO_PASSWORD : SUDO_PASSWORD, true));

        TerminalAgentService service = new TerminalAgentService();
        try {
            service.runAgent(null, runner, cloud(), ai, request(), "run-sudo-rejected", ui);
        } finally {
            service.clearCachedSudoPassword(request().sessionId());
        }

        assertThat(ui.passwordRequests).hasSize(2);
        assertThat(ui.passwordRequests.get(1).command()).isEqualTo(ui.passwordRequests.getFirst().command());
        assertThat(runner.credentials).containsExactly(WRONG_SUDO_PASSWORD + "\n" + SUDO_PASSWORD + "\n",
            SUDO_PASSWORD + "\n").inOrder();
        assertThat(ui.states.getLast().phase()).isEqualTo(TerminalAgentModels.Phase.DONE);
    }


    @Test
    void cloudProfileMasksProbeHistoryRepairAndTurnLimitPrompts() throws Exception {
        // Call 1 is invalid (repair), calls 2..9 plan a command each of the 8 turns, call 10 is
        // the turn-limit final prompt.
        CapturingAiService ai = new CapturingAiService(call -> switch (call) {
            case 1 -> "not json at all";
            case 10 -> DONE;
            default -> RUN_COMMAND;
        });
        SecretRunner runner = new SecretRunner(PASSWORD, AWS_KEY_ID + " " + PASSWORD, commandOutput());
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-cloud", ui);

        assertThat(ai.userPrompts).hasSize(10);
        assertThat(ai.userPrompts.get(1)).contains("Your previous reply was invalid");
        assertThat(ai.userPrompts.get(9)).contains("Turn limit reached");
        assertNoSecretIn(ai.allPrompts());
        assertThat(ai.userPrompts.get(9)).contains(SessionJournalRedactor.REPLACEMENT);
        // The placeholder is explained to the model.
        assertThat(ai.systemPrompts.get(0)).contains(TerminalAgentService.MASKED_SECRET_NOTE);

        // The run itself keeps the raw text.
        assertThat(ui.transcript.toString()).contains(PASSWORD);
        assertThat(ui.transcript.toString()).contains(AWS_KEY_ID);
        assertThat(runner.executed).contains("cat /etc/app.conf");

        // Password, GitHub token and AWS key: three distinct values, however often they repeat.
        List<TerminalAgentModels.AgentActivity> masked = ui.maskedActivities();
        assertThat(masked).isNotEmpty();
        assertThat(masked.get(masked.size() - 1).summary())
            .isEqualTo(I18n.get("ai.agent.outbound.masked.summary", 3));
    }

    @Test
    void distinctCountStaysOneWhenTheSameTokenRepeatsAcrossTurns() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> call <= 3 ? RUN_COMMAND : DONE);
        SecretRunner runner = new SecretRunner(null, "", "key " + AWS_KEY_ID + "\n");
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-repeat", ui);

        assertThat(runner.executed).hasSize(3);
        assertNoSecretIn(ai.allPrompts());
        assertThat(ui.maskedActivities()).isNotEmpty();
        for (TerminalAgentModels.AgentActivity activity : ui.maskedActivities()) {
            assertThat(activity.summary()).isEqualTo(I18n.get("ai.agent.outbound.masked.summary", 1));
        }
    }

    @Test
    void integratedModelsAndTrustedLoopbackGetTheRawText() throws Exception {
        for (AiProfile profile : List.of(
            profile(AiConnectionMode.EMBEDDED_LLAMA_CPP, null),
            profile(AiConnectionMode.EMBEDDED_MLX, null),
            trustedLoopback())) {
            CapturingAiService ai = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE);
            SecretRunner runner = new SecretRunner(PASSWORD, PASSWORD, commandOutput());
            RecordingUi ui = new RecordingUi();

            new TerminalAgentService().runAgent(null, runner, profile, ai, request(), "run-local", ui);

            assertThat(ai.userPrompts.get(0)).contains(PASSWORD);
            assertThat(ai.userPrompts.get(1)).contains(PASSWORD);
            assertThat(ai.userPrompts.get(1)).contains(GITHUB_TOKEN);
            assertThat(ui.maskedActivities()).isEmpty();
        }
    }

    @Test
    void journalingRunnerForwardsTheKnownSecrets() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE);
        SecretRunner inner = new SecretRunner(PASSWORD, "", "plain " + PASSWORD + "\n");
        AgentCommandRunner journaling = new JournalingAgentCommandRunner(inner, AutomationJournalRecorder.NOOP);

        new TerminalAgentService().runAgent(null, journaling, cloud(), ai, request(), "run-journal", new RecordingUi());

        assertNoSecretIn(ai.allPrompts());
        assertThat(ai.userPrompts.get(1)).contains("plain " + SessionJournalRedactor.REPLACEMENT);
    }

    @Test
    void concurrentRunsOnOneServiceDoNotCrossMask() throws Exception {
        String alpha = "alpha-Pass-1111";
        String bravo = "bravo-Pass-2222";
        String output = "values " + alpha + " and " + bravo + "\n";
        TerminalAgentService service = new TerminalAgentService();
        // Both runs wait for each other inside their first AI call, so they really overlap.
        CountDownLatch bothInside = new CountDownLatch(2);
        Runnable rendezvous = () -> {
            bothInside.countDown();
            try {
                bothInside.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CapturingAiService aiAlpha = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE, rendezvous);
        CapturingAiService aiBravo = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE, rendezvous);
        SecretRunner runnerAlpha = new SecretRunner(alpha, "", output);
        SecretRunner runnerBravo = new SecretRunner(bravo, "", output);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                service.runAgent(null, runnerAlpha, cloud(), aiAlpha, request(), "run-alpha", new RecordingUi());
                return null;
            });
            Future<?> b = pool.submit(() -> {
                service.runAgent(null, runnerBravo, cloud(), aiBravo, request(), "run-bravo", new RecordingUi());
                return null;
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String alphaHistory = aiAlpha.userPrompts.get(1);
        String bravoHistory = aiBravo.userPrompts.get(1);
        assertThat(alphaHistory).doesNotContain(alpha);
        assertThat(alphaHistory).contains(bravo);
        assertThat(bravoHistory).doesNotContain(bravo);
        assertThat(bravoHistory).contains(alpha);
    }

    @Test
    void plannedCommandWithThePlaceholderIsRefusedNotExecuted() throws Exception {
        String placeholderCommand = """
            {"status":"run_commands","summary":"Log in","userMessage":"Logging in.",
             "commands":[{"command":"curl -s -u admin:*** https://example.com/health","purpose":"Query","risk":"read_only"}],
             "needsReprobe":false}
            """;
        CapturingAiService ai = new CapturingAiService(call -> call == 1 ? placeholderCommand : DONE);
        SecretRunner runner = new SecretRunner(PASSWORD, "", commandOutput());
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-placeholder", ui);

        assertThat(runner.executed).isEmpty();
        assertThat(ui.transcript.toString())
            .contains(I18n.get("ai.agent.outbound.placeholderRefused", SessionJournalRedactor.REPLACEMENT));
        // The next turn learns why, so it can plan without the masked value.
        assertThat(ai.userPrompts).hasSize(2);
        assertThat(ai.userPrompts.get(1)).contains("Not run: the command contains the masked-secret placeholder");
    }

    @Test
    void placeholderCheckMatchesOnlyTheMarker() {
        assertThat(TerminalAgentService.containsMaskPlaceholder("mysql -p*** -e 'x'")).isTrue();
        assertThat(TerminalAgentService.containsMaskPlaceholder("ls -la /tmp")).isFalse();
        assertThat(TerminalAgentService.containsMaskPlaceholder(null)).isFalse();
    }

    @Test
    void planningPromptsAndTheirRepairAreMasked() throws Exception {
        String validOptions = """
            {"status":"options","summary":"One path","userMessage":"Pick one.",
             "options":[{"title":"Plan","summary":"Do it","feasibility":"High",
               "risks":[],"prerequisites":[],"steps":["Step"],"alternatives":[]}]}
            """;
        CapturingAiService ai = new CapturingAiService(call -> call == 1 ? "{\"status\":\"options\"," : validOptions);
        TerminalAgentModels.ProbeSnapshot probe = new TerminalAgentModels.ProbeSnapshot(
            "Ubuntu " + AWS_KEY_ID, "", "", "bash", "deploy", "", "", List.of(), "/home/deploy",
            "/srv/" + PASSWORD, null, "", List.of(), List.of(), false, false, false, false, "", "");

        new TerminalAgentService().requestPlanningOptions(
            cloud(), ai,
            new TerminalAgentModels.PlanRequest("session-1", "profile-1", "Rotate " + GITHUB_TOKEN, "host"),
            probe, List.of(), "", null, secrets(PASSWORD));

        assertThat(ai.userPrompts).hasSize(2);
        assertNoSecretIn(ai.allPrompts());
        // The probe the caller holds is unchanged.
        assertThat(probe.currentDir()).isEqualTo("/srv/" + PASSWORD);
    }

    @Test
    void maskStructuredLeavesTheOriginalUntouchedAndCountsDistinctValues() {
        List<Integer> reported = Collections.synchronizedList(new ArrayList<>());
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), reported::add, secrets(PASSWORD));
        List<TerminalAgentModels.CommandResult> history = List.of(
            new TerminalAgentModels.CommandResult("cat a", "read", TerminalAgentModels.Risk.READ_ONLY, 0, null,
                PASSWORD + "\n" + AWS_KEY_ID, "", false, false, false, false));

        for (int i = 0; i < 3; i++) {
            List<TerminalAgentModels.CommandResult> masked = context.maskStructured(
                history, new com.google.gson.reflect.TypeToken<List<TerminalAgentModels.CommandResult>>() { }.getType());
            assertThat(masked.get(0).stdoutTail()).isEqualTo("***\nAKIA***");
            assertThat(masked.get(0).exitStatus()).isEqualTo(0);
        }

        assertThat(history.get(0).stdoutTail()).contains(PASSWORD);
        assertThat(context.distinctMaskedCount()).isEqualTo(2);
        assertThat(reported).containsExactly(2);
    }

    @Test
    void sudoPasswordAddedDuringTheRunIsMaskedFromThenOn() {
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), null);
        assertThat(context.mask("echo SudoPw-98765")).isEqualTo("echo SudoPw-98765");
        context.addSecret("SudoPw-98765".toCharArray());
        assertThat(context.mask("echo SudoPw-98765")).isEqualTo("echo ***");
    }
}
