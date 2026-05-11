package de.kortty.plugin.terminaleffects.mother;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.model.TerminalModelListener;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MotherTerminalEffectPlugin implements TerminalEffectPlugin {

    public static final String PLUGIN_ID = "mother";

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
        return "ALIEN-style green CRT terminal appearance with paced line output.";
    }

    @Override
    public TerminalEffectSession createSession(TerminalEffectContext context) {
        return new MotherTerminalEffectSession(context);
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
        private final AtomicBoolean visibleOutputPending = new AtomicBoolean(false);
        private @Nullable SithTermFxWidget watchedWidget;
        private @Nullable TerminalModelListener flashListener;

        private MotherTerminalEffectSession(TerminalEffectContext context) {
            this.context = context;
        }

        @Override
        public void start() {
            context.applyAppearance(APPEARANCE);
            installOverlay();
        }

        private void installOverlay() {
            StackPane overlayRoot = context.overlayRoot();
            overlay.widthProperty().bind(overlayRoot.widthProperty());
            overlay.heightProperty().bind(overlayRoot.heightProperty());
            if (!overlayRoot.getChildren().contains(overlay)) {
                overlayRoot.getChildren().add(overlay);
            }
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
            return new MotherPacedTtyConnector(
                    baseConnector,
                    context::animationSpeed,
                    () -> visibleOutputPending.set(true));
        }

        @Override
        public void stop() {
            detachFlashListener();
            Platform.runLater(() -> {
                overlay.stop();
                overlay.widthProperty().unbind();
                overlay.heightProperty().unbind();
                context.overlayRoot().getChildren().remove(overlay);
            });
        }

        private void attachFlashListener(@Nullable SithTermFxWidget widget) {
            if (widget == null || watchedWidget == widget) {
                return;
            }
            detachFlashListener();
            watchedWidget = widget;
            flashListener = () -> {
                if (visibleOutputPending.getAndSet(false)) {
                    overlay.flashCurrentLine(widget);
                }
            };
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
            visibleOutputPending.set(false);
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
