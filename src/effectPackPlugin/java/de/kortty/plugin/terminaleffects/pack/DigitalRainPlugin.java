package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class DigitalRainPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "digital-rain";

    public DigitalRainPlugin() {
        super(PLUGIN_ID,
                "Digital Rain",
                "plugin.terminalEffects.desc.digital-rain",
                "Green-on-black matrix style with a faint stream of falling glyphs.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#00FF66", "#020A04", "#B4FFC8", "BLINK_BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new DigitalRainOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "Wake up, Neo...",
                "The Matrix has you.",
                "Follow the white rabbit.",
                "Knock, knock, Neo.",
                "$ ");
    }
}
