package de.kortty.model;

import java.util.List;
import java.util.Objects;

/** A pinned label. Only the reserved hold ID changes deletion behaviour. */
public record TerminalLogBadge(String id, String name, String icon, String color, Style style) {
    public enum Style { SOFT, OUTLINE, SOLID, FLAG, MINIMAL }
    public static final List<String> ICONS = List.of("security", "logfile", "jobs", "server", "group",
        "terminal", "sftp", "ai", "gear", "privacy", "language", "translation", "themes", "appearance",
        "window", "video", "backup", "export", "updates", "resources", "editor", "snippetEditor", "add",
        "pin", "check", "warning", "star", "flag", "search", "clock");

    public TerminalLogBadge {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(style);
        name = name.strip();
        if (id.isBlank() || id.length() > 100 || name.isEmpty() || name.length() > 40
            || name.chars().anyMatch(Character::isISOControl) || !ICONS.contains(icon)
            || color == null || !color.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Invalid terminal log badge");
        }
    }
    public boolean hold() { return id.equals("hold"); }
    public static List<TerminalLogBadge> defaults() {
        return List.of(new TerminalLogBadge("hold", "Hold", "security", "#c08020", Style.SOFT),
            new TerminalLogBadge("review", "Review", "search", "#4488cc", Style.SOFT),
            new TerminalLogBadge("incident", "Incident", "warning", "#cc5555", Style.SOFT),
            new TerminalLogBadge("important", "Important", "star", "#9966cc", Style.SOFT),
            new TerminalLogBadge("done", "Done", "check", "#339977", Style.SOFT),
            new TerminalLogBadge("share", "Share", "export", "#3399aa", Style.SOFT));
    }
}
