package de.kortty.core;

/**
 * A downloaded SKILL.md.
 *
 * @param reference    canonical locator to store with the skill and to check for updates with
 * @param sourceUrl    page a browser opens for the file
 * @param revision     provider revision of the file: Git blob SHA, or SHA-256 of the body
 * @param markdown     the SKILL.md text, front matter included
 * @param fallbackName skill name when the front matter has none (the skill's directory)
 */
public record ExternalAiSkillDocument(
    String reference,
    String sourceUrl,
    String revision,
    String markdown,
    String fallbackName) {

    public ExternalAiSkillDocument {
        markdown = markdown != null ? markdown : "";
        sourceUrl = sourceUrl != null ? sourceUrl : reference;
    }
}
