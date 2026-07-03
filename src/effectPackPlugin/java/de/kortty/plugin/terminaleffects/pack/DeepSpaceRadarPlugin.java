package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class DeepSpaceRadarPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "deep-space-radar";

    public DeepSpaceRadarPlugin() {
        super(PLUGIN_ID,
                "Deep Space Radar",
                "plugin.terminalEffects.desc.deep-space-radar",
                "Tactical deep-space console with a slow radar sweep, faint blips and frame corners.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#35D08A", "#020D07", "#9CFFC9", "BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new DeepSpaceRadarOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "> radar --sweep long-range",
                "contact bearing 042 mark 7",
                "classification: unknown",
                "shields at 100%",
                "> ");
    }
}
