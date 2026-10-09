package de.kortty.core;

/**
 * One skill a provider search found, before its SKILL.md is downloaded.
 *
 * @param reference what {@link ExternalAiSkillClient#fetch(String)} takes to download it
 * @param name      display name; the skill directory when the provider reports no name
 * @param description may be empty — a GitHub repository listing does not read every SKILL.md
 * @param author    GitHub owner or vendor-reported author; may be empty
 * @param sourceUrl page a browser opens for the skill
 * @param stars     GitHub stars as the provider reports them, or {@code null}
 */
public record ExternalAiSkillCandidate(
    String reference,
    String name,
    String description,
    String author,
    String sourceUrl,
    Integer stars) {

    public ExternalAiSkillCandidate {
        reference = reference != null ? reference : "";
        name = name != null && !name.isBlank() ? name : reference;
        description = description != null ? description : "";
        author = author != null ? author : "";
        sourceUrl = sourceUrl != null ? sourceUrl : reference;
    }
}
