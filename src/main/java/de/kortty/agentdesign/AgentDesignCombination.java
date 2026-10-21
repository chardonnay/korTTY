package de.kortty.agentdesign;

import java.util.Objects;

/**
 * A named KI-Agent design: one module per {@link AgentRole}. Built-in combinations come from
 * {@link AgentDesignPresets} and cannot be changed; the user's own are kept in the global settings.
 *
 * @param id            stable id ({@code standard}, {@code classic} or {@code custom-…})
 * @param name          the user's name for an own combination; ignored for a built-in one, whose name
 *                      comes from the i18n bundle
 * @param builtIn       whether the combination ships with korTTY
 * @param historyId     module of the {@link AgentRole#HISTORY} role
 * @param statusId      module of the {@link AgentRole#STATUS} role
 * @param approvalId    module of the {@link AgentRole#APPROVAL} role
 * @param arrangementId module of the {@link AgentRole#ARRANGEMENT} role
 */
public record AgentDesignCombination(
    String id,
    String name,
    boolean builtIn,
    String historyId,
    String statusId,
    String approvalId,
    String arrangementId) {

    public AgentDesignCombination {
        Objects.requireNonNull(id, "id");
        name = name != null ? name.strip() : "";
    }

    /** The module chosen for {@code role}. */
    public String moduleFor(AgentRole role) {
        return switch (role) {
            case HISTORY -> historyId;
            case STATUS -> statusId;
            case APPROVAL -> approvalId;
            case ARRANGEMENT -> arrangementId;
        };
    }

    /** A copy with {@code moduleId} chosen for {@code role}. */
    public AgentDesignCombination with(AgentRole role, String moduleId) {
        return new AgentDesignCombination(
            id,
            name,
            builtIn,
            role == AgentRole.HISTORY ? moduleId : historyId,
            role == AgentRole.STATUS ? moduleId : statusId,
            role == AgentRole.APPROVAL ? moduleId : approvalId,
            role == AgentRole.ARRANGEMENT ? moduleId : arrangementId);
    }

    /** A user-owned copy of this combination under a new id and name. */
    public AgentDesignCombination asCustom(String newId, String newName) {
        return new AgentDesignCombination(newId, newName, false, historyId, statusId, approvalId, arrangementId);
    }

    /** A copy with a new name. */
    public AgentDesignCombination withName(String newName) {
        return new AgentDesignCombination(id, newName, builtIn, historyId, statusId, approvalId, arrangementId);
    }

    /** Whether the classic activity panel is the history module. */
    public boolean usesClassicHistory() {
        return AgentModuleCatalog.CLASSIC.equals(historyId);
    }

    /** Whether the in-terminal blocks are the history module. */
    public boolean usesInlineBlocks() {
        return AgentModuleCatalog.INLINE_BLOCKS.equals(historyId);
    }

    /** Whether the focus mode is the history module. */
    public boolean usesFocusMode() {
        return AgentModuleCatalog.FOCUS_MODE.equals(historyId);
    }

    /** Whether the metro-line bar is the status module. */
    public boolean usesMetroBar() {
        return AgentModuleCatalog.METRO_BAR.equals(statusId);
    }
}
