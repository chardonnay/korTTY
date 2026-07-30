package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;

import java.time.Duration;

/**
 * Resolves how long a single AI request may run.
 *
 * <p>korTTY never imposes a request timeout of its own: a full code analysis over a large snippet
 * legitimately runs for many minutes, and cutting it off discards work the user is still waiting
 * for. A timeout therefore exists only when the user asked for one in the AI Manager — either the
 * global value in minutes, or a per-profile override that wins over it.
 */
public final class AiRequestTimeoutSupport {

    /** Upper bound offered by the AI Manager spinners (24 hours). */
    public static final int MAX_TIMEOUT_MINUTES = 1440;

    private AiRequestTimeoutSupport() {
    }

    /**
     * Resolves the effective timeout for {@code profile} against the live global settings.
     *
     * @return the timeout, or {@code null} when the request must run without a timeout
     */
    public static Duration resolve(AiProfile profile) {
        return resolve(profile, globalTimeoutMinutes());
    }

    /**
     * Resolution used by {@link #resolve(AiProfile)}, with the global value passed in so it can be
     * exercised without an application instance.
     */
    static Duration resolve(AiProfile profile, int globalTimeoutMinutes) {
        Integer profileMinutes = profile != null ? profile.getRequestTimeoutMinutes() : null;
        int minutes = profileMinutes != null ? profileMinutes : Math.max(0, globalTimeoutMinutes);
        return minutes > 0 ? Duration.ofMinutes(minutes) : null;
    }

    /** @return the global timeout in minutes, or 0 when unset or unavailable (no timeout). */
    static int globalTimeoutMinutes() {
        try {
            de.kortty.KorTTYApplication application = de.kortty.KorTTYApplication.getInstance();
            GlobalSettingsManager manager = application != null ? application.getGlobalSettingsManager() : null;
            GlobalSettings settings = manager != null ? manager.getSettings() : null;
            return settings != null ? settings.getAiRequestTimeoutMinutes() : 0;
        } catch (Exception error) {
            // Headless harnesses and early startup have no settings yet. Falling back to "no
            // timeout" keeps the documented default instead of inventing a limit.
            return 0;
        }
    }
}
