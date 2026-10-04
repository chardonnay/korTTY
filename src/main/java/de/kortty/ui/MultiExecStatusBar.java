package de.kortty.ui;

import com.sithtermfx.ui.split.TerminalSplitPane;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.shape.SVGPath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The multi-exec chip in a window's status bar: an icon, how many panes take part in how many tabs
 * and windows, how many of them get nothing right now, and a <b>Stop</b> link that takes every pane
 * of every window out. Every window shows it while any pane takes part, and hides it (unmanaged, so
 * the status bar looks as before) while none does. The stylesheets give it an amber background
 * with dark text, readable on every theme. FX thread only.
 */
final class MultiExecStatusBar extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(MultiExecStatusBar.class);

    static final String STYLE_CLASS = "kortty-multi-exec-status";
    static final String ICON_STYLE_CLASS = "kortty-multi-exec-status-icon";
    static final String TEXT_STYLE_CLASS = "kortty-multi-exec-status-text";
    static final String STOP_STYLE_CLASS = "kortty-multi-exec-status-stop";

    private final Label text = new Label();
    private final Hyperlink stop = new Hyperlink(I18n.get(MultiExecMarkers.STATUS_STOP_KEY));
    private @Nullable Runnable onStop;

    MultiExecStatusBar() {
        super(6);
        getStyleClass().add(STYLE_CLASS);
        setAlignment(Pos.CENTER_LEFT);
        SVGPath icon = new SVGPath();
        icon.setContent(TerminalSplitPane.MIRROR_ICON_PATH);
        icon.getStyleClass().add(ICON_STYLE_CLASS);
        text.getStyleClass().add(TEXT_STYLE_CLASS);
        stop.getStyleClass().add(STOP_STYLE_CLASS);
        stop.setOnAction(event -> {
            Runnable handler = onStop;
            if (handler != null) {
                try {
                    handler.run();
                } catch (RuntimeException e) {
                    logger.debug("Stopping multi-exec failed: {}", e.toString());
                }
            }
            stop.setVisited(false);
        });
        getChildren().addAll(icon, text, stop);
        setAccessibleRole(AccessibleRole.TEXT);
        Tooltip.install(this, new Tooltip(I18n.get(MultiExecMarkers.STATUS_TOOLTIP_KEY)));
        show(null);
    }

    /** What <b>Stop</b> does: MainWindow stops multi-exec in every window. */
    void setOnStop(@Nullable Runnable onStop) {
        this.onStop = onStop;
    }

    /**
     * Shows how far multi-exec reaches, or hides the chip while no pane takes part.
     *
     * @param held the members the guards hold back now
     */
    void update(@NotNull MultiExecMembership.Counts counts, int held) {
        show(counts.panes() > 0 ? MultiExecMarkers.statusText(counts, held) : null);
    }

    private void show(@Nullable String status) {
        boolean visible = status != null;
        if (visible) {
            text.setText(status);
            setAccessibleText(status);
        }
        setVisible(visible);
        setManaged(visible);
    }
}
