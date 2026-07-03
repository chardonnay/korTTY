package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class CommodoreHeritagePlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "commodore-blue";

    public CommodoreHeritagePlugin() {
        super(PLUGIN_ID,
                "Commodore Heritage",
                "plugin.terminalEffects.desc.commodore-blue",
                "Classic C64 home computer look: light blue on blue with a chunky cursor and loader bars.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#A59FE6", "#40318D", "#A59FE6", "BLOCK"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new CommodoreLoaderOverlay(animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "**** COMMODORE 64 BASIC V2 ****",
                "64K RAM SYSTEM  38911 BASIC BYTES FREE",
                "READY.",
                "LOAD \"KORTTY\",8,1",
                "SEARCHING FOR KORTTY",
                "READY.");
    }
}
