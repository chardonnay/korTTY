package de.kortty.agentdesign;

/**
 * One selectable building block of a KI-Agent design: one of the numbered designs ({@code d01} to
 * {@code d20}), today's panel ({@code classic}) or a role's neutral choice ({@code none},
 * {@code history}, {@code fixed}).
 *
 * @param id          stable id, stored in settings and connections
 * @param number      the design's number from the design catalogue, or 0 for a neutral choice
 * @param role        the role the module fills
 * @param implemented whether this version of korTTY can show it; others are listed as coming later
 */
public record AgentModuleInfo(String id, int number, AgentRole role, boolean implemented) {

    /** The i18n key of the module's name. */
    public String nameKey() {
        return "aiAgentDesign.module." + id + ".name";
    }

    /** The i18n key of the module's one-line description. */
    public String summaryKey() {
        return "aiAgentDesign.module." + id + ".summary";
    }

    /** The design number as shown in the table ("03"), or an empty string for a neutral choice. */
    public String numberLabel() {
        return number > 0 ? String.format(java.util.Locale.ROOT, "%02d", number) : "";
    }
}
