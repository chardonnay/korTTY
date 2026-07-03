package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared session for pack effects: applies the effect appearance and installs one canvas overlay
 * over the tab's terminal container. Appearance restoration on stop is handled by the host.
 */
class PackEffectSession implements TerminalEffectSession {

    protected final TerminalEffectContext context;
    private final TerminalEffectAppearance appearance;
    private final Supplier<AbstractPackOverlay> overlayFactory;
    private @Nullable AbstractPackOverlay overlay;

    PackEffectSession(
            TerminalEffectContext context,
            TerminalEffectAppearance appearance,
            Supplier<AbstractPackOverlay> overlayFactory) {
        this.context = Objects.requireNonNull(context, "context");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.overlayFactory = Objects.requireNonNull(overlayFactory, "overlayFactory");
    }

    @Override
    public void start() {
        context.applyAppearance(appearance);
        AbstractPackOverlay installed = overlay;
        if (installed == null) {
            installed = overlayFactory.get();
            overlay = installed;
        }
        StackPane overlayRoot = context.overlayRoot();
        installed.widthProperty().bind(overlayRoot.widthProperty());
        installed.heightProperty().bind(overlayRoot.heightProperty());
        if (!overlayRoot.getChildren().contains(installed)) {
            overlayRoot.getChildren().add(installed);
        }
        installed.start();
    }

    @Override
    public void stop() {
        AbstractPackOverlay installed = overlay;
        if (installed == null) {
            return;
        }
        AbstractPackOverlay toRemove = installed;
        Platform.runLater(() -> {
            toRemove.stop();
            toRemove.widthProperty().unbind();
            toRemove.heightProperty().unbind();
            context.overlayRoot().getChildren().remove(toRemove);
        });
    }
}
