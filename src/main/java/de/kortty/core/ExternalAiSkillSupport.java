package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderType;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns downloaded SKILL.md files into library skills and checks imported ones for updates.
 *
 * <p>Product rule: an imported skill is always <strong>disabled</strong>, whatever its front matter says.
 * Its text goes into AI prompts verbatim, so the user reads it before switching it on.
 */
public final class ExternalAiSkillSupport {

    private ExternalAiSkillSupport() {
    }

    /**
     * A client for {@code provider}.
     *
     * @param secrets   the decrypted token or password of a provider, or {@code null} for none
     * @param providers all profiles — SkillsMP downloads through the GitHub profile, with its token
     */
    public static ExternalAiSkillClient clientFor(
            AiSkillProvider provider, Function<AiSkillProvider, String> secrets, List<AiSkillProvider> providers) {
        ExternalAiSkillHttp.Credentials credentials = credentials(provider, secrets);
        return switch (provider.getType()) {
            case GITHUB -> new GitHubAiSkillClient(provider.effectiveBaseUrl(), credentials);
            case SKILLSMP -> new SkillsMpAiSkillClient(provider.effectiveBaseUrl(), credentials,
                githubDownloader(secrets, providers));
            case HTTP -> new HttpAiSkillClient(provider.effectiveBaseUrl(), credentials);
        };
    }

    /** The GitHub profile SkillsMP results are downloaded with: an enabled one for github.com, else anonymous. */
    static ExternalAiSkillClient githubDownloader(
            Function<AiSkillProvider, String> secrets, List<AiSkillProvider> providers) {
        AiSkillProvider github = githubProviderFor(providers);
        return github != null
            ? new GitHubAiSkillClient(github.effectiveBaseUrl(), credentials(github, secrets))
            : new GitHubAiSkillClient(GitHubAiSkillClient.DEFAULT_API_URL, ExternalAiSkillHttp.Credentials.NONE);
    }

    /** The enabled GitHub profile for github.com whose token SkillsMP downloads use, or {@code null}. */
    public static AiSkillProvider githubProviderFor(List<AiSkillProvider> providers) {
        for (AiSkillProvider candidate : providers != null ? providers : List.<AiSkillProvider>of()) {
            if (candidate.isEnabled() && candidate.getType() == AiSkillProviderType.GITHUB
                && GitHubAiSkillClient.DEFAULT_API_URL.equalsIgnoreCase(candidate.effectiveBaseUrl())) {
                return candidate;
            }
        }
        return null;
    }

    private static ExternalAiSkillHttp.Credentials credentials(
            AiSkillProvider provider, Function<AiSkillProvider, String> secrets) {
        String secret = provider.hasSecret() && secrets != null ? secrets.apply(provider) : null;
        return new ExternalAiSkillHttp.Credentials(provider.getAuth(), provider.getUsername(), secret);
    }

    /** A new, disabled library skill from {@code document}, remembering where it came from. */
    public static AiSkill toSkill(ExternalAiSkillDocument document, AiSkillProvider provider, long nowMillis)
            throws IOException {
        AiSkill skill = AiSkillMarkdownCodec.importFromExternalMarkdownText(document.markdown(), document.fallbackName());
        skill.setEnabled(false);
        AiSkillExternalSource source = new AiSkillExternalSource();
        source.setProviderId(provider != null ? provider.getId() : null);
        skill.setExternalSource(source);
        adopt(skill, document, nowMillis);
        skill.stripUnstorableText();
        source.setDescriptionSha256(ExternalAiSkillHttp.sha256(skill.getDescription()));
        return skill;
    }

    /** The skill of {@code skills} imported from {@code reference}, or {@code null}. */
    public static AiSkill findImported(List<AiSkill> skills, String reference) {
        String key = referenceKey(reference);
        for (AiSkill skill : skills) {
            if (skill != null && skill.isExternal() && referenceKey(skill.getExternalSource().getReference()).equals(key)) {
                return skill;
            }
        }
        return null;
    }

    /** The skill of {@code skills} with {@code id}, or {@code null}. */
    public static AiSkill findById(List<AiSkill> skills, String id) {
        for (AiSkill skill : skills) {
            if (skill != null && Objects.equals(skill.getId(), id)) {
                return skill;
            }
        }
        return null;
    }

    /**
     * The plain local skill of {@code skills} named {@code name}, ignoring case and surrounding blanks, or
     * {@code null}. External skills are not matched: a copy made by {@link #copyAsLocalSkill} is found by its name here.
     */
    public static AiSkill findLocalByName(List<AiSkill> skills, String name) {
        String wanted = name != null ? name.trim() : "";
        for (AiSkill skill : skills) {
            String candidate = skill != null && !skill.isExternal() && skill.getName() != null
                ? skill.getName().trim() : null;
            if (candidate != null && candidate.equalsIgnoreCase(wanted)) {
                return skill;
            }
        }
        return null;
    }

    /**
     * Whether {@code skill} still holds the {@code content} and {@code description} an update dialog diffed. A dialog
     * decides on that text, so an update applies only while it still holds it.
     */
    public static boolean holdsText(AiSkill skill, String content, String description) {
        return skill != null
            && Objects.equals(Objects.requireNonNullElse(skill.getContent(), ""),
                Objects.requireNonNullElse(content, ""))
            && Objects.equals(Objects.requireNonNullElse(skill.getDescription(), ""),
                Objects.requireNonNullElse(description, ""));
    }

    /**
     * Whether saving {@code candidate} over {@code stored} would move a locally changed imported skill to another
     * provider revision. Such an update was made before the change, so the store refuses it too, not only the update.
     */
    static boolean replacesLocalChange(AiSkill stored, AiSkill candidate) {
        return stored != null && candidate != null && stored.isExternal() && candidate.isExternal()
            && !Objects.equals(stored.getExternalSource().getRevision(), candidate.getExternalSource().getRevision())
            && isLocallyModified(stored);
    }

    /** Case- and trailing-slash-insensitive form of a reference, for duplicate detection. */
    static String referenceKey(String reference) {
        String key = reference != null ? reference.trim().toLowerCase(Locale.ROOT) : "";
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    /**
     * Whether the user or the skill optimizer changed the text or the description since the import or the last
     * adopted update. A description imported before descriptions were tracked counts only through its text.
     */
    public static boolean isLocallyModified(AiSkill skill) {
        if (skill == null || !skill.isExternal()) {
            return false;
        }
        AiSkillExternalSource source = skill.getExternalSource();
        String importedContent = source.getContentSha256();
        if (importedContent != null && !importedContent.equals(ExternalAiSkillHttp.sha256(skill.getContent()))) {
            return true;
        }
        String importedDescription = source.getDescriptionSha256();
        return importedDescription != null
            && !importedDescription.equals(ExternalAiSkillHttp.sha256(skill.getDescription()));
    }

    /**
     * Whether {@code latest} is a different revision than the one {@code skill} was imported at and than the one
     * the user skipped. A newer revision after a skipped one is offered again.
     */
    public static boolean hasUpdate(AiSkill skill, ExternalAiSkillDocument latest) {
        if (skill == null || !skill.isExternal() || latest == null || latest.revision() == null) {
            return false;
        }
        AiSkillExternalSource source = skill.getExternalSource();
        return !latest.revision().equals(source.getRevision()) && !latest.revision().equals(source.getSkippedRevision());
    }

    /** Records {@code latest}'s revision as skipped, so {@link #hasUpdate} no longer offers it. */
    public static void skipRevision(AiSkill skill, ExternalAiSkillDocument latest) {
        if (skill == null || !skill.isExternal() || latest == null || latest.revision() == null) {
            throw new IllegalArgumentException("Only an update of an imported skill can be skipped.");
        }
        skill.getExternalSource().setSkippedRevision(latest.revision());
    }

    /**
     * The provider version of {@code latest} as a new, disabled, plain local skill, named after {@code skill} and the
     * revision. It has no external source, so update checks and duplicate detection do not see it, and it gets a
     * fresh id and no learned rules.
     */
    public static AiSkill copyAsLocalSkill(AiSkill skill, ExternalAiSkillDocument latest) throws IOException {
        AiSkill copy = AiSkillMarkdownCodec.importFromExternalMarkdownText(latest.markdown(), latest.fallbackName());
        String baseName = skill.getName() != null && !skill.getName().isBlank() ? skill.getName().trim() : copy.getName();
        copy.setName(baseName + " (" + shortRevision(latest.revision()) + ")");
        copy.setEnabled(false);
        copy.ensureId();
        copy.stripUnstorableText();
        return copy;
    }

    /**
     * The skill text {@code latest} would put into {@code skill} — what an update diff shows on its right.
     */
    public static String updatedContent(ExternalAiSkillDocument latest) throws IOException {
        return AiSkillMarkdownCodec.importFromExternalMarkdownText(latest.markdown(), latest.fallbackName()).getContent();
    }

    /**
     * Adopts an upstream update of an unmodified skill: replaces the text and, when it is tracked, the description;
     * keeps the user's name, tags, target, active state, learned rules and origin.
     *
     * <p>A skill that {@link #isLocallyModified is changed locally} is refused with a
     * {@link LocallyModifiedException}, so no caller can overwrite a local change. An import from before descriptions
     * were tracked keeps its description, since an edit of that cannot be told apart from the provider's text.
     */
    public static void applyUpdate(AiSkill skill, ExternalAiSkillDocument latest, long nowMillis) throws IOException {
        if (skill == null || !skill.isExternal()) {
            throw new IllegalArgumentException("Only an imported skill has an update.");
        }
        if (isLocallyModified(skill)) {
            throw new LocallyModifiedException(skill.getName());
        }
        AiSkillExternalSource source = skill.getExternalSource();
        boolean descriptionTracked = source.getDescriptionSha256() != null;
        AiSkill parsed = AiSkillMarkdownCodec.importFromExternalMarkdownText(latest.markdown(), latest.fallbackName());
        skill.setContent(parsed.getContent());
        if (descriptionTracked && parsed.getDescription() != null && !parsed.getDescription().isBlank()) {
            skill.setDescription(parsed.getDescription());
        }
        adopt(skill, latest, nowMillis);
        skill.stripUnstorableText();
        if (descriptionTracked) {
            source.setDescriptionSha256(ExternalAiSkillHttp.sha256(skill.getDescription()));
        }
    }

    private static void adopt(AiSkill skill, ExternalAiSkillDocument document, long nowMillis) {
        AiSkillExternalSource source = skill.getExternalSource();
        source.setReference(document.reference());
        source.setSourceUrl(document.sourceUrl());
        source.setRevision(document.revision());
        source.setContentSha256(ExternalAiSkillHttp.sha256(skill.getContent()));
        source.setSkippedRevision(null);
        source.setImportedAt(nowMillis);
    }

    /** {@link #applyUpdate} refused a skill the user or the skill optimizer changed. */
    public static final class LocallyModifiedException extends IllegalStateException {

        private final String skillName;

        public LocallyModifiedException(String skillName) {
            super("The skill \"" + skillName + "\" was changed locally; an update does not overwrite it.");
            this.skillName = skillName != null ? skillName : "";
        }

        /** The name of the refused skill, for the message the user sees. */
        public String skillName() {
            return skillName;
        }
    }

    /** First seven characters of a revision, like Git prints a commit. */
    public static String shortRevision(String revision) {
        if (revision == null || revision.isBlank()) {
            return "—";
        }
        return revision.length() > 7 ? revision.substring(0, 7) : revision;
    }
}
