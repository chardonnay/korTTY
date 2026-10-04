package de.kortty.jobscheduler;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.FileTransferGate;
import de.kortty.policy.PolicyDecision;
import de.kortty.policy.PolicyFeature;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
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
 * The JobScheduler leg of the {@code file-transfer} policy (D6): SFTP upload, download and sync
 * jobs and rsync jobs fail with the policy message before anything is resolved or connected, and remote-only SFTP
 * actions are not affected.
 */
class JobSchedulerFileTransferPolicyTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-job-ftpolicy");
    }

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static EffectivePolicy policy(PolicyDecision fileTransfer) {
        PolicyRule rule = PolicyRule.builder()
            .features(Map.of(PolicyFeature.FILE_TRANSFER, fileTransfer)).build();
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
        // No app: a job that got past the policy gate would fail while resolving its targets instead.
        return new JobSchedulerJobRunner(null, new JobSchedulerRepository(dir), new JobSchedulerRsyncSupport(null),
            () -> policy);
    }

    private ScheduledJob job(JobActionType type, Path local) {
        JobAction action = new JobAction();
        action.setType(type);
        action.setLocalPath(local.toString());
        action.setRemotePath("/srv/data/report.csv");
        ScheduledJob job = new ScheduledJob();
        job.setName("transfer");
        job.setAction(action);
        return job;
    }

    @Test
    void eachSftpTransferJobFailsWithThePolicyErrorAndTransfersNothing() {
        JobSchedulerJobRunner runner = runner(policy(PolicyDecision.DENY));
        String expected = FileTransferGate.check(policy(PolicyDecision.DENY),
            FileTransferGate.Route.JOB_SFTP_UPLOAD).reason();
        for (JobActionType type : List.of(JobActionType.SFTP_UPLOAD, JobActionType.SFTP_DOWNLOAD,
                JobActionType.SFTP_SYNC, JobActionType.RSYNC_SYNC)) {
            Path local = dir.resolve(type.name().toLowerCase() + ".csv");

            JobExecutionOutcome outcome = runner.run(job(type, local), "run-1");

            assertWithMessage(type.name()).that(outcome.status()).isEqualTo(JobRunStatus.FAILED);
            assertWithMessage(type.name() + ": refused by the gate, not by a later failure")
                .that(outcome.summary()).isEqualTo(expected);
            assertWithMessage(type.name()).that(outcome.stderr()).isEqualTo(expected);
            assertWithMessage(type.name() + " must not write anything locally")
                .that(Files.exists(local)).isFalse();
        }
    }

    @Test
    void remoteOnlySftpActionsAndOtherJobsAreNotRefused() {
        JobSchedulerJobRunner runner = runner(policy(PolicyDecision.DENY));
        for (JobActionType type : List.of(JobActionType.SFTP_DELETE, JobActionType.SFTP_RENAME,
                JobActionType.SFTP_MKDIR, JobActionType.SFTP_CHMOD, JobActionType.SFTP_CHOWN,
                JobActionType.SFTP_COPY_REMOTE, JobActionType.SFTP_ARCHIVE, JobActionType.COMMAND)) {
            JobAction action = new JobAction();
            action.setType(type);
            assertWithMessage(type.name()).that(runner.fileTransferRefusal(action)).isEmpty();
        }
    }

    @Test
    void anAllowingPolicyOrNoPolicyLetsTransferJobsThrough() {
        JobAction upload = new JobAction();
        upload.setType(JobActionType.SFTP_UPLOAD);
        assertThat(runner(policy(PolicyDecision.ALLOW)).fileTransferRefusal(upload)).isEmpty();
        assertThat(runner(EffectivePolicy.unrestricted()).fileTransferRefusal(upload)).isEmpty();
        assertThat(runner(EffectivePolicy.lockdown()).fileTransferRefusal(upload)).isPresent();
    }
}
