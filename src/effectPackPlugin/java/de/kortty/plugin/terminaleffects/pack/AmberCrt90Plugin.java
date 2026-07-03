package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class AmberCrt90Plugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "amber-crt-90";

    public AmberCrt90Plugin() {
        super(PLUGIN_ID,
                "Amber CRT '90",
                "plugin.terminalEffects.desc.amber-crt-90",
                "Amber phosphor CRT monitor from the 90s with scanlines, glow, flicker and a rolling refresh band.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#FFB000", "#0A0500", "#FFD75F", "BLINK_BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new AmberCrtOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "C:\\> VER",
                "MS-DOS Version 6.22",
                "C:\\> DIR /W",
                "AUTOEXEC.BAT  CONFIG.SYS  MOUSE.COM",
                "247,808 Bytes frei",
                "C:\\> ");
    }
}
