package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentCommandRunner.ExecResult;
import de.kortty.core.agent.AgentCommandRunner.ShellKind;
import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * The korTTY memory in the terminal agent's prompts: the agent turns, the planning steps and the
 * repair calls carry it once, it is masked with the run's known secrets, the transport's skills land
 * in front of it, a connection's memory joins the global one, and nothing is sent while memory is off.
 */
class TerminalAgentMemoryPromptTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String GLOBAL_RULE = "Always answer in short sentences.";
    private static final String CONNECTION_RULE = "The database host is db-staging.";
    private static final String CONNECTION_ID = "conn-1";
    private static final String SESSION_PASSWORD = "Memo-Passw0rd-4711";
    private static final String SKILL_TEXT = "Prefer ls -la when listing files.";

    private static final String RUN_COMMAND = """
        {"status":"run_commands","summary":"Read the config","userMessage":"Reading.",
         "commands":[{"command":"cat /etc/app.conf","purpose":"Read the config","risk":"read_only"}],
         "needsReprobe":false}
        """;
    private static final String DONE = """
        {"status":"done","summary":"Done","userMessage":"Finished.","commands":[],"needsReprobe":false}
        """;
    private static final String QUESTIONS = """
        {"status":"questions","summary":"Need scope","userMessage":"Choose the deployment target.",
         "questions":[{"id":"q1","question":"Which target should be used?",
         "options":["Local","Remote"],"allowCustomAnswer":true}]}
        """;
    private static final String OPTIONS = """
        {"status":"options","summary":"Two paths","userMessage":"Pick one.",
         "options":[{"title":"Incremental","summary":"Small scoped change","feasibility":"High",
         "risks":["Regression"],"prerequisites":["Tests"],"steps":["Patch","Test"],"alternatives":["Full rewrite"]}]}
        """;
    private static final String REPORT = """
        {"status":"final_plan","title":"Install package","summary":"Install and validate the package.",
         "userMessage":"Ready.","prerequisites":["Sudo access"],"steps":["Install package","Verify service"],
         "risks":["Package unavailable"],"successCriteria":["Service is active"]}
        """;

    private Path root;
    private MemoryStore store;
    private MemoryService service;

    @BeforeMethod
    void useTemporaryMemory() throws IOException {
        root = Files.createTempDirectory("kortty-agent-memory-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
        service = new MemoryService(store, name -> null);
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> de.kortty.policy.UserMemoryMode.EDITABLE);
        MemoryService.replaceInstanceForTesting(service);
    }

    /** Restores the application's lazily created memory service and removes the temporary files. */
    @AfterMethod(alwaysRun = true)
    void restoreMemory() throws IOException {
        MemoryService.replaceInstanceForTesting(null);
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void theMemoryIsInEveryTurnOnceAndTheSameBlock() throws Exception {
        writeGlobal(GLOBAL_RULE);
        RecordingAiService ai = new RecordingAiService(null, call -> call < 3 ? RUN_COMMAND : DONE);

        new TerminalAgentService().runAgent(null, runner(null), cloud(), ai, request(), "run-turns", new RecordingUi());

        assertThat(ai.systemPrompts).hasSize(3);
        String block = memoryBlockOf(ai.systemPrompts.get(0));
        assertThat(block).contains(GLOBAL_RULE);
        for (String system : ai.systemPrompts) {
            assertThat(count(system, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
            assertThat(memoryBlockOf(system)).isEqualTo(block);
        }
    }

    @Test
    void theMemoryIsInTheRepairCallToo() throws Exception {
        writeGlobal(GLOBAL_RULE);
        RecordingAiService ai = new RecordingAiService(null, call -> call == 1 ? "not json at all" : DONE);

        new TerminalAgentService().runAgent(null, runner(null), cloud(), ai, request(), "run-repair", new RecordingUi());

        assertThat(ai.systemPrompts).hasSize(2);
        for (String system : ai.systemPrompts) {
            assertThat(count(system, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
        }
    }

    @Test
    void thePlanningStepsCarryTheMemoryOnce() throws Exception {
        writeGlobal(GLOBAL_RULE);
        TerminalAgentService planner = new TerminalAgentService();

        RecordingAiService questions = new RecordingAiService(null, call -> QUESTIONS);
        planner.requestPlanningQuestions(cloud(), questions, planRequest(), probe(), null, CONNECTION_ID);

        RecordingAiService options = new RecordingAiService(null, call -> OPTIONS);
        planner.requestPlanningOptions(cloud(), options, planRequest(), probe(), List.of(), "answers", null, null,
            CONNECTION_ID);

        RecordingAiService report = new RecordingAiService(null, call -> REPORT);
        planner.requestPlanningReport(cloud(), report, planRequest(), probe(), List.of(), "answers", planOption(),
            null, null, CONNECTION_ID);

        for (RecordingAiService ai : List.of(questions, options, report)) {
            assertThat(ai.systemPrompts).hasSize(1);
            assertThat(count(ai.systemPrompts.get(0), MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
            assertThat(ai.systemPrompts.get(0)).contains(GLOBAL_RULE);
        }
    }

    @Test
    void aSecretOfTheSessionWrittenIntoMemoryIsMaskedInEveryPrompt() throws Exception {
        writeRaw(MemoryScope.global(), "Vault password for the backups: " + SESSION_PASSWORD + "\n");
        RecordingAiService ai = new RecordingAiService(null, call -> DONE);

        new TerminalAgentService().runAgent(null, runner(SESSION_PASSWORD), cloud(), ai, request(), "run-secret",
            new RecordingUi());

        assertThat(ai.systemPrompts.get(0)).contains("Vault password for the backups:");
        assertThat(ai.systemPrompts.get(0)).contains(SessionJournalRedactor.REPLACEMENT);
        for (String prompt : ai.allPrompts()) {
            assertThat(prompt).doesNotContain(SESSION_PASSWORD);
        }
    }

    @Test
    void theSkillsOfTheTransportLandBeforeTheMemoryBlock() throws Exception {
        writeGlobal(GLOBAL_RULE);
        AiSkillPromptSupport skills = new AiSkillPromptSupport(true, false, List.of(skill("Shell", SKILL_TEXT)));
        RecordingAiService ai = new RecordingAiService(skills, call -> DONE);

        new TerminalAgentService().runAgent(null, runner(null), cloud(), ai, request(), "run-skills", new RecordingUi());

        String system = ai.systemPrompts.get(0);
        assertThat(system).contains(SKILL_TEXT);
        assertThat(count(system, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
        assertThat(system.indexOf(SKILL_TEXT)).isLessThan(system.indexOf(MemoryBlockBuilder.OPEN_TAG));
    }

    @Test
    void aGivenConnectionsMemoryJoinsTheGlobalOne() throws Exception {
        writeGlobal(GLOBAL_RULE);
        writeConnection(CONNECTION_ID, CONNECTION_RULE);
        RecordingAiService withConnection = new RecordingAiService(null, call -> DONE);

        new TerminalAgentService().runAgent(null, runner(null), CONNECTION_ID, cloud(), withConnection, request(),
            "run-connection", new RecordingUi());

        assertThat(withConnection.systemPrompts.get(0)).contains(GLOBAL_RULE);
        assertThat(withConnection.systemPrompts.get(0)).contains(CONNECTION_RULE);
        assertThat(count(withConnection.systemPrompts.get(0), MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);

        RecordingAiService globalOnly = new RecordingAiService(null, call -> DONE);
        new TerminalAgentService().runAgent(null, runner(null), null, cloud(), globalOnly, request(),
            "run-global", new RecordingUi());

        assertThat(globalOnly.systemPrompts.get(0)).contains(GLOBAL_RULE);
        assertThat(globalOnly.systemPrompts.get(0)).doesNotContain(CONNECTION_RULE);
    }

    @Test
    void withMemoryDisabledNothingIsInserted() throws Exception {
        writeGlobal(GLOBAL_RULE);
        writeConnection(CONNECTION_ID, CONNECTION_RULE);
        service.setEnabledSupplier(() -> false);
        RecordingAiService ai = new RecordingAiService(null, call -> call < 2 ? RUN_COMMAND : DONE);

        new TerminalAgentService().runAgent(null, runner(null), CONNECTION_ID, cloud(), ai, request(), "run-off",
            new RecordingUi());

        assertThat(ai.systemPrompts).hasSize(2);
        for (String prompt : ai.allPrompts()) {
            assertThat(prompt).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
            assertThat(prompt).doesNotContain(GLOBAL_RULE);
            assertThat(prompt).doesNotContain(CONNECTION_RULE);
        }
    }

    @Test
    void insertMemoryAddsOneBlockAndLeavesABlankOneOut() {
        String block = MemoryBlockBuilder.OPEN_TAG + "\n" + GLOBAL_RULE + "\n" + MemoryBlockBuilder.CLOSE_TAG;

        assertThat(TerminalAgentService.insertMemory("Base rules.", "")).isEqualTo("Base rules.");
        assertThat(TerminalAgentService.insertMemory("Base rules.", null)).isEqualTo("Base rules.");

        String once = TerminalAgentService.insertMemory("Base rules.", block);
        assertThat(TerminalAgentService.insertMemory(once, block)).isEqualTo(once);
        assertThat(count(once, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixtures

    private void writeGlobal(String text) throws IOException {
        writeMemory(MemoryScope.global(), text);
    }

    private void writeConnection(String connectionId, String text) throws IOException {
        writeMemory(MemoryScope.connection(connectionId), text);
    }

    private void writeMemory(MemoryScope scope, String text) throws IOException {
        MemoryStore.Snapshot current = store.read(scope);
        store.write(scope, current.sha256(), text + "\n", MemoryStore.WriteReason.USER_SAVE, null);
    }

    /** Writes the file without the store's checks, for text the store refuses: a secret. */
    private void writeRaw(MemoryScope scope, String text) throws IOException {
        Path file = store.fileOf(scope);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String memoryBlockOf(String prompt) {
        int open = prompt.indexOf(MemoryBlockBuilder.OPEN_TAG);
        int close = prompt.indexOf(MemoryBlockBuilder.CLOSE_TAG, open) + MemoryBlockBuilder.CLOSE_TAG.length();
        return prompt.substring(open, close);
    }

    private static AiProfile cloud() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        return profile;
    }

    private static TerminalAgentModels.Request request() {
        return new TerminalAgentModels.Request(
            "session-1", "profile-1", "Show the app config", "test-host", null,
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false, false, false, false, false, false);
    }

    private static TerminalAgentModels.PlanRequest planRequest() {
        return new TerminalAgentModels.PlanRequest("session-1", "profile-1", "Install the package.", "test-host");
    }

    private static TerminalAgentModels.PlanOption planOption() {
        return new TerminalAgentModels.PlanOption("option-1", "Incremental", "Small scoped change", "High",
            List.of(), List.of(), List.of("Install package"), List.of());
    }

    private static TerminalAgentModels.ProbeSnapshot probe() {
        return new TerminalAgentModels.ProbeSnapshot(
            "Ubuntu 24.04", "kernel", "x86_64", "bash", "deploy", "1000", "1000", List.of("sudo"),
            "/home/deploy", "/home/deploy", null, "", List.of("apt"), List.of("systemctl"),
            false, true, false, false, "", "sudo_password");
    }

    private static AiSkill skill(String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(name.toLowerCase(Locale.ROOT));
        skill.setName(name);
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        return skill;
    }

    private static int count(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }

    /** Records every prompt pair it receives. With a skill support it adds the skills first, as a transport does. */
    private static final class RecordingAiService implements AiPromptService {
        final List<String> systemPrompts = new CopyOnWriteArrayList<>();
        final List<String> userPrompts = new CopyOnWriteArrayList<>();
        private final AiSkillPromptSupport transportSkills;
        private final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<String> script;

        RecordingAiService(AiSkillPromptSupport transportSkills, IntFunction<String> script) {
            this.transportSkills = transportSkills;
            this.script = script;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return executeJsonPrompt(systemPrompt, userPrompt);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            String sent = transportSkills != null
                ? transportSkills.appendAgentSkills(systemPrompt, userPrompt)
                : systemPrompt;
            systemPrompts.add(sent);
            userPrompts.add(userPrompt);
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

    /** A connected POSIX runner that knows the given session password, or none. */
    private static AgentCommandRunner runner(String password) {
        return new AgentCommandRunner() {
            @Override
            public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                                   BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
                if (outputConsumer != null) {
                    outputConsumer.accept("ok\n");
                }
                return new ExecResult("ok\n", "", 0, false, false);
            }

            @Override
            public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
                String stdout = String.join("\n",
                    "osRelease=Ubuntu 24.04",
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
                if (password == null) {
                    return null;
                }
                SessionJournalRedactor redactor = new SessionJournalRedactor();
                redactor.addSecret(password);
                return redactor;
            }
        };
    }

    private static final class RecordingUi implements TerminalAgentService.RunUi {
        @Override
        public void updateState(TerminalAgentModels.RunState state) {
        }

        @Override
        public void appendTranscript(String text) {
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
            return TerminalAgentService.ApprovalDecision.APPROVE_ONCE;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) {
            return null;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
