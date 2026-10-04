package de.kortty.jobscheduler;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import org.testng.annotations.Test;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerAiSupportTest {

    @Test
    void executeAgentJsonPromptFallsBackWhenProviderRejectsResponseFormat() throws Exception {
        ResponseFormatRejectingAiService aiService = new ResponseFormatRejectingAiService(
            "{\"status\":\"done\",\"summary\":\"ok\",\"commands\":[]}");

        AiExecutionResult result = JobSchedulerAiSupport.executeAgentJsonPrompt(
            aiService,
            "system",
            "user");

        assertThat(result.content()).contains("\"status\":\"done\"");
        assertThat(aiService.jsonPromptCalls).isEqualTo(1);
        assertThat(aiService.fallbackPromptCalls).isEqualTo(1);
    }

    @Test
    void tokenCountIsBookedAgainstTheQuotaInsteadOfBeingRedactedAsASecret() throws Exception {
        java.util.List<de.kortty.core.AiTokenUsage> recorded = new java.util.ArrayList<>();
        de.kortty.model.AiProfile profile = new de.kortty.model.AiProfile();
        profile.setId("p");
        AiPromptService aiService = new FixedUsageAiService(
            "{\"status\":\"done\",\"summary\":\"Checked 1234 files\",\"commands\":[]}",
            new de.kortty.core.AiTokenUsage(1000, 234, 1234));
        JobSchedulerAiSupport support = new JobSchedulerAiSupport(null, (p, usage) -> recorded.add(usage)) {
            @Override
            de.kortty.model.AiProfile findAiProfile(String profileId) {
                return profile;
            }

            @Override
            AiPromptService createAiService(de.kortty.model.AiProfile ignored) {
                return aiService;
            }
        };
        ScheduledJob job = new ScheduledJob();
        job.getAction().setType(JobActionType.AI_AGENT);
        job.getAction().setAiPrompt("count files");
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();

        JobExecutionOutcome outcome = support.runAiAgent(
            job, new JobSchedulerAiSupport.ServerConnectionContext("host"), null, null, redactor);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).totalTokens()).isEqualTo(1234L);
        assertThat(redactor.redact(outcome.summary())).isEqualTo("Checked 1234 files");
    }

    private static final String SERVER_PASSWORD = "Stored-Srv-Passw0rd";
    private static final String SUDO_PASSWORD = "Stored-Sudo-Passw0rd";

    private static String capturedAgentPrompt(de.kortty.model.AiConnectionMode mode) throws Exception {
        de.kortty.model.AiProfile profile = new de.kortty.model.AiProfile();
        profile.setId("p");
        profile.setConnectionMode(mode);
        profile.setApiUrl(mode == de.kortty.model.AiConnectionMode.HTTP_API ? "https://api.example.com/v1" : null);
        CapturingAiService aiService = new CapturingAiService(
            "{\"status\":\"done\",\"summary\":\"ok\",\"commands\":[]}");
        JobSchedulerAiSupport support = new JobSchedulerAiSupport(null, (p, usage) -> { }) {
            @Override
            de.kortty.model.AiProfile findAiProfile(String profileId) {
                return profile;
            }

            @Override
            AiPromptService createAiService(de.kortty.model.AiProfile ignored) {
                return aiService;
            }
        };
        ScheduledJob job = new ScheduledJob();
        job.getAction().setType(JobActionType.AI_AGENT);
        job.getAction().setAiPrompt("log in with " + SERVER_PASSWORD + " and use sudo password " + SUDO_PASSWORD);
        JobSchedulerRemoteSession remote = new JobSchedulerRemoteSession(
            null, new de.kortty.model.ServerConnection("srv", "srv.example", 22, "root"), null, null, true) {
            @Override
            public java.util.Optional<String> getPassword() {
                return java.util.Optional.of(SERVER_PASSWORD);
            }
        };

        support.runAiAgent(job, new JobSchedulerAiSupport.ServerConnectionContext("srv"), remote,
            SUDO_PASSWORD, new JobSchedulerSecretRedactor());
        return aiService.userPrompt;
    }

    @Test
    void scheduledAgentPromptCarriesNoStoredServerOrSudoPasswordForACloudProfile() throws Exception {
        String prompt = capturedAgentPrompt(de.kortty.model.AiConnectionMode.HTTP_API);

        assertThat(prompt).contains("Job prompt:");
        assertThat(prompt).doesNotContain(SERVER_PASSWORD);
        assertThat(prompt).doesNotContain(SUDO_PASSWORD);
        assertThat(prompt).contains("log in with ***");
    }

    @Test
    void scheduledAgentPromptStaysRawForAnIntegratedModel() throws Exception {
        String prompt = capturedAgentPrompt(de.kortty.model.AiConnectionMode.EMBEDDED_LLAMA_CPP);

        assertThat(prompt).contains(SERVER_PASSWORD);
        assertThat(prompt).contains(SUDO_PASSWORD);
    }

    @Test
    void knownSecretsIncludeWhatTheJobRedactorAlreadyHolds() {
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        redactor.addSecret("Vault-Secret-123");

        de.kortty.core.SessionJournalRedactor known = JobSchedulerAiSupport.knownSecrets(null, null, redactor);

        assertThat(known.redact("x Vault-Secret-123 y")).isEqualTo("x *** y");
    }

    // ------------------------------------------------- D8: agent execution CONFIRM on scheduled jobs

    private static de.kortty.policy.EffectivePolicy agentExecution(de.kortty.policy.AgentExecutionMode mode) {
        de.kortty.policy.PolicyRule rule = de.kortty.policy.PolicyRule.builder().agentExecution(mode).build();
        return de.kortty.policy.EffectivePolicy.resolve(new de.kortty.policy.PolicyFile(1, "ACME",
            java.util.Map.of(), java.util.List.of(rule), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of()), new de.kortty.policy.PolicyIdentity() {
                @Override
                public String userName() {
                    return "u";
                }

                @Override
                public java.util.Set<String> osGroups() {
                    return java.util.Set.of();
                }
            });
    }

    /** A session that records what would run on the server instead of connecting anywhere. */
    private static final class RecordingRemoteSession extends JobSchedulerRemoteSession {
        final java.util.List<String> executed = new java.util.ArrayList<>();

        RecordingRemoteSession() {
            super(null, new de.kortty.model.ServerConnection("srv", "srv.example", 22, "root"), null, null, true);
        }

        @Override
        public java.util.Optional<String> getPassword() {
            return java.util.Optional.empty();
        }

        @Override
        public CommandResult execute(String command, String stdin) {
            executed.add(command);
            return new CommandResult(0, "ok\n", "");
        }
    }

    private static JobExecutionOutcome runPlannedCommand(
        de.kortty.policy.EffectivePolicy policy, RecordingRemoteSession remote, String command, String risk)
        throws Exception {
        de.kortty.model.AiProfile profile = new de.kortty.model.AiProfile();
        profile.setId("p");
        AiPromptService aiService = new CapturingAiService(
            "{\"status\":\"run_commands\",\"summary\":\"plan\",\"commands\":[{\"command\":\""
                + command + "\",\"purpose\":\"x\",\"risk\":\"" + risk + "\"}]}");
        JobSchedulerAiSupport support = new JobSchedulerAiSupport(null, (p, usage) -> { }, () -> policy) {
            @Override
            de.kortty.model.AiProfile findAiProfile(String profileId) {
                return profile;
            }

            @Override
            AiPromptService createAiService(de.kortty.model.AiProfile ignored) {
                return aiService;
            }
        };
        ScheduledJob job = new ScheduledJob();
        job.getAction().setType(JobActionType.AI_AGENT);
        job.getAction().setAiPrompt("tidy up");
        job.getAction().setAiAutoApproveCommands(true);
        return support.runAiAgent(job, new JobSchedulerAiSupport.ServerConnectionContext("srv"), remote,
            null, new JobSchedulerSecretRedactor());
    }

    @Test
    void confirmPolicyBlocksAServerChangingCommandEvenWithAutoApprove() throws Exception {
        RecordingRemoteSession remote = new RecordingRemoteSession();

        JobExecutionOutcome outcome = runPlannedCommand(
            agentExecution(de.kortty.policy.AgentExecutionMode.CONFIRM), remote, "touch /tmp/kortty-marker", "LOW");

        assertThat(outcome.status()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(outcome.summary()).isEqualTo(de.kortty.ui.I18n.get("jobscheduler.dialog.policy.aiConfirmBlocked"));
        assertThat(outcome.detail()).isEqualTo("touch /tmp/kortty-marker");
        assertThat(remote.executed).isEmpty();
    }

    @Test
    void confirmPolicyStillRunsAReadOnlyCommand() throws Exception {
        RecordingRemoteSession remote = new RecordingRemoteSession();

        JobExecutionOutcome outcome = runPlannedCommand(
            agentExecution(de.kortty.policy.AgentExecutionMode.CONFIRM), remote, "df -h", "LOW");

        assertThat(outcome.status()).isEqualTo(JobRunStatus.SUCCESS);
        assertThat(remote.executed).hasSize(1);
        assertThat(remote.executed.get(0)).contains("df -h");
    }

    @Test
    void allowPolicyKeepsRunningAutoApprovedServerChangingCommands() throws Exception {
        RecordingRemoteSession remote = new RecordingRemoteSession();

        JobExecutionOutcome outcome = runPlannedCommand(
            agentExecution(de.kortty.policy.AgentExecutionMode.ALLOW), remote, "touch /tmp/kortty-marker", "LOW");

        assertThat(outcome.status()).isEqualTo(JobRunStatus.SUCCESS);
        assertThat(remote.executed).hasSize(1);
    }

    @Test
    void readOnlyPolicyBlocksTheAgentBeforeTheAiIsAsked() throws Exception {
        RecordingRemoteSession remote = new RecordingRemoteSession();

        JobExecutionOutcome outcome = runPlannedCommand(
            agentExecution(de.kortty.policy.AgentExecutionMode.READ_ONLY), remote, "df -h", "LOW");

        assertThat(outcome.status()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(outcome.summary()).isEqualTo(de.kortty.ui.I18n.get("jobscheduler.dialog.policy.aiReadOnly"));
        assertThat(remote.executed).isEmpty();
    }

    @Test
    void executeAgentJsonPromptKeepsNonResponseFormatErrors() throws Exception {
        FailingAiService aiService = new FailingAiService();

        try {
            JobSchedulerAiSupport.executeAgentJsonPrompt(aiService, "system", "user");
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
            assertThat(expected).hasMessageThat().contains("network unavailable");
        }
        assertThat(aiService.fallbackPromptCalls).isEqualTo(0);
    }

    @Test
    void autoApprovalIsOnlyRequiredForServerChangingCommands() {
        assertThat(JobSchedulerAiSupport.requiresAutoApprovalForServerChange(
            new JobSchedulerAiSupport.AgentCommand("find /var/log -type f | head", "Inspect logs", "LOW"),
            "find /var/log -type f | head")).isFalse();

        assertThat(JobSchedulerAiSupport.requiresAutoApprovalForServerChange(
            new JobSchedulerAiSupport.AgentCommand("touch /tmp/kortty-test", "Create marker", "LOW"),
            "touch /tmp/kortty-test")).isTrue();

        assertThat(JobSchedulerAiSupport.requiresAutoApprovalForServerChange(
            new JobSchedulerAiSupport.AgentCommand("dnf install -y tmux", "Install package", "REQUIRES_CONFIRMATION"),
            "dnf install -y tmux")).isTrue();
    }

    /** Test double for providers that reject OpenAI JSON mode response_format. */
    private static final class ResponseFormatRejectingAiService implements AiPromptService {
        private final String fallbackResponse;
        private int jsonPromptCalls;
        private int fallbackPromptCalls;

        private ResponseFormatRejectingAiService(String fallbackResponse) {
            this.fallbackResponse = fallbackResponse;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return new AiExecutionResult(fallbackResponse, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(fallbackResponse, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws IOException {
            jsonPromptCalls++;
            throw new IOException("AI API error 400: {\"error\":\"'response_format.type' must be 'json_schema' or 'text'\"}");
        }

        @Override
        public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) {
            fallbackPromptCalls++;
            return new AiExecutionResult(fallbackResponse, null);
        }
    }

    /** Test double for unrelated AI failures. */
    private static final class FailingAiService implements AiPromptService {
        private int fallbackPromptCalls;

        @Override
        public AiExecutionResult execute(AiRequest request) throws IOException {
            throw new IOException("network unavailable");
        }

        @Override
        public boolean testConnection() {
            return false;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("network unavailable");
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("network unavailable");
        }

        @Override
        public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws IOException {
            fallbackPromptCalls++;
            throw new IOException("network unavailable");
        }
    }

    private static final class FixedUsageAiService implements AiPromptService {
        private final String content;
        private final de.kortty.core.AiTokenUsage usage;

        FixedUsageAiService(String content, de.kortty.core.AiTokenUsage usage) {
            this.content = content;
            this.usage = usage;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(content, usage);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(content, usage);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    private static final class CapturingAiService implements AiPromptService {
        private final String content;
        private String userPrompt;

        CapturingAiService(String content) {
            this.content = content;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            this.userPrompt = userPrompt;
            return new AiExecutionResult(content, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return executePrompt(systemPrompt, userPrompt);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
