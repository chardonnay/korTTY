package de.kortty.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.util.List;

/**
 * The "AI processing" window: a narrow, non-modal companion of the snippet editor that hosts the
 * progress view of a running Full-code-analysis apply, so the checklist stays in sight beside the
 * editor instead of sharing the analysis panel with the report.
 *
 * <p>It only hosts the view — the controller owns the {@link SnippetAiApplyProgressPane}, moves it
 * in here and takes it back into the panel once the window hides. Closing the window never
 * disposes the view or stops the run. The first time it opens beside the editor's right edge;
 * after that it remembers its size and position like every korTTY dialog.</p>
 */
final class SnippetAiApplyProgressWindow extends ThemeAwareDialog<Void> {

    static final String PANE_ID = "snippet-ai-progress-window";
    static final double DEFAULT_WIDTH = 400;
    static final double GAP = 8;

    private final VBox root = new VBox();
    private SnippetAiApplyProgressPane pane;

    SnippetAiApplyProgressWindow(Window owner) {
        setTitle(I18n.get("snippets.ai.analysis.progress.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        getDialogPane().setId(PANE_ID);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(DEFAULT_WIDTH, owner != null ? Math.max(480, owner.getHeight()) : 640);
        setResultConverter(buttonType -> null);
        if (owner != null) {
            Rectangle2D placed = besideOwner(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight(),
                DEFAULT_WIDTH, screenBounds(owner));
            setX(placed.getMinX());
            setY(placed.getMinY());
        }
    }

    /** Shows {@code view} in the window (taking it out of wherever it was) and opens the window. */
    void showPane(SnippetAiApplyProgressPane view) {
        if (view == null) {
            return;
        }
        if (pane != view) {
            if (view.getParent() instanceof javafx.scene.layout.Pane parent && parent != root) {
                parent.getChildren().remove(view);
            }
            pane = view;
            VBox.setVgrow(view, Priority.ALWAYS);
            root.getChildren().setAll(view);
        }
        if (isShowing()) {
            revealDialogOrHost();
        } else {
            show();
        }
    }

    /** The progress view the window hosts, or {@code null}. */
    SnippetAiApplyProgressPane pane() {
        return pane;
    }

    /** Hands the view back (the window keeps nothing): the caller re-parents it. */
    SnippetAiApplyProgressPane releasePane() {
        SnippetAiApplyProgressPane released = pane;
        pane = null;
        root.getChildren().clear();
        return released;
    }

    /**
     * Where the window opens the first time: right of the editor window when that fits on the
     * screen, else at the screen's right edge (over the editor's right side); as tall as the editor,
     * within the screen.
     */
    static Rectangle2D besideOwner(double ownerX, double ownerY, double ownerWidth, double ownerHeight,
                                   double width, Rectangle2D screen) {
        double x = ownerX + ownerWidth + GAP;
        if (screen != null && x + width > screen.getMaxX()) {
            x = Math.max(screen.getMinX(), screen.getMaxX() - width);
        }
        double y = ownerY;
        double height = ownerHeight;
        if (screen != null) {
            y = Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - 200));
            height = Math.min(height, screen.getMaxY() - y);
        }
        return new Rectangle2D(x, y, width, Math.max(0, height));
    }

    private static Rectangle2D screenBounds(Window owner) {
        List<Screen> screens = Screen.getScreensForRectangle(
            owner.getX(), owner.getY(), Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()));
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }
}
