package de.kortty.model;

/**
 * A named color palette for the AI chat surfaces (main AI chat and swarm chat).
 *
 * <p>A profile either {@link #followsTheme() follows the active terminal theme} — in which case the
 * concrete colors are derived at render time from the theme's agent-panel palette — or carries an
 * explicit set of hex colors. The nine explicit slots map onto the chat's visual regions:
 * canvas ({@code background}), lifted chrome ({@code surface}), body text ({@code foreground}),
 * role/meta labels ({@code muted}), the assistant accent marker and focus ({@code accent}), hairlines
 * ({@code border}), code blocks ({@code codeBackground}), and the right-indented user bubble
 * ({@code userBubbleBackground} / {@code userBubbleBorder}).
 *
 * <p>Built-in profiles live in the UI-layer registry; new profiles supplied as color sets are added
 * there as one {@link #of} entry each.
 */
public record ChatColorProfile(
    String id,
    String name,
    boolean followsTheme,
    String background,
    String surface,
    String foreground,
    String muted,
    String accent,
    String border,
    String codeBackground,
    String userBubbleBackground,
    String userBubbleBorder) {

    /** A profile whose colors are derived from the active terminal theme at render time. */
    public static ChatColorProfile followTheme(String id, String name) {
        return new ChatColorProfile(id, name, true, null, null, null, null, null, null, null, null, null);
    }

    /** A profile with an explicit, fixed palette. */
    public static ChatColorProfile of(
        String id,
        String name,
        String background,
        String surface,
        String foreground,
        String muted,
        String accent,
        String border,
        String codeBackground,
        String userBubbleBackground,
        String userBubbleBorder) {
        return new ChatColorProfile(id, name, false, background, surface, foreground, muted, accent, border,
            codeBackground, userBubbleBackground, userBubbleBorder);
    }
}
