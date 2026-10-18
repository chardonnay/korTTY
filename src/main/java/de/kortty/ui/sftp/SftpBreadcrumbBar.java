package de.kortty.ui.sftp;

import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A panel's path as clickable folders ("/ › home › demo") over the plain path field. Clicking a
 * folder goes there; clicking the empty part of the bar, or {@link #edit()} (Cmd/Ctrl+L), shows the
 * text field to type a path; Enter goes there, Escape or leaving the field returns to the folders.
 *
 * <p>The text field stays the source of truth: whatever sets its text (a listing, a failed
 * navigation putting the old path back) redraws the folders, so the code that drives the field
 * needs no change. The field keeps its size while hidden, so the bar is as tall as before. When
 * the path is wider than the bar, its end stays visible and the start is cut off.
 */
public final class SftpBreadcrumbBar extends StackPane {

    /** One clickable folder: what it shows and the path it goes to. */
    public record Crumb(String label, String path) {
    }

    private final TextField field;
    private final Consumer<String> navigate;
    private final boolean windowsPaths;
    private final CrumbRow row = new CrumbRow();
    private boolean editing;
    /** Where the keyboard goes after Enter or Escape (the panel's list); null leaves it to JavaFX. */
    private Runnable afterEditing;
    /** The path shown before editing started; Escape and leaving the field go back to it. */
    private String shownPath = "";

    /**
     * @param field        the panel's path field, already wired to navigate on Enter
     * @param navigate     goes to a clicked folder's path
     * @param windowsPaths whether the paths are Windows paths ({@code C:\...}); server paths never are
     */
    public SftpBreadcrumbBar(TextField field, Consumer<String> navigate, boolean windowsPaths) {
        this.field = field;
        this.navigate = navigate;
        this.windowsPaths = windowsPaths;
        getStyleClass().add("sftp-breadcrumb");
        setAlignment(Pos.CENTER_LEFT);
        setMinWidth(0);
        HBox.setHgrow(this, Priority.ALWAYS);

        row.getStyleClass().add("sftp-breadcrumb-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(row.widthProperty());
        clip.heightProperty().bind(row.heightProperty());
        row.setClip(clip);
        // A click beside the folders edits the path; a folder button handles its own click.
        row.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (!(event.getTarget() instanceof Node target) || !isInsideCrumb(target)) {
                edit();
                event.consume();
            }
        });

        field.setVisible(false);
        getChildren().addAll(field, row);

        field.textProperty().addListener((obs, oldText, text) -> {
            if (!editing) {
                rebuild(text);
            }
        });
        field.addEventHandler(ActionEvent.ACTION, event -> {
            showCrumbs(false);
            returnFocus();
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && editing) {
                showCrumbs(true);
                returnFocus();
                event.consume();
            }
        });
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && editing) {
                showCrumbs(true);
            }
        });
        rebuild(field.getText());
    }

    /** Shows the text field with the whole path selected, ready to type a path. */
    public void edit() {
        if (editing) {
            field.requestFocus();
            return;
        }
        editing = true;
        shownPath = field.getText() == null ? "" : field.getText();
        row.setVisible(false);
        field.setVisible(true);
        field.requestFocus();
        field.selectAll();
    }

    /** Gives the keyboard to {@code target} after Enter or Escape, instead of the next button. */
    public void setAfterEditing(Runnable target) {
        this.afterEditing = target;
    }

    private void returnFocus() {
        if (afterEditing != null) {
            afterEditing.run();
        }
    }

    /** Whether the text field is shown instead of the folders. */
    public boolean isEditing() {
        return editing;
    }

    /** The folders shown right now; for tests. */
    public List<Crumb> shownCrumbs() {
        return crumbs(field.getText(), windowsPaths);
    }

    private void showCrumbs(boolean revert) {
        if (!editing) {
            return;
        }
        editing = false;
        if (revert) {
            field.setText(shownPath);
        }
        field.setVisible(false);
        row.setVisible(true);
        rebuild(field.getText());
    }

    private void rebuild(String path) {
        List<Node> nodes = new ArrayList<>();
        List<Crumb> crumbs = crumbs(path, windowsPaths);
        for (int index = 0; index < crumbs.size(); index++) {
            Crumb crumb = crumbs.get(index);
            if (index > 0) {
                Label separator = new Label("\u203A");
                separator.getStyleClass().add("sftp-crumb-separator");
                separator.setMinWidth(Label.USE_PREF_SIZE);
                nodes.add(separator);
            }
            Button button = new Button(crumb.label());
            button.getStyleClass().add("sftp-crumb");
            if (index == crumbs.size() - 1) {
                button.getStyleClass().add("sftp-crumb-current");
            }
            button.setMinWidth(Button.USE_PREF_SIZE);
            button.setFocusTraversable(false);
            button.setMnemonicParsing(false);
            button.setTooltip(new Tooltip(crumb.path()));
            button.setOnAction(event -> navigate.accept(crumb.path()));
            nodes.add(button);
        }
        row.getChildren().setAll(nodes);
    }

    private boolean isInsideCrumb(Node node) {
        for (Node current = node; current != null && current != row; current = current.getParent()) {
            if (current.getStyleClass().contains("sftp-crumb")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The folders of {@code path}, each with the path it stands for. A Unix path starts with
     * {@code /}; a Windows path with its drive ({@code C:\}) or its share ({@code \\server\share\}).
     * A path that is not absolute (the server's unexpanded {@code ~} before the first listing) is a
     * single folder.
     */
    public static List<Crumb> crumbs(String path, boolean windowsPaths) {
        String value = path == null ? "" : path.trim();
        List<Crumb> crumbs = new ArrayList<>();
        if (value.isEmpty()) {
            return crumbs;
        }
        String root;
        String rest;
        char separator;
        if (windowsPaths) {
            String normalized = value.replace('/', '\\');
            separator = '\\';
            if (normalized.startsWith("\\\\")) {
                int share = normalized.indexOf('\\', 2);
                int end = share < 0 ? -1 : normalized.indexOf('\\', share + 1);
                root = end < 0 ? normalized + "\\" : normalized.substring(0, end + 1);
                rest = end < 0 ? "" : normalized.substring(end + 1);
            } else if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
                root = normalized.substring(0, 2) + "\\";
                rest = normalized.length() > 3 ? normalized.substring(3) : "";
            } else {
                crumbs.add(new Crumb(normalized, normalized));
                return crumbs;
            }
        } else {
            if (!value.startsWith("/")) {
                crumbs.add(new Crumb(value, value));
                return crumbs;
            }
            separator = '/';
            root = "/";
            rest = value.substring(1);
        }
        crumbs.add(new Crumb(root, root));
        StringBuilder current = new StringBuilder(root);
        for (String part : rest.split(java.util.regex.Pattern.quote(String.valueOf(separator)))) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.charAt(current.length() - 1) != separator) {
                current.append(separator);
            }
            current.append(part);
            crumbs.add(new Crumb(part, current.toString()));
        }
        return crumbs;
    }

    /**
     * The row of folders. When they are wider than the bar, they are laid out from the right, so
     * the current folder stays visible and the start of the path is cut off by the clip.
     */
    private static final class CrumbRow extends HBox {
        @Override
        protected void layoutChildren() {
            double wanted = snappedLeftInset() + snappedRightInset();
            for (Node child : getChildren()) {
                wanted += child.prefWidth(-1);
            }
            wanted += Math.max(0, getChildren().size() - 1) * getSpacing();
            Pos alignment = wanted > getWidth() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT;
            if (getAlignment() != alignment) {
                setAlignment(alignment);
            }
            super.layoutChildren();
        }
    }
}
