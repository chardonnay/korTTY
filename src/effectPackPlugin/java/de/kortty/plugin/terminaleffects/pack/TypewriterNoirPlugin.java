package de.kortty.plugin.terminaleffects.pack;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.DoubleSupplier;

public final class TypewriterNoirPlugin extends AbstractEffectPackPlugin {

    public static final String PLUGIN_ID = "typewriter-noir";

    public TypewriterNoirPlugin() {
        super(PLUGIN_ID,
                "Typewriter Noir",
                "plugin.terminalEffects.desc.typewriter-noir",
                "Sepia paper and ink noir look with per-character typewriter output pacing.",
                new TerminalEffectAppearance(
                        "Monospaced", null, "#241C12", "#E9DFC8", "#241C12", "BLINK_UNDERLINE"));
    }

    @Override
    protected AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed) {
        return new TypewriterNoirOverlay(animationSpeed);
    }

    @Override
    public TerminalEffectSession createSession(TerminalEffectContext context) {
        return new PackEffectSession(context, appearance(), () -> createOverlay(context::animationSpeed)) {
            @Override
            public @NotNull TtyConnector wrapConnector(
                    @Nullable SithTermFxWidget widget,
                    @NotNull TtyConnector connector) {
                return wrapTypewriter(connector, context::animationSpeed);
            }
        };
    }

    static TtyConnector wrapTypewriter(TtyConnector connector, DoubleSupplier animationSpeed) {
        if (connector instanceof PackTypewriterTtyConnector) {
            return connector;
        }
        TtyConnector base = connector;
        while (base instanceof TerminalEffectConnectorWrapper wrapper) {
            base = wrapper.delegate();
        }
        return new PackTypewriterTtyConnector(base, animationSpeed);
    }

    @Override
    protected List<String> previewLines() {
        return List.of(
                "It was a rainy Tuesday night.",
                "The terminal blinked, once.",
                "She typed the last command:",
                "$ exit",
                "$ ");
    }
}
