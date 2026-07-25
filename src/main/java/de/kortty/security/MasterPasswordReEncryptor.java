package de.kortty.security;

import de.kortty.ai.huggingface.HuggingFaceTokenStore;
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
import de.kortty.rag.RagSecretSupport;
import de.kortty.rag.RagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Re-encrypts every master-password-derived secret from an old master password to a new one when
 * the user changes their master password. Each secret is decrypted with the old password and
 * re-encrypted with the new one, so nothing silently becomes unreadable after the change.
 *
 * <p>Callers MUST pass the explicit old and new passwords, not the manager's live password:
 * {@link MasterPasswordManager#changePassword} has already swapped the in-memory password to the
 * new one by the time re-encryption runs.
 *
 * <p>Failures are isolated per secret — an undecryptable value is logged and left untouched (never
 * overwritten with garbage), so one stale or foreign entry cannot abort the whole migration.
 *
 * <p>The in-memory model stores (connections, SSH keys, credentials, global settings) are mutated
 * in place; the caller persists their managers afterwards. The file/JSON-backed stores (Hugging
 * Face token file, RAG {@code stores.json}, {@code job-scheduler.xml}) are re-encrypted AND
 * persisted by the method that handles them.
 */
public final class MasterPasswordReEncryptor {

    private static final Logger logger = LoggerFactory.getLogger(MasterPasswordReEncryptor.class);

    private final EncryptionService enc;
    private final char[] oldPassword;
    private final char[] newPassword;
    private int reEncrypted;
    private int failures;

    public MasterPasswordReEncryptor(EncryptionService enc, char[] oldPassword, char[] newPassword) {
        this.enc = Objects.requireNonNull(enc, "enc");
        this.oldPassword = oldPassword;
        this.newPassword = newPassword;
    }

    /** Number of secrets successfully re-encrypted so far. */
    public int reEncryptedCount() {
        return reEncrypted;
    }

    /** Number of secrets that could not be re-encrypted (left unchanged). */
    public int failureCount() {
        return failures;
    }

    // --- shared helpers ------------------------------------------------------------------------

    /**
     * Re-encrypts one {@code salt:iv+ct} value old→new, or returns it unchanged when it is blank or
     * a policy-managed value (encrypted with the organization key, not the master password).
     * Throws if the value cannot be decrypted with the old password.
     */
    private String reEncryptValue(String stored) throws Exception {
        if (stored == null || stored.isBlank() || PolicyValueCipher.isEncryptedValue(stored)) {
            return stored;
        }
        String plain = enc.decryptPassword(stored, oldPassword);
        String result = enc.encryptPassword(plain, newPassword);
        reEncrypted++;
        return result;
    }

    /** Re-encrypts one field via its getter/setter, isolating and logging any failure. */
    private void field(String label, Supplier<String> getter, Consumer<String> setter) {
        String current = getter.get();
        if (current == null || current.isBlank()) {
            return;
        }
        try {
            String updated = reEncryptValue(current);
            if (!Objects.equals(updated, current)) {
                setter.accept(updated);
            }
        } catch (Exception e) {
            failures++;
            logger.warn("Could not re-encrypt secret [{}] — leaving it unchanged", label);
        }
    }

    // --- in-memory model stores (caller persists the managers) ---------------------------------

    /** Connection password, private-key passphrase, and jump-server password (in connections.xml). */
    public void reEncryptConnections(List<ServerConnection> connections) {
        if (connections == null) {
            return;
        }
        for (ServerConnection c : connections) {
            field("connection.password", c::getEncryptedPassword, c::setEncryptedPassword);
            field("connection.keyPassphrase", c::getPrivateKeyPassphrase, c::setPrivateKeyPassphrase);
            JumpServer jump = c.getJumpServer();
            if (jump != null) {
                field("jumpServer.password", jump::getEncryptedPassword, jump::setEncryptedPassword);
            }
        }
    }

    /** SSH key passphrases (in ssh-keys.xml). */
    public void reEncryptSshKeys(List<SSHKey> keys) {
        if (keys == null) {
            return;
        }
        for (SSHKey k : keys) {
            field("sshKey.passphrase", k::getEncryptedPassphrase, k::setEncryptedPassphrase);
        }
    }

    /** Stored credentials and external password commands (in credentials.xml). */
    public void reEncryptCredentials(List<StoredCredential> credentials) {
        if (credentials == null) {
            return;
        }
        for (StoredCredential cred : credentials) {
            field("credential.password", cred::getEncryptedPassword, cred::setEncryptedPassword);
            field("credential.externalCommand",
                cred::getEncryptedExternalCommand, cred::setEncryptedExternalCommand);
        }
    }

    /** AI-profile API keys and the global AI / translation / Hugging Face secrets (in global-settings.xml). */
    public void reEncryptGlobalSettings(GlobalSettings gs) {
        if (gs == null) {
            return;
        }
        if (gs.getAiProfiles() != null) {
            for (AiProfile p : gs.getAiProfiles()) {
                // Policy-managed profiles keep their org key in a separate transient field; the
                // master-password-encrypted encryptedApiKey is what we migrate here.
                field("aiProfile.apiKey", p::getEncryptedApiKey, p::setEncryptedApiKey);
            }
        }
        field("gs.aiApiKey", gs::getEncryptedAiApiKey, gs::setEncryptedAiApiKey);
        field("gs.translationApiKey", gs::getEncryptedTranslationApiKey, gs::setEncryptedTranslationApiKey);
        field("gs.huggingFaceToken", gs::getEncryptedHuggingFaceToken, gs::setEncryptedHuggingFaceToken);
        field("gs.tavilyApiKey", gs::getEncryptedAiTavilyApiKey, gs::setEncryptedAiTavilyApiKey);
        field("gs.brightDataToken", gs::getEncryptedAiBrightDataApiToken, gs::setEncryptedAiBrightDataApiToken);
        field("gs.braveSearchApiKey", gs::getEncryptedAiBraveSearchApiKey, gs::setEncryptedAiBraveSearchApiKey);
    }

    // --- self-persisting file / JSON stores ----------------------------------------------------

    /** The file-backed Hugging Face token, if one is stored (defensive — usually unset). */
    public void reEncryptHuggingFaceTokenStore(HuggingFaceTokenStore store) {
        if (store == null || !store.isConfigured()) {
            return;
        }
        try {
            Optional<String> token = store.load(oldPassword);
            if (token.isPresent()) {
                store.store(token.get(), newPassword);
                reEncrypted++;
            }
        } catch (Exception e) {
            failures++;
            logger.warn("Could not re-encrypt the Hugging Face token file — leaving it unchanged");
        }
    }

    /** RAG provider secrets (Qdrant API keys, {@code vault:v1:} envelope) in stores.json. */
    public void reEncryptRagStores(RagConfigurationManager rag) {
        if (rag == null) {
            return;
        }
        for (RagStore store : rag.listStores()) {
            if (!RagSecretSupport.isProtected(store.apiKey())) {
                continue;
            }
            try {
                String updated = RagSecretSupport.reEncrypt(store.apiKey(), oldPassword, newPassword);
                rag.update(store.withApiKey(updated));
                reEncrypted++;
            } catch (Exception e) {
                failures++;
                logger.warn("Could not re-encrypt RAG store secret [{}] — leaving it unchanged", store.id());
            }
        }
    }

    /** Job Scheduler sudo passwords and password-protected-archive passwords in job-scheduler.xml. */
    public void reEncryptJobScheduler(JobSchedulerRepository repo) {
        if (repo == null) {
            return;
        }
        boolean changed = false;
        for (SudoCredential cred : repo.getSudoCredentials()) {
            String current = cred.getEncryptedPassword();
            if (current == null || current.isBlank()) {
                continue;
            }
            try {
                cred.setEncryptedPassword(reEncryptValue(current));
                repo.upsertSudoCredential(cred);
                changed = true;
            } catch (Exception e) {
                failures++;
                logger.warn("Could not re-encrypt sudo credential [{}] — leaving it unchanged", cred.getId());
            }
        }
        for (ScheduledJob job : repo.getJobs()) {
            if (job.getAction() == null) {
                continue;
            }
            String current = job.getAction().getEncryptedArchivePassword();
            if (current == null || current.isBlank()) {
                continue;
            }
            try {
                job.getAction().setEncryptedArchivePassword(reEncryptValue(current));
                repo.upsertJob(job);
                changed = true;
            } catch (Exception e) {
                failures++;
                logger.warn("Could not re-encrypt archive password for job [{}] — leaving it unchanged",
                    job.getId());
            }
        }
        if (changed) {
            try {
                repo.save();
            } catch (Exception e) {
                failures++;
                logger.warn("Failed to persist re-encrypted Job Scheduler secrets", e);
            }
        }
    }
}
