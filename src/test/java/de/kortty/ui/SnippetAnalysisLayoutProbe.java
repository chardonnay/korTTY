package de.kortty.ui;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures how a laid-out snippet editor shares its width between the code and the
 * Full-code-analysis panel, for the render smokes: the panel keeps its width (never below its
 * minimum), the code keeps its minimum, and no label of the panel toolbar is cut short.
 */
final class SnippetAnalysisLayoutProbe {

    private SnippetAnalysisLayoutProbe() {
    }

    /** One measurement; {@link #problems()} is empty when the layout is right. */
    record Measurement(double workbenchWidth, double editorWidth, double panelWidth, double panelPreferred,
                       List<String> problems) {
        @Override
        public String toString() {
            return String.format("row %.0f px: code %.0f px, panel %.0f px (wants %.0f)%s",
                workbenchWidth, editorWidth, panelWidth, panelPreferred,
                problems.isEmpty() ? "" : " — " + String.join("; ", problems));
        }
    }

    /** The editor's analysis side panel while it is shown, else {@code null}. */
    static Region panelOf(SnippetEditDialog editor) {
        Node node = editor.getDialogPane().lookup("#snippet-editor-workbench");
        return node instanceof SnippetEditorWorkbench workbench ? workbench.panel() : null;
    }

    /** Lays the editor's scene out and measures it. Call on the FX thread. */
    static Measurement measure(SnippetEditDialog editor) {
        Parent root = editor.getDialogPane().getScene().getRoot();
        root.applyCss();
        root.layout();
        List<String> problems = new ArrayList<>();
        Node node = editor.getDialogPane().lookup("#snippet-editor-workbench");
        if (!(node instanceof SnippetEditorWorkbench workbench)) {
            problems.add("the editor has no workbench row");
            return new Measurement(0, 0, 0, 0, problems);
        }
        Region panel = workbench.panel();
        if (panel == null) {
            problems.add("the analysis panel is not shown");
            return new Measurement(workbench.getWidth(), 0, 0, 0, problems);
        }
        Region editorArea = (Region) workbench.getChildren().getFirst();
        double row = workbench.getWidth();
        double code = editorArea.getWidth();
        double width = panel.getWidth();
        double preferred = panel.getPrefWidth();
        double minimum = SnippetAnalysisController.MIN_PANEL_WIDTH;
        if (width < minimum - 0.5) {
            problems.add("the panel is " + Math.round(width) + " px, below its " + Math.round(minimum) + " px minimum");
        }
        boolean room = row - SnippetEditorWorkbench.DIVIDER_WIDTH - preferred >= SnippetEditorWorkbench.MIN_EDITOR_WIDTH;
        if (room && Math.abs(width - preferred) > 1) {
            problems.add("the panel is " + Math.round(width) + " px although there is room for its "
                + Math.round(preferred) + " px");
        }
        boolean roomForCode = row - SnippetEditorWorkbench.DIVIDER_WIDTH - minimum >= SnippetEditorWorkbench.MIN_EDITOR_WIDTH;
        if (roomForCode && code < SnippetEditorWorkbench.MIN_EDITOR_WIDTH - 0.5) {
            problems.add("the code is " + Math.round(code) + " px, below its "
                + Math.round(SnippetEditorWorkbench.MIN_EDITOR_WIDTH) + " px minimum");
        }
        problems.addAll(truncatedToolbarLabels(panel));
        problems.addAll(truncatedLabels(panel, "#" + SnippetAnalysisController.HEADER_ID, "header",
            label -> !(label instanceof javafx.scene.control.ListCell<?>)));
        return new Measurement(row, code, width, preferred, problems);
    }

    /** Every visible toolbar label that shows less than its text (an ellipsis) or sticks out of the row. */
    static List<String> truncatedToolbarLabels(Region panel) {
        return truncatedLabels(panel, "#snippet-analysis-toolbar", "toolbar", node -> true);
    }

    /**
     * Every visible label of the row {@code selector} that shows less than its text or sticks out
     * of the row; labels {@code include} rejects (a chooser's cell that may elide) are skipped.
     */
    static List<String> truncatedLabels(Region panel, String selector, String rowName,
                                        java.util.function.Predicate<Labeled> include) {
        List<String> problems = new ArrayList<>();
        Node found = panel.lookup(selector);
        if (!(found instanceof Region row)) {
            problems.add("the analysis " + rowName + " is missing");
            return problems;
        }
        List<Labeled> labeled = new ArrayList<>();
        collectLabeled(row, labeled);
        labeled.removeIf(include.negate());
        if (labeled.isEmpty()) {
            problems.add("the analysis " + rowName + " has no labels");
        }
        Bounds rowBounds = row.localToScene(row.getLayoutBounds());
        for (Labeled control : labeled) {
            if (!control.isVisible() || control.getText() == null || control.getText().isEmpty()) {
                continue;
            }
            String text = control.getText();
            Node shown = control.lookup(".text");
            String displayed = shown instanceof Text textNode ? textNode.getText() : text;
            // A cut label shows its text shortened with an ellipsis; a wrapped one keeps all of it.
            if (!displayed.equals(text)) {
                problems.add(rowName + " label \"" + text + "\" is cut to \"" + displayed + "\" at "
                    + Math.round(control.getWidth()) + " px");
            }
            Bounds bounds = control.localToScene(control.getLayoutBounds());
            if (bounds.getMaxX() > rowBounds.getMaxX() + 1 || bounds.getMinX() < rowBounds.getMinX() - 1) {
                problems.add(rowName + " label \"" + text + "\" sticks out of the " + Math.round(row.getWidth())
                    + " px " + rowName);
            }
        }
        return problems;
    }

    private static void collectLabeled(Parent parent, List<Labeled> into) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Labeled control) {
                into.add(control);
            } else if (child instanceof Parent nested) {
                collectLabeled(nested, into);
            }
        }
    }
}
