package de.kortty.policy;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Maps admin-provided policy definitions onto runtime model objects and resolves the API key of a
 * policy-managed profile. The key stays in its {@code kortty-enc:v1:} envelope on the profile and
 * is decrypted only here, at the moment of use.
 */
public final class PolicyAiProfileSupport {

    private static final Logger logger = LoggerFactory.getLogger(PolicyAiProfileSupport.class);

    /** Default endpoint per provider when the policy names none. */
    private static final String ANTHROPIC_DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";

    private PolicyAiProfileSupport() {
    }

    /** Builds the runtime {@link AiProfile} for one {@code [[ai-profile]]} definition. */
    public static AiProfile toAiProfile(PolicyFile.AiProfileDef def) {
        AiProfile profile = new AiProfile();
        profile.setId(def.id());
        profile.setName(def.name());
        profile.setPolicyManaged(true);
        profile.setPolicyEncryptedApiKey(def.apiKeyEncrypted());
        switch (def.provider().toLowerCase(Locale.ROOT)) {
            case "embedded-llama" -> {
                profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
                profile.setEmbeddedModelId(def.model());
            }
            case "embedded-mlx" -> {
                profile.setConnectionMode(AiConnectionMode.EMBEDDED_MLX);
                profile.setEmbeddedModelId(def.model());
            }
            case "anthropic" -> {
                profile.setConnectionMode(AiConnectionMode.HTTP_API);
                profile.setApiUrl(def.endpoint() != null ? def.endpoint() : ANTHROPIC_DEFAULT_ENDPOINT);
                profile.setModel(def.model());
            }
            default -> {
                // "openai-compatible" and "lm-studio": dispatched by URL in AiServiceFactory.
                profile.setConnectionMode(AiConnectionMode.HTTP_API);
                profile.setApiUrl(def.endpoint());
                profile.setModel(def.model());
            }
        }
        return profile;
    }

    /**
     * The plaintext API key for a policy-managed profile, or null when the profile is not
     * policy-managed or carries no admin key. Call sites consult this <b>before</b> falling back to
     * the user's own master-password-encrypted key.
     */
    public static String apiKeyOverride(AiProfile profile) {
        if (profile == null || !profile.isPolicyManaged()) {
            return null;
        }
        String envelope = profile.getPolicyEncryptedApiKey();
        if (envelope == null || envelope.isBlank()) {
            return null;
        }
        try {
            return PolicyValueCipher.decrypt(envelope);
        } catch (IllegalArgumentException e) {
            logger.warn("Could not decrypt the policy-provided API key for profile {}: {}",
                profile.getId(), e.getMessage());
            return null;
        }
    }

    /** Builds the runtime {@link TeamworkSourceConfig} for one {@code [[teamwork-source]]} definition. */
    public static TeamworkSourceConfig toTeamworkSource(PolicyFile.TeamworkSourceDef def, int index) {
        TeamworkSourceConfig config = new TeamworkSourceConfig();
        config.setId("policy-teamwork-" + index);
        config.setType("shared-file".equalsIgnoreCase(def.type())
            ? TeamworkSourceType.SHARED_FILE
            : TeamworkSourceType.GIT);
        config.setLocation(def.url());
        config.setReadOnly(true);
        config.setEnabled(true);
        config.setPolicyManaged(true);
        return config;
    }
}
