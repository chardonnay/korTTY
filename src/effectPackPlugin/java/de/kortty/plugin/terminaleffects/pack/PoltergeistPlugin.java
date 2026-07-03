package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class PoltergeistPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "poltergeist";

    public PoltergeistPlugin() {
        super(PLUGIN_ID,
                "Poltergeist",
                "plugin.terminalEffects.desc.poltergeist",
                "Haunted monochrome terminal with a breathing vignette, static bursts and ghostly flashes.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#C9C9C9", "#050505", "#FF2B2B", "BLINK_BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new PoltergeistOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "$ tail -f /var/log/whispers",
                "03:33:33 they are here",
                "03:33:33 do not turn around",
                "signal lost.",
                "$ ");
    }
}
