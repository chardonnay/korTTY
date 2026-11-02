package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.AiProfileSelectionSupport;
import de.kortty.core.AiPromptExecutionScope;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.FailingAiService;
import de.kortty.memory.MemoryCleanupService;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.policy.PolicyAiProfileSupport;
import de.kortty.policy.PolicyManager;
import de.kortty.security.EncryptionService;

/**
 * The AI call behind the memory cleanup: the default AI profile, the user prompt masked for that profile, and no
 * internet tools. The cleanup calls it from a worker thread, never from the FX thread.
 */
final class AiMemoryAssistant implements MemoryCleanupService.Assistant {

    /** No default AI profile is configured. */
    static final class NoProfileException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NoProfileException() {
            super("No default AI profile is configured.");
        }
    }

    private final KorTTYApplication app;

    AiMemoryAssistant(KorTTYApplication app) {
        this.app = app;
    }

    /** Whether a cleanup call may run now: AI is allowed by the policy and a default profile exists. */
    boolean available() {
        return PolicyManager.effective().aiAllowed() && defaultProfile() != null;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        return complete(defaultProfile(), systemPrompt, userPrompt);
    }

    /**
     * The call with {@code profile}: the prompt masked for it, no memory, no skills and no internet tools. Used with the
     * profile of the chat or run whose message is looked at, see {@link RuleLearning}.
     */
    String complete(AiProfile profile, String systemPrompt, String userPrompt) throws Exception {
        if (profile == null) {
            throw new NoProfileException();
        }
        String masked = AiOutboundRedaction.redactFor(profile, userPrompt, null).text();
        AiProfile copy = new AiProfile(profile);
        if (copy.getInternetAccessMode() == null || !copy.getInternetAccessMode().usesLmStudioMcp()) {
            copy.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        }
        AiService service = AiServiceFactory.create(copy, apiKey(profile), AiInternetAccessConfiguration.disabled(),
            AiSkillPromptSupport.disabled());
        if (service instanceof FailingAiService failing) {
            throw new IllegalStateException(failing.message());
        }
        if (!(service instanceof AiPromptService prompt)) {
            throw new IllegalStateException("The AI profile cannot run prompts.");
        }
        AiExecutionResult result = prompt.executeJsonPrompt(systemPrompt, masked, AiPromptExecutionScope.TEXT);
        return result != null ? result.content() : null;
    }

    /** The default AI profile, or null when none is configured. */
    AiProfile defaultProfile() {
        GlobalSettings settings = settings();
        return settings == null
            ? null
            : AiProfileSelectionSupport.defaultProfile(settings.getAiProfiles(), settings.getDefaultAiProfileId());
    }

    private String apiKey(AiProfile profile) throws Exception {
        String policyKey = PolicyAiProfileSupport.apiKeyOverride(profile);
        if (policyKey != null) {
            return policyKey;
        }
        String encrypted = profile.getEncryptedApiKey();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        char[] master = app != null && app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        if (master == null) {
            throw new IllegalStateException(I18n.get("settings.ai.memory.cleanup.vaultLocked"));
        }
        String decrypted = new EncryptionService().decryptPassword(encrypted, master);
        return decrypted != null && !decrypted.isBlank() ? decrypted : null;
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }
}
