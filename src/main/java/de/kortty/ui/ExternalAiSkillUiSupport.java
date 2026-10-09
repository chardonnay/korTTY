package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ExternalAiSkillException;
import de.kortty.core.ExternalAiSkillSupport;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import de.kortty.security.EncryptionService;
import javafx.stage.Window;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared by the AI Skills tab, the import dialog and the providers dialog: credential decryption with
 * the master password, and translated messages for {@link ExternalAiSkillException}s.
 */
final class ExternalAiSkillUiSupport {

    private ExternalAiSkillUiSupport() {
    }

    /**
     * Decrypts the stored token or password of every provider in {@code providers} that has one, offering
     * to unlock the vault first. Runs on the JavaFX thread, before any network call starts.
     *
     * @return provider id → secret, or {@code null} when the vault stayed locked (the user saw why)
     */
    static Map<String, String> unlockSecrets(Window owner, KorTTYApplication app, Collection<AiSkillProvider> providers) {
        Map<String, String> secrets = new HashMap<>();
        char[] masterPassword = null;
        for (AiSkillProvider provider : providers) {
            if (provider == null || !provider.hasSecret() || provider.getAuth() == AiSkillProviderAuth.NONE) {
                continue;
            }
            if (masterPassword == null) {
                masterPassword = VaultUnlockSupport.masterPasswordOrOfferUnlock(owner,
                    app != null ? app.getMasterPasswordManager() : null,
                    I18n.get("settings.aiSkills.external.vaultLocked"));
                if (masterPassword == null) {
                    return null;
                }
            }
            try {
                secrets.put(provider.getId(), new EncryptionService().decryptPassword(provider.getEncryptedSecret(), masterPassword));
            } catch (Exception e) {
                // A secret encrypted with an older master password: proceed without it, the provider says why.
            }
        }
        return secrets;
    }

    /** The profiles a call through {@code provider} may need credentials of: itself, plus GitHub for SkillsMP. */
    static Set<AiSkillProvider> involvedProviders(AiSkillProvider provider, List<AiSkillProvider> providers) {
        Set<AiSkillProvider> involved = new LinkedHashSet<>();
        involved.add(provider);
        if (provider.getType() == AiSkillProviderType.SKILLSMP) {
            AiSkillProvider github = ExternalAiSkillSupport.githubProviderFor(providers);
            if (github != null) {
                involved.add(github);
            }
        }
        return involved;
    }

    static Function<AiSkillProvider, String> secretLookup(Map<String, String> secrets) {
        return provider -> secrets.get(provider.getId());
    }

    /** A translated sentence for a failed provider call. */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ExternalAiSkillException) && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof ExternalAiSkillException external) {
            return I18n.get("settings.aiSkills.external.error." + external.reason().name().toLowerCase(Locale.ROOT),
                external.detail());
        }
        Throwable shown = cause != null ? cause : failure;
        String message = shown != null && shown.getMessage() != null ? shown.getMessage()
            : shown != null ? shown.getClass().getSimpleName() : "";
        return I18n.get("settings.aiSkills.external.error.network", message);
    }

    static String typeLabel(AiSkillProviderType type) {
        return I18n.get("settings.aiSkills.providers.type." + type.name().toLowerCase(Locale.ROOT));
    }

    static String authLabel(AiSkillProviderAuth auth) {
        return I18n.get("settings.aiSkills.providers.auth." + auth.name().toLowerCase(Locale.ROOT));
    }
}
