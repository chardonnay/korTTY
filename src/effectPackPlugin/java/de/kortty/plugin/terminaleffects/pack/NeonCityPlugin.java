package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class NeonCityPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "neon-city";

    public NeonCityPlugin() {
        super(PLUGIN_ID,
                "Neon City",
                "plugin.terminalEffects.desc.neon-city",
                "Cyberpunk neon look with glitch tears, RGB-split flickers and pulsing glow.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#EAE6FF", "#0B0716", "#00E5FF", "BLINK_BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new NeonCityOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "$ jack_in --grid night-city",
                "ICE detected: layer 3",
                "breach protocol: 7A 55 E9 1C",
                "daemon uploaded. stay low.",
                "$ ");
    }
}
