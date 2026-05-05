package de.kortty.plugin.terminaleffects;

import org.jetbrains.annotations.Nullable;

/**
 * Optional appearance values applied by a terminal effect.
 */
public record TerminalEffectAppearance(
        @Nullable String fontFamily,
        @Nullable Integer fontSize,
        @Nullable String foregroundColor,
        @Nullable String backgroundColor,
        @Nullable String cursorColor,
        @Nullable String cursorStyle) {
}
