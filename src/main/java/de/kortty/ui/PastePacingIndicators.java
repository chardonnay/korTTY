package de.kortty.ui;

import de.kortty.paste.PastePacer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Shows how far a paced paste is, in the bottom right corner of its pane: "Pasting line 3 of 40 ·
 * Esc stops". The label appears with the first line and goes away when the paste ends, however it
 * ends. It does not take mouse clicks, so the pane underneath stays usable.
 *
 * <p>JavaFX thread, like {@link PastePacer}.
 */
final class PastePacingIndicators implements PastePacer.Listener {

    /** The style class of the label, for designs that want to restyle it. */
    static final String STYLE_CLASS = "paste-pacing-indicator";

    private static final String STYLE = "-fx-background-color: rgba(0, 0, 0, 0.72); -fx-text-fill: white;"
        + " -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-size: 0.8462em;";

    private final Function<Object, StackPane> hostFor;

    /** The label shown for each pacing pane, by the pane's key, compared by reference. */
    private final Map<Object, Label> shown = new IdentityHashMap<>();

    /** @param hostFor the pane's own stack to show the label in, by the pane's key; null shows nothing */
    PastePacingIndicators(Function<Object, StackPane> hostFor) {
        this.hostFor = Objects.requireNonNull(hostFor, "hostFor");
    }

    @Override
    public void progressed(Object key, int sent, int total) {
        Label label = shown.get(key);
        if (label == null) {
            StackPane host = hostFor.apply(key);
            if (host == null) {
                return;
            }
            label = createLabel();
            host.getChildren().add(label);
            shown.put(key, label);
        }
        label.setText(I18n.get("terminal.paste.pacing.progress", sent, total));
    }

    @Override
    public void ended(Object key, PastePacer.Outcome outcome, int sent, int total) {
        Label label = shown.remove(key);
        if (label != null && label.getParent() instanceof Pane parent) {
            parent.getChildren().remove(label);
        }
    }

    private static Label createLabel() {
        Label label = new Label();
        label.getStyleClass().add(STYLE_CLASS);
        label.setStyle(STYLE);
        label.setMouseTransparent(true);
        label.setFocusTraversable(false);
        label.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(label, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(label, new Insets(0, 18, 8, 0));
        return label;
    }
}
