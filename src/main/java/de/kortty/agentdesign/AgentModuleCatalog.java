package de.kortty.agentdesign;

import java.util.List;
import java.util.Optional;

/**
 * Every KI-Agent design module korTTY knows, grouped by role. A module that is not implemented would be
 * listed so the design dialog can show it as coming later; a combination can only pick implemented ones.
 */
public final class AgentModuleCatalog {

    public static final String CLASSIC = "classic";
    public static final String INLINE_BLOCKS = "d03";
    /** HISTORY: the agent's lines in the terminal, marked, while the rest of the terminal dims. */
    public static final String FOCUS_MODE = "d17";
    public static final String METRO_BAR = "d08";
    /** STATUS: no status indicator next to the history. */
    public static final String NO_STATUS = "none";
    /** APPROVAL: requests are answered in the history module (or its status bar). */
    public static final String APPROVAL_IN_HISTORY = "history";
    /** APPROVAL: the proposed command as ghost text at the cursor line. */
    public static final String CURSOR_APPROVAL = "d14";
    /** ARRANGEMENT: modules stay where their role puts them. */
    public static final String FIXED = "fixed";
    /** ARRANGEMENT: the panel floats in a picture-in-picture window that snaps to the pane's corners. */
    public static final String PICTURE_IN_PICTURE = "d19";
    /** ARRANGEMENT: the status display follows the terminal's size (side timeline, bar or pill). */
    public static final String ADAPTIVE_DOCK = "d20";

    private static final List<AgentModuleInfo> MODULES = List.of(
        new AgentModuleInfo(CLASSIC, 0, AgentRole.HISTORY, true),
        new AgentModuleInfo(INLINE_BLOCKS, 3, AgentRole.HISTORY, true),
        new AgentModuleInfo("d02", 2, AgentRole.HISTORY, true),
        new AgentModuleInfo("d06", 6, AgentRole.HISTORY, true),
        new AgentModuleInfo("d07", 7, AgentRole.HISTORY, true),
        new AgentModuleInfo("d09", 9, AgentRole.HISTORY, true),
        new AgentModuleInfo("d13", 13, AgentRole.HISTORY, true),
        new AgentModuleInfo("d16", 16, AgentRole.HISTORY, true),
        new AgentModuleInfo(FOCUS_MODE, 17, AgentRole.HISTORY, true),
        new AgentModuleInfo(NO_STATUS, 0, AgentRole.STATUS, true),
        new AgentModuleInfo(METRO_BAR, 8, AgentRole.STATUS, true),
        new AgentModuleInfo("d01", 1, AgentRole.STATUS, true),
        new AgentModuleInfo("d11", 11, AgentRole.STATUS, true),
        new AgentModuleInfo("d12", 12, AgentRole.STATUS, true),
        new AgentModuleInfo("d18", 18, AgentRole.STATUS, true),
        new AgentModuleInfo("d04", 4, AgentRole.STATUS, true),
        new AgentModuleInfo("d05", 5, AgentRole.STATUS, true),
        new AgentModuleInfo("d10", 10, AgentRole.STATUS, true),
        new AgentModuleInfo("d15", 15, AgentRole.STATUS, true),
        new AgentModuleInfo(APPROVAL_IN_HISTORY, 0, AgentRole.APPROVAL, true),
        new AgentModuleInfo(CURSOR_APPROVAL, 14, AgentRole.APPROVAL, true),
        new AgentModuleInfo(FIXED, 0, AgentRole.ARRANGEMENT, true),
        new AgentModuleInfo(PICTURE_IN_PICTURE, 19, AgentRole.ARRANGEMENT, true),
        new AgentModuleInfo(ADAPTIVE_DOCK, 20, AgentRole.ARRANGEMENT, true));

    private AgentModuleCatalog() {
    }

    /** All modules in table order: by role, then as listed above. */
    public static List<AgentModuleInfo> all() {
        return MODULES;
    }

    /** The modules that can fill {@code role}, in table order. */
    public static List<AgentModuleInfo> forRole(AgentRole role) {
        return MODULES.stream().filter(module -> module.role() == role).toList();
    }

    public static Optional<AgentModuleInfo> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        return MODULES.stream().filter(module -> module.id().equals(trimmed)).findFirst();
    }

    /** Whether {@code id} names an implemented module that fills {@code role}. */
    public static boolean isUsable(String id, AgentRole role) {
        return find(id).filter(module -> module.role() == role && module.implemented()).isPresent();
    }
}
