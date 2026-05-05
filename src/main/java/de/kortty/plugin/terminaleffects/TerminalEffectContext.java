package de.kortty.plugin.terminaleffects;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.ui.TerminalView;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Runtime context exposed to a terminal effect plugin.
 */
public final class TerminalEffectContext {

    private final String pluginId;
    private final TerminalView terminalView;
    private final StackPane overlayRoot;
    private final Supplier<List<SithTermFxWidget>> widgetsSupplier;
    private final DoubleSupplier animationSpeedSupplier;
    private final Consumer<TerminalEffectAppearance> appearanceConsumer;
    private final Runnable restoreAppearanceAction;

    public TerminalEffectContext(
            @NotNull String pluginId,
            @NotNull TerminalView terminalView,
            @NotNull StackPane overlayRoot,
            @NotNull Supplier<List<SithTermFxWidget>> widgetsSupplier,
            @NotNull DoubleSupplier animationSpeedSupplier,
            @NotNull Consumer<TerminalEffectAppearance> appearanceConsumer,
            @NotNull Runnable restoreAppearanceAction) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.terminalView = Objects.requireNonNull(terminalView, "terminalView");
        this.overlayRoot = Objects.requireNonNull(overlayRoot, "overlayRoot");
        this.widgetsSupplier = Objects.requireNonNull(widgetsSupplier, "widgetsSupplier");
        this.animationSpeedSupplier = Objects.requireNonNull(animationSpeedSupplier, "animationSpeedSupplier");
        this.appearanceConsumer = Objects.requireNonNull(appearanceConsumer, "appearanceConsumer");
        this.restoreAppearanceAction = Objects.requireNonNull(restoreAppearanceAction, "restoreAppearanceAction");
    }

    public String pluginId() {
        return pluginId;
    }

    public TerminalView terminalView() {
        return terminalView;
    }

    public StackPane overlayRoot() {
        return overlayRoot;
    }

    public List<SithTermFxWidget> widgets() {
        return List.copyOf(widgetsSupplier.get());
    }

    public double animationSpeed() {
        return TerminalEffectAnimationSpeed.normalize(animationSpeedSupplier.getAsDouble());
    }

    public void applyAppearance(TerminalEffectAppearance appearance) {
        appearanceConsumer.accept(appearance);
    }

    public void restoreAppearance() {
        restoreAppearanceAction.run();
    }
}
