package de.kortty.security;

import de.kortty.ai.huggingface.HuggingFaceTokenStore;
import de.kortty.jobscheduler.JobAction;
import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.jobscheduler.SudoCredential;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.JumpServer;
import de.kortty.model.SSHKey;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.policy.PolicyValueCipher;
import de.kortty.rag.RagConfigurationManager;
import de.kortty.rag.RagStore;
import de.kortty.rag.RagStoreType;
import org.testng.annotations.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Verifies that changing the master password re-encrypts EVERY master-password-derived secret, not
 * just connection/SSH secrets — the gap this class was written to close. Each secret is set up
 * encrypted with an old password, migrated, and asserted to decrypt with the new password.
 */
class MasterPasswordReEncryptorTest {

    private static final EncryptionService ENC = new EncryptionService();
    private static final char[] OLD = "old-master-pass".toCharArray();
    private static final char[] NEW = "new-master-pass-2".toCharArray();
    private static final String RAG_PREFIX = "vault:v1:";

    private static String encOld(String plain) throws Exception {
        return ENC.encryptPassword(plain, OLD);
    }

    private static String decNew(String value) throws Exception {
        return ENC.decryptPassword(value, NEW);
    }

    private static MasterPasswordReEncryptor rex() {
        return new MasterPasswordReEncryptor(ENC, OLD, NEW);
    }

    @Test
    void reEncryptsConnectionSecretsIncludingJumpServer() throws Exception {
        ServerConnection c = new ServerConnection();
        c.setEncryptedPassword(encOld("conn-pw"));
        c.setPrivateKeyPassphrase(encOld("key-pass"));
        JumpServer jump = new JumpServer();
        jump.setEncryptedPassword(encOld("jump-pw"));
        c.setJumpServer(jump);

        MasterPasswordReEncryptor r = rex();
        r.reEncryptConnections(List.of(c));

        assertThat(decNew(c.getEncryptedPassword())).isEqualTo("conn-pw");
        assertThat(decNew(c.getPrivateKeyPassphrase())).isEqualTo("key-pass");
        // The jump-server password was previously NOT re-encrypted — the regression this guards.
        assertThat(decNew(c.getJumpServer().getEncryptedPassword())).isEqualTo("jump-pw");
        assertThat(r.reEncryptedCount()).isEqualTo(3);
        assertThat(r.failureCount()).isEqualTo(0);
    }

    @Test
    void reEncryptsGlobalSettingsAiSecrets() throws Exception {
        GlobalSettings gs = new GlobalSettings();
        AiProfile profile = new AiProfile();
        profile.setId("p1");
        profile.setEncryptedApiKey(encOld("sk-profile"));
        gs.setAiProfiles(new ArrayList<>(List.of(profile)));
        gs.setEncryptedAiApiKey(encOld("legacy-ai"));
        gs.setEncryptedTranslationApiKey(encOld("translate"));
        gs.setEncryptedHuggingFaceToken(encOld("hf"));
        gs.setEncryptedAiTavilyApiKey(encOld("tavily"));
        gs.setEncryptedAiBrightDataApiToken(encOld("bright"));
        gs.setEncryptedAiBraveSearchApiKey(encOld("brave"));

        MasterPasswordReEncryptor r = rex();
        r.reEncryptGlobalSettings(gs);

        assertThat(decNew(gs.getAiProfiles().get(0).getEncryptedApiKey())).isEqualTo("sk-profile");
        assertThat(decNew(gs.getEncryptedAiApiKey())).isEqualTo("legacy-ai");
        assertThat(decNew(gs.getEncryptedTranslationApiKey())).isEqualTo("translate");
        assertThat(decNew(gs.getEncryptedHuggingFaceToken())).isEqualTo("hf");
        assertThat(decNew(gs.getEncryptedAiTavilyApiKey())).isEqualTo("tavily");
        assertThat(decNew(gs.getEncryptedAiBrightDataApiToken())).isEqualTo("bright");
        assertThat(decNew(gs.getEncryptedAiBraveSearchApiKey())).isEqualTo("brave");
        assertThat(r.reEncryptedCount()).isEqualTo(7);
        assertThat(r.failureCount()).isEqualTo(0);
    }

    @Test
    void reEncryptsCredentials() throws Exception {
        StoredCredential cred = new StoredCredential();
        cred.setEncryptedPassword(encOld("cred-pw"));
        cred.setEncryptedExternalCommand(encOld("secret-cmd"));

        MasterPasswordReEncryptor r = rex();
        r.reEncryptCredentials(List.of(cred));

        assertThat(decNew(cred.getEncryptedPassword())).isEqualTo("cred-pw");
        assertThat(decNew(cred.getEncryptedExternalCommand())).isEqualTo("secret-cmd");
        assertThat(r.reEncryptedCount()).isEqualTo(2);
    }

    @Test
    void reEncryptsSshKeyPassphrases() throws Exception {
        SSHKey key = new SSHKey();
        key.setEncryptedPassphrase(encOld("ssh-pass"));

        MasterPasswordReEncryptor r = rex();
        r.reEncryptSshKeys(List.of(key));

        assertThat(decNew(key.getEncryptedPassphrase())).isEqualTo("ssh-pass");
        assertThat(r.reEncryptedCount()).isEqualTo(1);
    }

    @Test
    void reEncryptsHuggingFaceTokenFile() throws Exception {
        Path dir = Files.createTempDirectory("kortty-rex-hf");
        try {
            HuggingFaceTokenStore store = new HuggingFaceTokenStore(dir);
            store.store("hf-token", OLD);

            MasterPasswordReEncryptor r = rex();
            r.reEncryptHuggingFaceTokenStore(store);

            assertThat(store.load(NEW).orElse(null)).isEqualTo("hf-token");
            assertThat(r.reEncryptedCount()).isEqualTo(1);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void reEncryptsRagStoreApiKeys() throws Exception {
        Path dir = Files.createTempDirectory("kortty-rex-rag");
        try {
            RagConfigurationManager mgr = new RagConfigurationManager(dir.resolve("stores.json"));
            String vaultValue = RAG_PREFIX + encOld("qdrant-key");
            RagStore store = new RagStore(null, "Q", RagStoreType.QDRANT, null,
                URI.create("http://localhost:6333"), "coll", vaultValue);
            mgr.create(store);

            MasterPasswordReEncryptor r = rex();
            r.reEncryptRagStores(mgr);

            String updated = mgr.listStores().get(0).apiKey();
            assertThat(updated).startsWith(RAG_PREFIX);
            assertThat(decNew(updated.substring(RAG_PREFIX.length()))).isEqualTo("qdrant-key");
            assertThat(r.reEncryptedCount()).isEqualTo(1);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void reEncryptsJobSchedulerSecrets() throws Exception {
        Path dir = Files.createTempDirectory("kortty-rex-js");
        try {
            JobSchedulerRepository repo = new JobSchedulerRepository(dir);
            SudoCredential sudo = new SudoCredential();
            sudo.setId("sudo-1");
            sudo.setEncryptedPassword(encOld("sudo-pw"));
            repo.upsertSudoCredential(sudo);

            ScheduledJob job = new ScheduledJob();
            JobAction action = new JobAction();
            action.setEncryptedArchivePassword(encOld("archive-pw"));
            job.setAction(action);
            repo.upsertJob(job);

            MasterPasswordReEncryptor r = rex();
            r.reEncryptJobScheduler(repo);

            assertThat(decNew(repo.getSudoCredentials().get(0).getEncryptedPassword())).isEqualTo("sudo-pw");
            assertThat(decNew(repo.getJobs().get(0).getAction().getEncryptedArchivePassword()))
                .isEqualTo("archive-pw");
            assertThat(r.reEncryptedCount()).isEqualTo(2);
            assertThat(r.failureCount()).isEqualTo(0);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void leavesUndecryptableSecretUnchangedAndCountsFailure() throws Exception {
        // A secret encrypted with a THIRD password (not OLD) must be left intact, never clobbered.
        String foreign = ENC.encryptPassword("foreign", "different-pass".toCharArray());
        ServerConnection c = new ServerConnection();
        c.setEncryptedPassword(foreign);

        MasterPasswordReEncryptor r = rex();
        r.reEncryptConnections(List.of(c));

        assertThat(c.getEncryptedPassword()).isEqualTo(foreign);
        assertThat(r.reEncryptedCount()).isEqualTo(0);
        assertThat(r.failureCount()).isEqualTo(1);
    }

    @Test
    void skipsBlankAndPolicyManagedValues() throws Exception {
        GlobalSettings gs = new GlobalSettings();
        gs.setEncryptedAiApiKey("");                                  // blank → skipped
        String policyValue = PolicyValueCipher.encrypt("org-key");   // org key, not master password
        gs.setEncryptedAiTavilyApiKey(policyValue);

        MasterPasswordReEncryptor r = rex();
        r.reEncryptGlobalSettings(gs);

        // The policy-managed value must be left exactly as-is (never master-decrypted).
        assertThat(gs.getEncryptedAiTavilyApiKey()).isEqualTo(policyValue);
        assertThat(r.reEncryptedCount()).isEqualTo(0);
        assertThat(r.failureCount()).isEqualTo(0);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
