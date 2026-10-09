package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.UUID;

/**
 * A profile for an external AI-skill provider (AI Manager → AI Skills → Providers…): which service
 * external skills are searched on and imported from, and the credentials korTTY sends to it.
 *
 * <p>The token or password is kept encrypted with the master password ({@link #getEncryptedSecret()});
 * the user name is not a secret and stays readable.
 */
@XmlRootElement(name = "aiSkillProvider")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiSkillProvider {

    /** Stable id of the GitHub provider korTTY creates on first use. */
    public static final String DEFAULT_GITHUB_ID = "github";
    /** Stable id of the SkillsMP provider korTTY creates on first use. */
    public static final String DEFAULT_SKILLSMP_ID = "skillsmp";

    @XmlElement
    private String id;

    @XmlElement
    private String name;

    @XmlElement
    private AiSkillProviderType type = AiSkillProviderType.GITHUB;

    @XmlElement
    private String baseUrl;

    @XmlElement
    private AiSkillProviderAuth auth = AiSkillProviderAuth.NONE;

    @XmlElement
    private String username;

    /** Token (TOKEN) or password (BASIC), encrypted with the master password. */
    @XmlElement
    private String encryptedSecret;

    @XmlElement
    private boolean enabled = true;

    public AiSkillProvider() {
        this.id = UUID.randomUUID().toString();
    }

    public AiSkillProvider(AiSkillProvider source) {
        this();
        if (source == null) {
            return;
        }
        setId(source.id);
        this.name = source.name;
        setType(source.type);
        this.baseUrl = source.baseUrl;
        setAuth(source.auth);
        this.username = source.username;
        this.encryptedSecret = source.encryptedSecret;
        this.enabled = source.enabled;
    }

    /** The GitHub and SkillsMP providers a library without any provider starts with. */
    public static java.util.List<AiSkillProvider> defaults() {
        AiSkillProvider github = new AiSkillProvider();
        github.setId(DEFAULT_GITHUB_ID);
        github.setName("GitHub");
        github.setType(AiSkillProviderType.GITHUB);
        github.setBaseUrl(AiSkillProviderType.GITHUB.defaultBaseUrl());
        AiSkillProvider skillsMp = new AiSkillProvider();
        skillsMp.setId(DEFAULT_SKILLSMP_ID);
        skillsMp.setName("SkillsMP");
        skillsMp.setType(AiSkillProviderType.SKILLSMP);
        skillsMp.setBaseUrl(AiSkillProviderType.SKILLSMP.defaultBaseUrl());
        return java.util.List.of(github, skillsMp);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** {@link #getName()}, or the type when the profile has no name. */
    public String displayName() {
        return name != null && !name.isBlank() ? name.trim() : getType().name();
    }

    public AiSkillProviderType getType() {
        return type != null ? type : AiSkillProviderType.GITHUB;
    }

    public void setType(AiSkillProviderType type) {
        this.type = type != null ? type : AiSkillProviderType.GITHUB;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : null;
    }

    /** {@link #getBaseUrl()}, or the type's default; never ends with a slash. */
    public String effectiveBaseUrl() {
        String url = baseUrl != null ? baseUrl : getType().defaultBaseUrl();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public AiSkillProviderAuth getAuth() {
        return auth != null ? auth : AiSkillProviderAuth.NONE;
    }

    public void setAuth(AiSkillProviderAuth auth) {
        this.auth = auth != null ? auth : AiSkillProviderAuth.NONE;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username != null && !username.isBlank() ? username.trim() : null;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public void setEncryptedSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret != null && !encryptedSecret.isBlank() ? encryptedSecret : null;
    }

    public boolean hasSecret() {
        return encryptedSecret != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Removes characters {@code global-settings.xml} cannot hold from the text the user typed. */
    void stripUnstorableText() {
        setId(AiSkill.withoutUnstorable(id));
        name = AiSkill.withoutUnstorable(name);
        setBaseUrl(AiSkill.withoutUnstorable(baseUrl));
        setUsername(AiSkill.withoutUnstorable(username));
    }
}
