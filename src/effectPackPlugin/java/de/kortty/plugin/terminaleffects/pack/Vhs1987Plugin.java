package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class Vhs1987Plugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "vhs-1987";

    public Vhs1987Plugin() {
        super(PLUGIN_ID,
                "VHS 1987",
                "plugin.terminalEffects.desc.vhs-1987",
                "Worn VHS tape playback with tracking noise, rolling distortion and a PLAY overlay.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#E8E8E8", "#0A0A0C", "#FFFFFF", "BLINK_BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new VhsOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "$ vcr --insert memories.tape",
                "TRACKING . . . . OK",
                "SP  0:13:37",
                "be kind, rewind",
                "$ ");
    }
}
