package de.kortty.jobscheduler;

import de.kortty.security.EncryptionService;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

class WebhookTargetEncryptionTest {

    private static final EncryptionService ENC = new EncryptionService();
    private static final char[] MASTER = "webhook-master-pass".toCharArray();
    private static final String SECRET_PATH = "/services/T0SECRET/B0SECRET/xoxSecretToken42";
    private static final String URL = "https://hooks.slack.com" + SECRET_PATH;

    @Test
    void savedXmlHoldsNoPlaintextUrlAndDecryptsAfterReload() throws Exception {
        Path dir = Files.createTempDirectory("kortty-webhook-enc");
        try {
            JobSchedulerRepository repo = new JobSchedulerRepository(dir);
            WebhookTargetSecrets secrets = new WebhookTargetSecrets(ENC);
            WebhookTarget target = new WebhookTarget();
            target.setName("Ops channel");
            target.setFormat(WebhookFormat.SLACK);
            secrets.storeUrl(target, URL, MASTER);
            repo.upsertWebhookTarget(target);
            repo.save();

            String xml = Files.readString(dir.resolve(JobSchedulerRepository.FILE_NAME), StandardCharsets.UTF_8);
            assertThat(xml).contains("<webhookTarget>");
            assertThat(xml).contains("<encryptedUrl>");
            assertThat(xml).doesNotContain("hooks.slack.com");
            assertThat(xml).doesNotContain("T0SECRET");
            assertThat(xml).doesNotContain("xoxSecretToken42");
            assertThat(xml).contains("<includeSummary>false</includeSummary>");

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();
            WebhookTarget loaded = reloaded.findWebhookTarget(target.getId()).orElseThrow();
            assertThat(loaded.getName()).isEqualTo("Ops channel");
            assertThat(loaded.getFormat()).isEqualTo(WebhookFormat.SLACK);
            assertThat(loaded.isEnabled()).isTrue();
            assertThat(loaded.isIncludeSummary()).isFalse();
            WebhookTargetSecrets.Resolution resolution = secrets.resolve(loaded, MASTER);
            assertThat(resolution.resolved()).isTrue();
            assertThat(resolution.uri().toString()).isEqualTo(URL);
            assertThat(loaded.toString()).doesNotContain(SECRET_PATH);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void lockedMasterPasswordSkipsWithJournalText() throws Exception {
        WebhookTargetSecrets secrets = new WebhookTargetSecrets(ENC);
        WebhookTarget target = new WebhookTarget();
        secrets.storeUrl(target, URL, MASTER);

        WebhookTargetSecrets.Resolution locked = secrets.resolve(target, null);
        assertThat(locked.resolved()).isFalse();
        assertThat(locked.skipReason()).isEqualTo(WebhookTargetSecrets.SkipReason.LOCKED);
        assertThat(locked.journalText()).isEqualTo("notification skipped: master password locked");

        assertThat(secrets.resolve(target, "wrong-password".toCharArray()).skipReason())
            .isEqualTo(WebhookTargetSecrets.SkipReason.UNREADABLE);
        assertThat(secrets.resolve(new WebhookTarget(), MASTER).skipReason())
            .isEqualTo(WebhookTargetSecrets.SkipReason.NO_URL);
    }

    @Test
    void storedUrlIsRevalidatedOnResolve() throws Exception {
        WebhookTarget target = new WebhookTarget();
        target.setEncryptedUrl(ENC.encryptPassword("javascript:alert(1)", MASTER));
        assertThat(new WebhookTargetSecrets(ENC).resolve(target, MASTER).skipReason())
            .isEqualTo(WebhookTargetSecrets.SkipReason.INVALID);
    }

    @Test
    void storeRejectsInvalidUrlsAndALockedVaultWithoutLeakingTheUrl() {
        WebhookTargetSecrets secrets = new WebhookTargetSecrets(ENC);
        WebhookTarget target = new WebhookTarget();
        IllegalArgumentException invalid = expectThrows(IllegalArgumentException.class,
            () -> secrets.storeUrl(target, "http://hooks.example.org/secret-path", MASTER));
        assertThat(invalid.getMessage()).isEqualTo(WebhookUrlValidator.Problem.INSECURE_HTTP.i18nKey());
        assertThat(invalid.getMessage()).doesNotContain("secret-path");
        assertThrows(IllegalStateException.class, () -> secrets.storeUrl(target, URL, null));
        assertThat(target.hasUrl()).isFalse();
    }

    @Test
    void deletingATargetDropsItFromJobs() {
        JobSchedulerRepository repo = new JobSchedulerRepository(Path.of("unused"));
        WebhookTarget target = new WebhookTarget();
        repo.upsertWebhookTarget(target);
        ScheduledJob job = new ScheduledJob();
        JobNotificationConfig config = JobNotificationConfig.defaults();
        config.setWebhookTargetIds(List.of(target.getId(), "other"));
        job.setNotificationConfig(config);
        repo.upsertJob(job);

        assertThat(repo.deleteWebhookTarget(target.getId())).isTrue();
        assertThat(repo.getWebhookTargets()).isEmpty();
        assertThat(repo.getJobs().get(0).getNotificationConfig().getWebhookTargetIds()).containsExactly("other");
        assertThat(repo.deleteWebhookTarget(target.getId())).isFalse();
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
