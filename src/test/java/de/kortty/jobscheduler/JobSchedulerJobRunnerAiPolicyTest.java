package de.kortty.jobscheduler;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.policy.AgentExecutionMode;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyDecision;
import de.kortty.policy.PolicyFeature;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
import de.kortty.ui.I18n;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * D8: scheduled AI jobs honour the enterprise AI policy before any connection is opened. The jobs
 * here select no target, so a job that got past the gate stops in target resolution with
 * {@link #NO_TARGET} instead of with a policy reason.
 */
class JobSchedulerJobRunnerAiPolicyTest {

    private static final String NO_TARGET = "No server connection or server group is selected.";

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-job-aipolicy");
    }

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static EffectivePolicy policy(Map<PolicyFeature, PolicyDecision> features, AgentExecutionMode mode) {
        PolicyRule rule = PolicyRule.builder().features(features).agentExecution(mode).build();
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of()), new PolicyIdentity() {
                @Override
                public String userName() {
                    return "u";
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
    }

    private JobSchedulerJobRunner runner(EffectivePolicy policy) {
        return new JobSchedulerJobRunner(null, new JobSchedulerRepository(dir), new JobSchedulerRsyncSupport(null),
            () -> policy);
    }

    private static ScheduledJob job(JobActionType type) {
        JobAction action = new JobAction();
        action.setType(type);
        action.setAiPrompt("check disk space");
        action.setAiAutoApproveCommands(true);
        ScheduledJob job = new ScheduledJob();
        job.setName("ai");
        job.setAction(action);
        return job;
    }

    private static void assertBlockedBeforeConnecting(JobSchedulerJobRunner runner, JobActionType type, String key) {
        JobExecutionOutcome outcome = runner.run(job(type), "run-1");
        String expected = I18n.get(key);
        assertWithMessage(type.name()).that(outcome.status()).isEqualTo(JobRunStatus.BLOCKED);
        assertWithMessage(type.name() + ": blocked by the gate, not by a later failure")
            .that(outcome.summary()).isEqualTo(expected);
        assertWithMessage(type.name()).that(outcome.detail()).isEqualTo(expected);
    }

    @Test
    void aiDeniedBlocksBothAiJobTypes() {
        JobSchedulerJobRunner runner = runner(policy(Map.of(PolicyFeature.AI, PolicyDecision.DENY), null));
        assertBlockedBeforeConnecting(runner, JobActionType.AI_AGENT, "jobscheduler.dialog.policy.aiAgentDenied");
        assertBlockedBeforeConnecting(runner, JobActionType.AI_SWARM, "jobscheduler.dialog.policy.aiAgentDenied");
    }

    @Test
    void agentDeniedBlocksBothAiJobTypesBecauseEverySwarmMemberIsAnAgent() {
        JobSchedulerJobRunner runner = runner(policy(Map.of(PolicyFeature.AI_AGENT, PolicyDecision.DENY), null));
        assertBlockedBeforeConnecting(runner, JobActionType.AI_AGENT, "jobscheduler.dialog.policy.aiAgentDenied");
        assertBlockedBeforeConnecting(runner, JobActionType.AI_SWARM, "jobscheduler.dialog.policy.aiAgentDenied");
    }

    @Test
    void swarmDeniedBlocksOnlyTheSwarmJob() {
        EffectivePolicy swarmDenied = policy(Map.of(PolicyFeature.AI_SWARM, PolicyDecision.DENY), null);
        JobSchedulerJobRunner runner = runner(swarmDenied);
        assertBlockedBeforeConnecting(runner, JobActionType.AI_SWARM, "jobscheduler.dialog.policy.aiSwarmDenied");

        JobAction agent = job(JobActionType.AI_AGENT).getAction();
        assertThat(runner.aiPolicyRefusal(agent)).isEmpty();
        assertThat(runner.run(job(JobActionType.AI_AGENT), "run-2").summary()).isEqualTo(NO_TARGET);
    }

    @Test
    void readOnlyAgentExecutionBlocksBothAiJobTypes() {
        JobSchedulerJobRunner runner = runner(policy(Map.of(), AgentExecutionMode.READ_ONLY));
        assertBlockedBeforeConnecting(runner, JobActionType.AI_AGENT, "jobscheduler.dialog.policy.aiReadOnly");
        assertBlockedBeforeConnecting(runner, JobActionType.AI_SWARM, "jobscheduler.dialog.policy.aiReadOnly");
    }

    @Test
    void lockdownBlocksAiJobs() {
        JobSchedulerJobRunner runner = runner(EffectivePolicy.lockdown());
        assertThat(runner.run(job(JobActionType.AI_AGENT), "run-1").status()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(runner.run(job(JobActionType.AI_SWARM), "run-1").status()).isEqualTo(JobRunStatus.BLOCKED);
    }

    @Test
    void allowingPoliciesLeaveAiJobsUnchanged() {
        for (EffectivePolicy allowing : List.of(
                EffectivePolicy.unrestricted(),
                policy(Map.of(PolicyFeature.AI_AGENT, PolicyDecision.ALLOW), AgentExecutionMode.ALLOW),
                // CONFIRM is enforced per command, not by refusing the whole job.
                policy(Map.of(), AgentExecutionMode.CONFIRM))) {
            JobSchedulerJobRunner runner = runner(allowing);
            for (JobActionType type : List.of(JobActionType.AI_AGENT, JobActionType.AI_SWARM)) {
                assertWithMessage(type.name()).that(runner.aiPolicyRefusal(job(type).getAction())).isEmpty();
                // Past the gate: stops in target resolution, i.e. behaves as before.
                assertWithMessage(type.name()).that(runner.run(job(type), "run-1").summary())
                    .isEqualTo(NO_TARGET);
            }
        }
    }

    @Test
    void otherJobTypesAreNeverRefusedByTheAiGate() {
        JobSchedulerJobRunner runner = runner(EffectivePolicy.lockdown());
        for (JobActionType type : List.of(JobActionType.COMMAND, JobActionType.SFTP_DELETE, JobActionType.SFTP_MKDIR)) {
            JobAction action = new JobAction();
            action.setType(type);
            assertWithMessage(type.name()).that(runner.aiPolicyRefusal(action)).isEmpty();
        }
    }
}
