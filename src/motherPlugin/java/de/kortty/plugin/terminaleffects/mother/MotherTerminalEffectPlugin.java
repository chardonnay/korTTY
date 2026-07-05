package de.kortty.plugin.terminaleffects.mother;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.model.TerminalModelListener;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectPreview;
import de.kortty.plugin.terminaleffects.TerminalEffectPreviewCanvas;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MotherTerminalEffectPlugin implements TerminalEffectPlugin {

    public static final String PLUGIN_ID = "mother";

    private static final String DESCRIPTION_ENGLISH =
            "ALIEN-style green CRT terminal appearance with paced line output.";

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "MU/TH/UR 6000";
    }

    @Override
    public String description() {
        return localized("plugin.terminalEffects.desc.mother", DESCRIPTION_ENGLISH);
    }

    @Override
    public TerminalEffectSession createSession(TerminalEffectContext context) {
        return new MotherTerminalEffectSession(context);
    }

    @Override
    public TerminalEffectPreview createPreview() {
        return TerminalEffectPreviewCanvas.builder()
                .backgroundColor("#000000")
                .foregroundColor("#19FF4C")
                .lines(List.of(
                        "INTERFACE 2037 READY FOR INQUIRY",
                        "> REQUEST EVALUATION OF MISSION",
                        "ANALYSIS CONFIRMED",
                        "> WHAT IS SPECIAL ORDER 937?",
                        "> "))
                .overlay(MotherCrtOverlay::new, MotherCrtOverlay::start, MotherCrtOverlay::stop)
                .build();
    }

    private static String localized(String key, String englishFallback) {
        try {
            String value = de.kortty.ui.I18n.get(key);
            if (value == null || value.isBlank() || value.equals(key)) {
                return englishFallback;
            }
            return value;
        } catch (Throwable t) {
            return englishFallback;
        }
    }

    private static final class MotherTerminalEffectSession implements TerminalEffectSession {

        private static final TerminalEffectAppearance APPEARANCE = new TerminalEffectAppearance(
                "Monospaced",
                null,
                "#19FF4C",
                "#000000",
                "#F2F2F2",
                "BLINK_BLOCK");

        private final TerminalEffectContext context;
        private final MotherCrtOverlay overlay = new MotherCrtOverlay();
        private @Nullable SithTermFxWidget watchedWidget;
        private @Nullable TerminalModelListener flashListener;

        private MotherTerminalEffectSession(TerminalEffectContext context) {
            this.context = context;
        }

        @Override
        public void start() {
            context.applyAppearance(APPEARANCE);
            installOverlay();
            // Drive the per-line glow from the pane's output model so it works even when the effect is
            // applied to an already-connected pane (the connector read path can't be re-wrapped live).
            attachFlashListener(primaryWidget());
        }

        private @Nullable SithTermFxWidget primaryWidget() {
            java.util.List<SithTermFxWidget> widgets = context.widgets();
            return widgets.isEmpty() ? null : widgets.get(0);
        }

        private void installOverlay() {
            StackPane overlayRoot = context.overlayRoot();
            if (!overlayRoot.getChildren().contains(overlay)) {
                overlayRoot.getChildren().add(overlay);
            }
            overlay.bindToContainer(overlayRoot);
            overlay.start();
        }

        @Override
        public @NotNull TtyConnector wrapConnector(
                @Nullable SithTermFxWidget widget,
                @NotNull TtyConnector connector) {
            if (connector instanceof MotherPacedTtyConnector) {
                return connector;
            }
            attachFlashListener(widget);
            TtyConnector baseConnector = unwrap(connector);
            return new MotherPacedTtyConnector(baseConnector, context::animationSpeed);
        }

        @Override
        public void stop() {
            detachFlashListener();
            Platform.runLater(() -> {
                overlay.stop();
                overlay.detachFromContainer();
            });
        }

        private void attachFlashListener(@Nullable SithTermFxWidget widget) {
            if (widget == null || watchedWidget == widget) {
                return;
            }
            detachFlashListener();
            watchedWidget = widget;
            // Glow the cursor line whenever the pane's buffer changes (throttled inside the overlay).
            flashListener = () -> overlay.flashCurrentLine(widget);
            widget.getTerminalTextBuffer().addModelListener(flashListener);
        }

        private void detachFlashListener() {
            SithTermFxWidget widget = watchedWidget;
            TerminalModelListener listener = flashListener;
            if (widget != null && listener != null) {
                widget.getTerminalTextBuffer().removeModelListener(listener);
            }
            watchedWidget = null;
            flashListener = null;
        }

        private static TtyConnector unwrap(TtyConnector connector) {
            TtyConnector current = connector;
            while (current instanceof TerminalEffectConnectorWrapper wrapper) {
                current = wrapper.delegate();
            }
            return current;
        }
    }
}
