package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderType;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
        AiSkill skill = AiSkillMarkdownCodec.importFromMarkdownText(document.markdown(), document.fallbackName());
        skill.setEnabled(false);
        AiSkillExternalSource source = new AiSkillExternalSource();
        source.setProviderId(provider != null ? provider.getId() : null);
        skill.setExternalSource(source);
        adopt(skill, document, nowMillis);
        skill.stripUnstorableText();
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

    /** Case- and trailing-slash-insensitive form of a reference, for duplicate detection. */
    static String referenceKey(String reference) {
        String key = reference != null ? reference.trim().toLowerCase(Locale.ROOT) : "";
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    /** Whether the skill text differs from what was imported (the user edited it). */
    public static boolean isLocallyModified(AiSkill skill) {
        if (skill == null || !skill.isExternal()) {
            return false;
        }
        String imported = skill.getExternalSource().getContentSha256();
        return imported != null && !imported.equals(ExternalAiSkillHttp.sha256(skill.getContent()));
    }

    /** Whether {@code latest} is a different revision than the one {@code skill} was imported at. */
    public static boolean hasUpdate(AiSkill skill, ExternalAiSkillDocument latest) {
        if (skill == null || !skill.isExternal() || latest == null || latest.revision() == null) {
            return false;
        }
        return !latest.revision().equals(skill.getExternalSource().getRevision());
    }

    /**
     * The skill text {@code latest} would put into {@code skill} — what an update diff shows on its right.
     */
    public static String updatedContent(ExternalAiSkillDocument latest) throws IOException {
        return AiSkillMarkdownCodec.importFromMarkdownText(latest.markdown(), latest.fallbackName()).getContent();
    }

    /**
     * Adopts an upstream update: replaces the text and description, keeps the user's name, tags, target and
     * active state. A local edit of the text is overwritten — the update dialog shows that diff first.
     */
    public static void applyUpdate(AiSkill skill, ExternalAiSkillDocument latest, long nowMillis) throws IOException {
        AiSkill parsed = AiSkillMarkdownCodec.importFromMarkdownText(latest.markdown(), latest.fallbackName());
        skill.setContent(parsed.getContent());
        if (parsed.getDescription() != null && !parsed.getDescription().isBlank()) {
            skill.setDescription(parsed.getDescription());
        }
        adopt(skill, latest, nowMillis);
        skill.stripUnstorableText();
    }

    private static void adopt(AiSkill skill, ExternalAiSkillDocument document, long nowMillis) {
        AiSkillExternalSource source = skill.getExternalSource();
        source.setReference(document.reference());
        source.setSourceUrl(document.sourceUrl());
        source.setRevision(document.revision());
        source.setContentSha256(ExternalAiSkillHttp.sha256(skill.getContent()));
        source.setImportedAt(nowMillis);
    }

    /** First seven characters of a revision, like Git prints a commit. */
    public static String shortRevision(String revision) {
        if (revision == null || revision.isBlank()) {
            return "—";
        }
        return revision.length() > 7 ? revision.substring(0, 7) : revision;
    }
}
