package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.Snippet;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact "add a script header" picker: a label plus a combo offering "no header" followed by every
 * saved Script-Header snippet. Unlike the workflow-script generator's chooser (which also carries the
 * per-language auto-header and a "set as default" action), this variant is deliberately minimal — the
 * code-analysis window only needs an <em>opt-in</em> header to prepend to the snippet when the user
 * applies the selected improvements. Resolution of the chosen header text is delegated to
 * {@link ScriptHeaderSupport}.
 */
final class ScriptHeaderChooser extends HBox {

    /** A combo entry: {@code snippetId == null} means "no header". */
    private record HeaderChoice(String snippetId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final ComboBox<HeaderChoice> combo = new ComboBox<>();

    ScriptHeaderChooser() {
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);

        List<HeaderChoice> choices = new ArrayList<>();
        choices.add(new HeaderChoice(null, I18n.get("snippets.ai.analysis.header.none")));
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app != null && app.getSnippetManager() != null) {
            for (Snippet header : app.getSnippetManager().getScriptHeaderSnippets()) {
                choices.add(new HeaderChoice(header.getId(),
                    header.getName() != null && !header.getName().isBlank() ? header.getName() : header.getId()));
            }
        }
        combo.getItems().setAll(choices);
        combo.setValue(choices.get(0));
        combo.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.header.tooltip")));

        Label label = new Label(I18n.get("snippets.ai.analysis.header.label"));
        getChildren().addAll(label, combo);
    }

    /** {@code true} when a real header (not "no header") is selected. */
    boolean hasSelection() {
        HeaderChoice choice = combo.getValue();
        return choice != null && choice.snippetId() != null;
    }

    /** The variable-substituted header text to inject, or {@code null} when "no header" is selected. */
    String resolveHeaderText() {
        HeaderChoice choice = combo.getValue();
        if (choice == null || choice.snippetId() == null) {
            return null;
        }
        return ScriptHeaderSupport.substitutedHeaderById(choice.snippetId());
    }
}
