package de.kortty.security;

import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.jobscheduler.WebhookTarget;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class MasterPasswordReEncryptorWebhookTest {

    private static final EncryptionService ENC = new EncryptionService();
    private static final char[] OLD = "old-master-pass".toCharArray();
    private static final char[] NEW = "new-master-pass-2".toCharArray();
    private static final String URL = "https://example.org/hooks/secret-token";

    @Test
    void webhookUrlsAreReEncryptedAndPersistedOnPasswordChange() throws Exception {
        Path dir = Files.createTempDirectory("kortty-rex-webhook");
        try {
            JobSchedulerRepository repo = new JobSchedulerRepository(dir);
            WebhookTarget target = new WebhookTarget();
            target.setEncryptedUrl(ENC.encryptPassword(URL, OLD));
            repo.upsertWebhookTarget(target);
            WebhookTarget empty = new WebhookTarget();
            repo.upsertWebhookTarget(empty);

            MasterPasswordReEncryptor r = new MasterPasswordReEncryptor(ENC, OLD, NEW);
            r.reEncryptJobScheduler(repo);

            String updated = repo.findWebhookTarget(target.getId()).orElseThrow().getEncryptedUrl();
            assertThat(ENC.decryptPassword(updated, NEW)).isEqualTo(URL);
            assertThat(repo.findWebhookTarget(empty.getId()).orElseThrow().hasUrl()).isFalse();
            assertThat(r.reEncryptedCount()).isEqualTo(1);
            assertThat(r.failureCount()).isEqualTo(0);

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();
            assertThat(ENC.decryptPassword(
                reloaded.findWebhookTarget(target.getId()).orElseThrow().getEncryptedUrl(), NEW)).isEqualTo(URL);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void undecryptableUrlIsLeftUnchangedAndCounted() throws Exception {
        Path dir = Files.createTempDirectory("kortty-rex-webhook-bad");
        try {
            JobSchedulerRepository repo = new JobSchedulerRepository(dir);
            WebhookTarget target = new WebhookTarget();
            String foreign = ENC.encryptPassword(URL, "some-other-pass".toCharArray());
            target.setEncryptedUrl(foreign);
            repo.upsertWebhookTarget(target);

            MasterPasswordReEncryptor r = new MasterPasswordReEncryptor(ENC, OLD, NEW);
            r.reEncryptJobScheduler(repo);

            assertThat(repo.findWebhookTarget(target.getId()).orElseThrow().getEncryptedUrl()).isEqualTo(foreign);
            assertThat(r.failureCount()).isEqualTo(1);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
