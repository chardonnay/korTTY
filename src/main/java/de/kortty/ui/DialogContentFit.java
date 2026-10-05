package de.kortty.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Cell;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens a dialog large enough that its labels are readable.
 *
 * <p>A dialog without a remembered geometry — every tool window right after a fresh install, and
 * every window again after a UI font scale change — opens at the size its code hard-wires. Those
 * sizes were picked against the English labels at 100 % scale, so German labels or a larger font
 * came out truncated ("Verbindungs…") until the user found out the window can be resized.</p>
 *
 * <p>Just before such a dialog is first sized, this lays the pane out at its preferred size, looks
 * for labels, buttons and check boxes whose text the skin had to cut short, and widens (or
 * heightens) the pane by what is missing, a few rounds until nothing is cut or the screen is full.
 * Everything happens before the window is mapped, so the dialog simply appears at the right size —
 * no visible jump. A remembered geometry is the user's choice and is never second-guessed.</p>
 */
final class DialogContentFit {

    /** Set on the dialog pane by whoever already gave the dialog its size (a restored geometry). */
    static final String SIZE_RESTORED_KEY = "kortty.dialogContentFit.sizeRestored";

    /** Prevents installing the handler twice when the theme is applied repeatedly. */
    private static final String INSTALLED_KEY = "kortty.dialogContentFit.installed";

    /**
     * A node carrying this property (any value) is skipped together with its subtree — for text that
     * is meant to be cut short, such as a path or a status line.
     */
    static final String IGNORE_KEY = "kortty.dialogContentFit.ignore";

    /** How many growing steps at most; two or three settle any real dialog. */
    private static final int MAX_ROUNDS = 8;

    /** How many bisection steps narrow a grown size back down. */
    private static final int BISECT_ROUNDS = 8;

    /** Share of the screen a fitted dialog may take, leaving room for the window frame. */
    private static final double SCREEN_SHARE = 0.92;

    /** Below this a measured shortfall is rounding noise, not a cut label. */
    private static final double EPSILON = 0.5;

    /** Extra room per round, so sub-pixel text metrics do not need another round. */
    private static final double SLACK = 4;

    private DialogContentFit() {
    }

    /** A cut label: how much wider/taller it wants to be, and whether a bigger window can help. */
    record Shortfall(String text, double width, double height, boolean growable) {
    }

    static void install(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        DialogPane pane = dialog.getDialogPane();
        if (Boolean.TRUE.equals(pane.getProperties().put(INSTALLED_KEY, Boolean.TRUE))) {
            return;
        }
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event -> {
            // An explicit window size (a restored geometry) means show() does not size to the scene,
            // so the pane's preferred size would not matter anyway.
            if (!Double.isNaN(dialog.getWidth()) || !Double.isNaN(dialog.getHeight())
                || Boolean.TRUE.equals(pane.getProperties().get(SIZE_RESTORED_KEY))
                || (dialog instanceof ThemeAwareDialog<?> themed && themed.isHostedInTab())) {
                return;
            }
            Rectangle2D screen = screenFor(dialog.getOwner());
            if (screen != null) {
                fit(pane, screen.getWidth() * SCREEN_SHARE, screen.getHeight() * SCREEN_SHARE);
            }
        });
    }

    /**
     * Grows the pane's preferred size until no growable label is cut short, never beyond
     * {@code maxWidth} × {@code maxHeight}. The pane must be in a scene.
     *
     * <p>A label's own shortfall understates what the window needs: a grid or box shares any extra
     * width among all its columns. So the width is first grown in steps that double the shortfall
     * until nothing is cut, then narrowed back by bisection to the smallest width that still shows
     * every label; then the same for the height. Growing stops as soon as it stops helping —
     * a label held by a fixed size elsewhere would otherwise blow the dialog up to the screen.</p>
     */
    static void fit(DialogPane pane, double maxWidth, double maxHeight) {
        if (pane.getScene() == null) {
            return;
        }
        pane.applyCss();
        double startWidth = pane.prefWidth(-1);
        double startHeight = pane.prefHeight(startWidth);
        double width = grow(pane, startWidth, startHeight, maxWidth, true);
        double height = grow(pane, width, startHeight, maxHeight, false);
        if (width > startWidth + EPSILON) {
            pane.setPrefWidth(Math.ceil(width));
        }
        if (height > startHeight + EPSILON) {
            pane.setPrefHeight(Math.ceil(height));
        }
    }

    /** The smallest width (or height) from {@code start} up to {@code max} that cuts no label. */
    private static double grow(DialogPane pane, double width, double height, double max, boolean horizontal) {
        double start = horizontal ? width : height;
        double good = start;
        double missing = missing(pane, width, height, horizontal);
        if (missing <= EPSILON || start >= max) {
            return start;
        }
        double bad = start;
        double bestMissing = missing;
        double candidate = start;
        boolean fits = false;
        for (int round = 0; round < MAX_ROUNDS && candidate < max; round++) {
            candidate = Math.min(max, candidate + 2 * missing + SLACK);
            double next = horizontal ? missing(pane, candidate, height, true) : missing(pane, width, candidate, false);
            if (next <= EPSILON) {
                good = candidate;
                fits = true;
                break;
            }
            if (next >= bestMissing - EPSILON) {
                // More room changed nothing: what is cut is held by a fixed size, not by the window.
                return bad;
            }
            bestMissing = next;
            bad = candidate;
            good = candidate;
            missing = next;
        }
        if (!fits) {
            return good;
        }
        // Narrow back to the smallest size that still fits, to within a few pixels.
        for (int round = 0; round < BISECT_ROUNDS && good - bad > SLACK; round++) {
            double middle = (good + bad) / 2;
            double next = horizontal ? missing(pane, middle, height, true) : missing(pane, width, middle, false);
            if (next <= EPSILON) {
                good = middle;
            } else {
                bad = middle;
            }
        }
        return good;
    }

    /** Lays the pane out at the given size and returns the largest growable shortfall along one axis. */
    private static double missing(DialogPane pane, double width, double height, boolean horizontal) {
        pane.resize(width, height);
        pane.layout();
        // A scroll pane decides on its scroll bars in the first pass and gives the bar's width away
        // only in the next one; measuring after one pass misses labels the bar cuts.
        pane.layout();
        double missing = 0;
        for (Shortfall shortfall : shortfalls(pane)) {
            if (shortfall.growable()) {
                missing = Math.max(missing, horizontal ? shortfall.width() : shortfall.height());
            }
        }
        return missing;
    }

    /** Every visible label under {@code root} whose text is currently cut short. */
    static List<Shortfall> shortfalls(Parent root) {
        List<Shortfall> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Node node, List<Shortfall> result) {
        if (!node.isVisible() || node.getProperties().containsKey(IGNORE_KEY)) {
            return;
        }
        // Rows and cells are sized by their virtualised container (and cut on purpose, with column
        // widths the user drags); a combo box's button cell shows whatever the item says.
        if (node instanceof Cell<?> || node instanceof ListView<?> || node instanceof TreeView<?>
            || node instanceof TableView<?> || node instanceof TreeTableView<?>
            || node instanceof ComboBoxBase<?>) {
            return;
        }
        if (node instanceof Labeled labeled) {
            Shortfall shortfall = shortfallOf(labeled);
            if (shortfall != null) {
                result.add(shortfall);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, result);
            }
        }
    }

    /**
     * How much a label lacks, read off what its skin actually draws: a cut text is drawn with the
     * ellipsis in it. {@code null} when the label shows its whole text.
     */
    static Shortfall shortfallOf(Labeled labeled) {
        String full = labeled.getText();
        if (full == null || full.isBlank() || labeled.isWrapText()) {
            return null;
        }
        Text drawn = drawnText(labeled);
        if (drawn == null) {
            return null;
        }
        String shown = drawn.getText();
        if (shown == null || shown.equals(full) || shown.equals(withoutMnemonic(full))) {
            return null;
        }
        String ellipsis = labeled.getEllipsisString();
        boolean cut = shown.isEmpty() || (ellipsis != null && !ellipsis.isEmpty() && shown.contains(ellipsis));
        if (!cut) {
            return null;
        }
        Text probe = new Text(labeled.isMnemonicParsing() ? withoutMnemonic(full) : full);
        probe.setFont(labeled.getFont());
        double wantedWidth = Math.ceil(probe.getLayoutBounds().getWidth());
        double drawnWidth = shown.isEmpty() ? 0 : drawn.getLayoutBounds().getWidth();
        double missingWidth = Math.max(0, wantedWidth - drawnWidth);
        double missingHeight = Math.max(0, labeled.prefHeight(-1) - labeled.getHeight());
        // Missing height only matters when the label is squeezed vertically, which also cuts it.
        if (missingHeight > EPSILON && missingWidth <= EPSILON) {
            missingWidth = 0;
        }
        return new Shortfall(full, missingWidth, missingHeight > EPSILON ? missingHeight : 0, growable(labeled));
    }

    /**
     * Whether a bigger window can give this label more room: not when the label itself (or its
     * direct parent row) has been given a fixed width that is the very reason for the cut.
     */
    private static boolean growable(Labeled labeled) {
        if (labeled.getPrefWidth() != Region.USE_COMPUTED_SIZE && labeled.getPrefWidth() >= 0) {
            return false;
        }
        return labeled.getMaxWidth() == Region.USE_COMPUTED_SIZE
            || labeled.getMaxWidth() == Double.MAX_VALUE
            || labeled.getMaxWidth() > labeled.getWidth() + EPSILON;
    }

    private static Text drawnText(Labeled labeled) {
        for (Node child : labeled.getChildrenUnmodifiable()) {
            if (child instanceof Text text) {
                return text;
            }
        }
        return null;
    }

    private static String withoutMnemonic(String text) {
        int index = text.indexOf('_');
        if (index < 0 || index == text.length() - 1) {
            return text;
        }
        return text.substring(0, index) + text.substring(index + 1);
    }

    /** The usable area of the screen the owner sits on (primary without one); null without a toolkit. */
    private static Rectangle2D screenFor(Window owner) {
        try {
            if (owner != null && !Double.isNaN(owner.getX()) && owner.getWidth() > 0) {
                List<Screen> screens = Screen.getScreensForRectangle(
                    owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
                if (!screens.isEmpty()) {
                    return screens.get(0).getVisualBounds();
                }
            }
            return Screen.getPrimary().getVisualBounds();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
