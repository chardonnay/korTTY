package de.kortty.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads the enterprise policy once at startup and holds the {@link EffectivePolicy} for the
 * current OS identity. Constructed by {@code KorTTYApplication.init()} <b>before</b> the settings
 * managers so the {@link PolicyClamp} is in place for the very first settings load. There is no
 * hot reload — the file lives in the admin-only install directory, and changes take effect on the
 * next start (documented in the guide chapter).
 */
public final class PolicyManager {

    private static final Logger logger = LoggerFactory.getLogger(PolicyManager.class);

    private static volatile PolicyManager instance;

    private final EffectivePolicy effective;
    private final PolicyLoadResult loadResult;

    private PolicyManager(EffectivePolicy effective, PolicyLoadResult loadResult) {
        this.effective = effective;
        this.loadResult = loadResult;
    }

    /** Locates, loads and resolves the policy for the current OS user; installs the singleton. */
    public static synchronized PolicyManager initialize() {
        Optional<Path> located = PolicyLocator.locate();
        PolicyManager manager;
        if (located.isEmpty()) {
            manager = new PolicyManager(EffectivePolicy.unrestricted(), null);
        } else {
            Path path = located.get();
            if (PolicyLocator.isWritableByCurrentUser(path)) {
                logger.warn("Policy file {} is writable by the current user — the admin-only "
                    + "security model relies on restrictive install-directory permissions", path);
            }
            PolicyLoadResult result = PolicyLoader.load(path);
            result.warnings().forEach(warning -> logger.warn("Policy: {}", warning));
            if (result.isValid()) {
                PolicyIdentity identity = new OsUserIdentity();
                EffectivePolicy resolved = EffectivePolicy.resolve(result.file(), identity);
                logger.info("Enterprise policy active from {} (organization: {}, user: {})",
                    path, resolved.organization().orElse("-"), identity.userName());
                manager = new PolicyManager(resolved, result);
            } else {
                result.errors().forEach(error -> logger.error("Policy: {}", error));
                logger.error("Policy file {} is invalid — applying fail-safe lockdown", path);
                manager = new PolicyManager(EffectivePolicy.lockdown(), result);
            }
        }
        instance = manager;
        return manager;
    }

    /**
     * The effective policy. Falls back to {@link EffectivePolicy#unrestricted()} when the app (or a
     * unit test) never initialized a policy — same trick as
     * {@code HostKeyCheckPolicy.resolveFromSettings}, so feature gates can call this from anywhere.
     */
    public static EffectivePolicy effective() {
        PolicyManager manager = instance;
        return manager != null ? manager.effective : EffectivePolicy.unrestricted();
    }

    /** The load result of the active policy file, or empty when none is installed. */
    public Optional<PolicyLoadResult> loadResult() {
        return Optional.ofNullable(loadResult);
    }

    /** True when a policy file exists but was rejected (lockdown is active). */
    public boolean hasLoadFailure() {
        return loadResult != null && !loadResult.isValid();
    }

    public EffectivePolicy getEffective() {
        return effective;
    }

    /** Test hook: clears the singleton so later tests see the unrestricted fallback. */
    static synchronized void resetForTests() {
        instance = null;
    }
}
