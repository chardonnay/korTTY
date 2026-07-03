package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class HologramHudPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "hologram-hud";

    public HologramHudPlugin() {
        super(PLUGIN_ID,
                "Hologram HUD",
                "plugin.terminalEffects.desc.hologram-hud",
                "Translucent sci-fi hologram with interference bands, HUD corner brackets and flicker.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#9FE8FF", "#04141C", "#CFF6FF", "BLINK_BAR"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new HologramHudOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "> initiate holo-link",
                "PROJECTION STABLE 98.4%",
                "> scan sector 7G",
                "3 contacts identified",
                "> ");
    }
}
