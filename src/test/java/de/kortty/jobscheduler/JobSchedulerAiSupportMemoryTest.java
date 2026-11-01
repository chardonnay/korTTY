package de.kortty.jobscheduler;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiUsageRecorder;
import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.ServerConnection;
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
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/** The headless AI agent of a scheduled job: the global memory and the job's connection memory, masked with the job's secrets. */
class JobSchedulerAiSupportMemoryTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String CONNECTION_ID = "conn-1";
    private static final String GLOBAL_RULE = "Always check disk usage first.";
    private static final String CONNECTION_RULE = "This server is the backup host.";
    private static final String SERVER_PASSWORD = "Srv-Passw0rd-2026";
    private static final String DONE = "{\"status\":\"done\",\"summary\":\"Checked\",\"commands\":[]}";

    private Path root;
    private MemoryStore store;
    private MemoryService service;

    @BeforeMethod
    void useTemporaryMemory() throws IOException {
        root = Files.createTempDirectory("kortty-job-memory-");
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
    void theJobPromptCarriesTheGlobalAndTheConnectionMemoryOnce() throws Exception {
        writeGlobal(GLOBAL_RULE);
        writeConnection(CONNECTION_ID, CONNECTION_RULE);
        CapturingAiService ai = new CapturingAiService();

        supportFor(ai).runAiAgent(job(), new JobSchedulerAiSupport.ServerConnectionContext("srv", CONNECTION_ID),
            null, null, new JobSchedulerSecretRedactor());

        assertThat(ai.systemPrompt).contains(GLOBAL_RULE);
        assertThat(ai.systemPrompt).contains(CONNECTION_RULE);
        assertThat(count(ai.systemPrompt, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
    }

    @Test
    void aJobWithoutAConnectionGetsTheGlobalMemoryOnly() throws Exception {
        writeGlobal(GLOBAL_RULE);
        writeConnection(CONNECTION_ID, CONNECTION_RULE);
        CapturingAiService ai = new CapturingAiService();

        supportFor(ai).runAiAgent(job(), new JobSchedulerAiSupport.ServerConnectionContext("srv"),
            null, null, new JobSchedulerSecretRedactor());

        assertThat(ai.systemPrompt).contains(GLOBAL_RULE);
        assertThat(ai.systemPrompt).doesNotContain(CONNECTION_RULE);
    }

    @Test
    void theJobsKnownSecretsAreMaskedInsideTheMemory() throws Exception {
        writeRaw(MemoryScope.global(), "Note: the server password " + SERVER_PASSWORD + " is rotated monthly.\n");
        CapturingAiService ai = new CapturingAiService();
        JobSchedulerRemoteSession remote = new JobSchedulerRemoteSession(
            null, new ServerConnection("srv", "srv.example", 22, "root"), null, null, true) {
            @Override
            public Optional<String> getPassword() {
                return Optional.of(SERVER_PASSWORD);
            }
        };

        supportFor(ai).runAiAgent(job(), new JobSchedulerAiSupport.ServerConnectionContext("srv", CONNECTION_ID),
            remote, null, new JobSchedulerSecretRedactor());

        assertThat(ai.systemPrompt).contains("Note: the server password");
        assertThat(ai.systemPrompt).contains("is rotated monthly.");
        assertThat(ai.systemPrompt).doesNotContain(SERVER_PASSWORD);
        assertThat(ai.userPrompt).doesNotContain(SERVER_PASSWORD);
    }

    @Test
    void withMemoryDisabledTheJobPromptCarriesNoMemory() throws Exception {
        writeGlobal(GLOBAL_RULE);
        writeConnection(CONNECTION_ID, CONNECTION_RULE);
        service.setEnabledSupplier(() -> false);
        CapturingAiService ai = new CapturingAiService();

        supportFor(ai).runAiAgent(job(), new JobSchedulerAiSupport.ServerConnectionContext("srv", CONNECTION_ID),
            null, null, new JobSchedulerSecretRedactor());

        assertThat(ai.systemPrompt).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
        assertThat(ai.systemPrompt).doesNotContain(GLOBAL_RULE);
        assertThat(ai.systemPrompt).doesNotContain(CONNECTION_RULE);
    }

    // ---------------------------------------------------------------- fixtures

    private JobSchedulerAiSupport supportFor(CapturingAiService ai) {
        AiProfile profile = new AiProfile();
        profile.setId("profile-1");
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        return new JobSchedulerAiSupport(null, (recordedProfile, usage) -> { }) {
            @Override
            AiProfile findAiProfile(String profileId) {
                return profile;
            }

            @Override
            AiPromptService createAiService(AiProfile ignored) {
                return ai;
            }
        };
    }

    private static ScheduledJob job() {
        ScheduledJob job = new ScheduledJob();
        job.getAction().setType(JobActionType.AI_AGENT);
        job.getAction().setAiPrompt("check the backups");
        return job;
    }

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

    private static int count(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }

    /** Keeps the prompts of the one call the job makes. */
    private static final class CapturingAiService implements AiPromptService {
        String systemPrompt;
        String userPrompt;

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return executeJsonPrompt(systemPrompt, userPrompt);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            return new AiExecutionResult(DONE, null);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
