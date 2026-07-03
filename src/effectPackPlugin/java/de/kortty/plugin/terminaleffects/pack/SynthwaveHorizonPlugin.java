package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class SynthwaveHorizonPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "synthwave-horizon";

    public SynthwaveHorizonPlugin() {
        super(PLUGIN_ID,
                "Synthwave Horizon",
                "plugin.terminalEffects.desc.synthwave-horizon",
                "Retro-80s synthwave palette with a glowing perspective grid on the horizon.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#F8E7FF", "#14061F", "#FF71CE", "BLINK_BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new SynthwaveHorizonOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "$ synth --wave outrun",
                "bpm: 118   year: 1984",
                "gridlines: rendered",
                "sunset: forever",
                "$ ");
    }
}
