package de.kortty.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A skill (or a whole skill repository) on GitHub, parsed from what users copy out of skill
 * directories:
 *
 * <ul>
 *   <li>{@code owner/repo} — every skill in the repository</li>
 *   <li>{@code owner/repo@skill} or {@code npx skills add owner/repo --skill skill} — one skill by its
 *       directory name, as agenticskills.io and skills.sh print it</li>
 *   <li>{@code owner/repo/path/to/skill} — one skill directory on the default branch</li>
 *   <li>{@code https://github.com/owner/repo/tree/<ref>/<path>} or {@code …/blob/<ref>/<path>/SKILL.md}</li>
 * </ul>
 *
 * @param ref       branch, tag or commit; {@code null} for the default branch
 * @param path      skill directory inside the repository ({@code ""} for the root); {@code null} when unknown
 * @param skillName directory name to look for when {@code path} is unknown; may be {@code null}
 */
public record GitHubSkillReference(String owner, String repo, String ref, String path, String skillName) {

    static final String SKILL_FILE = "SKILL.md";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern NPX_PREFIX =
        Pattern.compile("^(?:npx|bunx|pnpm dlx)\\s+(?:-y\\s+)?skills(?:@\\S+)?\\s+add\\s+", Pattern.CASE_INSENSITIVE);

    /**
     * Whether {@code text} names a repository or skill (one of the forms above) rather than search
     * keywords: it is a URL, a copied {@code npx skills add} command, or contains {@code /} or {@code @}.
     */
    public static boolean looksLikeReference(String text) {
        String trimmed = text != null ? text.trim() : "";
        return trimmed.matches("(?i)^https?://.*") || NPX_PREFIX.matcher(trimmed).find()
            || trimmed.contains("/") || trimmed.contains("@");
    }

    /** Parses one of the forms above; throws {@link ExternalAiSkillException.Reason#INVALID_REFERENCE} otherwise. */
    public static GitHubSkillReference parse(String raw) throws ExternalAiSkillException {
        String text = raw != null ? raw.trim() : "";
        text = NPX_PREFIX.matcher(text).replaceFirst("");
        String skillOption = null;
        int option = text.indexOf(" --skill ");
        if (option >= 0) {
            skillOption = text.substring(option + " --skill ".length()).trim().split("\\s+")[0];
            text = text.substring(0, option).trim();
        }
        if (text.isEmpty()) {
            throw invalid(raw);
        }
        GitHubSkillReference parsed = text.matches("(?i)^https?://.*") ? parseUrl(text, raw) : parseShort(text, raw);
        if (skillOption != null && parsed.path == null) {
            parsed = new GitHubSkillReference(parsed.owner, parsed.repo, parsed.ref, null, skillOption);
        }
        return parsed;
    }

    private static GitHubSkillReference parseUrl(String text, String raw) throws ExternalAiSkillException {
        String rawPath;
        try {
            rawPath = URI.create(text.replace(" ", "%20")).getRawPath();
        } catch (IllegalArgumentException e) {
            throw invalid(raw);
        }
        // Decoded per segment, so a branch written as feature%2Fx stays one segment.
        List<String> segments = new ArrayList<>();
        for (String segment : segments(rawPath)) {
            segments.add(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        if (segments.size() < 2) {
            throw invalid(raw);
        }
        String owner = segments.get(0);
        String repo = stripGitSuffix(segments.get(1));
        if (segments.size() == 2) {
            return validated(owner, repo, null, null, null, raw);
        }
        String kind = segments.get(2);
        if (!("tree".equals(kind) || "blob".equals(kind)) || segments.size() < 4) {
            throw invalid(raw);
        }
        String ref = segments.get(3);
        String skillPath = segments.size() > 4 ? String.join("/", segments.subList(4, segments.size())) : null;
        return validated(owner, repo, ref, stripSkillFile(skillPath), null, raw);
    }

    private static GitHubSkillReference parseShort(String text, String raw) throws ExternalAiSkillException {
        String skillName = null;
        int at = text.lastIndexOf('@');
        if (at > 0) {
            skillName = text.substring(at + 1).trim();
            text = text.substring(0, at).trim();
            if (skillName.isEmpty()) {
                throw invalid(raw);
            }
        }
        List<String> segments = segments(text);
        if (segments.size() < 2) {
            throw invalid(raw);
        }
        String skillPath = segments.size() > 2 ? String.join("/", segments.subList(2, segments.size())) : null;
        if (skillPath != null && skillName != null) {
            throw invalid(raw);
        }
        return validated(segments.get(0), stripGitSuffix(segments.get(1)), null, stripSkillFile(skillPath), skillName, raw);
    }

    private static GitHubSkillReference validated(
            String owner, String repo, String ref, String path, String skillName, String raw)
            throws ExternalAiSkillException {
        if (!NAME.matcher(owner).matches() || !NAME.matcher(repo).matches()) {
            throw invalid(raw);
        }
        if (ref != null && (ref.isBlank() || ref.contains(".."))) {
            throw invalid(raw);
        }
        if (path != null && Arrays.asList(path.split("/")).contains("..")) {
            throw invalid(raw);
        }
        if (skillName != null && (skillName.contains("/") || skillName.contains(".."))) {
            throw invalid(raw);
        }
        return new GitHubSkillReference(owner, repo, ref, path, skillName);
    }

    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : (path != null ? path : "").split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment.trim());
            }
        }
        return segments;
    }

    private static String stripGitSuffix(String repo) {
        return repo.toLowerCase(Locale.ROOT).endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }

    /** {@code skills/pdf/SKILL.md} → {@code skills/pdf}; {@code SKILL.md} → {@code ""} (repository root). */
    static String stripSkillFile(String path) {
        if (path == null) {
            return null;
        }
        if (path.equalsIgnoreCase(SKILL_FILE)) {
            return "";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String suffix = "/" + SKILL_FILE.toLowerCase(Locale.ROOT);
        return lower.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
    }

    private static ExternalAiSkillException invalid(String raw) {
        return new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, raw != null ? raw.trim() : "");
    }

    /** Last directory of {@link #path()}, else the skill name, else the repository name. */
    public String displayName() {
        if (path != null && !path.isEmpty()) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return skillName != null ? skillName : repo;
    }
}
