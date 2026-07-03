package de.kortty.ui;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks a Snippet-Manager script plus positional parameters for a swarm-wide run. The dialog is
 * itself the single confirmation: a live summary line states script, parameter count and server
 * count, and the "Run" button validates (variable resolution, supported language) before closing —
 * blocking problems appear inline and keep the dialog open.
 */
final class SwarmSnippetRunDialog extends Dialog<SwarmSnippetRunSupport.PreparedRun> {

    private record SnippetChoice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final SnippetManager snippetManager;
    private final ComboBox<SnippetChoice> snippetCombo = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final TextArea paramsArea = new TextArea();
    private final Label summaryLabel = new Label();
    private final Label errorLabel = new Label();
    private final int targetCount;
    private SwarmSnippetRunSupport.PreparedRun prepared;

    SwarmSnippetRunDialog(
        Window owner,
        int targetCount,
        SnippetManager snippetManager,
        SnippetVariableManager variableManager) {

        this.snippetManager = snippetManager;
        this.targetCount = targetCount;
        SwarmSnippetRunSupport support = new SwarmSnippetRunSupport(snippetManager, variableManager);

        setTitle(I18n.get("ai.swarm.script.dialog.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        setOnShown(event -> {
            if (getDialogPane().getScene() != null
                && getDialogPane().getScene().getWindow() instanceof Stage stage) {
                stage.toFront();
                stage.requestFocus();
            }
        });

        ObservableList<SnippetChoice> allChoices = FXCollections.observableArrayList(buildChoices());
        FilteredList<SnippetChoice> filtered = new FilteredList<>(allChoices, choice -> true);
        snippetCombo.setItems(filtered);
        snippetCombo.setMaxWidth(Double.MAX_VALUE);
        snippetCombo.setPromptText(I18n.get("ai.swarm.script.dialog.snippetPrompt"));
        snippetCombo.setCellFactory(list -> choiceCell());
        snippetCombo.setButtonCell(choiceCell());
        searchField.setPromptText(I18n.get("ai.swarm.script.dialog.searchPrompt"));
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            String selectedId = selectedSnippetId();
            List<String> terms = searchTerms(newValue);
            filtered.setPredicate(choice -> choice != null
                && (terms.isEmpty()
                    || (selectedId != null && selectedId.equals(choice.id()))
                    || matches(choice, terms)));
        });

        paramsArea.setPrefRowCount(3);
        paramsArea.setWrapText(true);
        paramsArea.setPromptText(I18n.get("ai.swarm.script.dialog.paramsPrompt"));

        summaryLabel.setWrapText(true);
        errorLabel.setWrapText(true);
        errorLabel.setStyle("-fx-text-fill: #ef5350;");
        snippetCombo.getSelectionModel().selectedItemProperty()
            .addListener((obs, oldValue, newValue) -> refreshSummary());
        paramsArea.textProperty().addListener((obs, oldValue, newValue) -> refreshSummary());
        refreshSummary();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.add(new Label(I18n.get("ai.swarm.script.dialog.searchLabel")), 0, 0);
        grid.add(searchField, 1, 0);
        grid.add(new Label(I18n.get("ai.swarm.script.dialog.snippetLabel")), 0, 1);
        grid.add(snippetCombo, 1, 1);
        grid.add(new Label(I18n.get("ai.swarm.script.dialog.paramsLabel")), 0, 2);
        grid.add(paramsArea, 1, 2);
        grid.add(summaryLabel, 0, 3, 2, 1);
        grid.add(errorLabel, 0, 4, 2, 1);
        GridPane.setHgrow(searchField, Priority.ALWAYS);
        GridPane.setHgrow(snippetCombo, Priority.ALWAYS);
        GridPane.setHgrow(paramsArea, Priority.ALWAYS);
        getDialogPane().setContent(grid);
        getDialogPane().setPrefSize(560, 340);

        ButtonType runType = new ButtonType(I18n.get("ai.swarm.script.dialog.run"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(runType, ButtonType.CANCEL);
        Button runButton = (Button) getDialogPane().lookupButton(runType);
        runButton.disableProperty().bind(snippetCombo.getSelectionModel().selectedItemProperty().isNull());
        // Single confirmation: validate on Run; blocking problems keep the dialog open.
        runButton.addEventFilter(ActionEvent.ACTION, event -> {
            String snippetId = selectedSnippetId();
            Snippet snippet = snippetId != null
                ? snippetManager.findById(snippetId).orElse(null)
                : null;
            if (snippet == null) {
                errorLabel.setText(I18n.get("ai.swarm.script.error.noSnippet"));
                event.consume();
                return;
            }
            try {
                prepared = support.prepare(
                    snippet, SwarmSnippetRunSupport.parseArgumentLines(paramsArea.getText()));
                errorLabel.setText("");
            } catch (SwarmSnippetRunSupport.SnippetRunBlockedException e) {
                errorLabel.setText(I18n.get(e.messageKey(), e.args()));
                event.consume();
            }
        });
        setResultConverter(buttonType -> buttonType == runType ? prepared : null);
        DialogThemeHelper.applyTheme(getDialogPane());
    }

    private void refreshSummary() {
        SnippetChoice selected = snippetCombo.getSelectionModel().getSelectedItem();
        int paramCount = SwarmSnippetRunSupport.parseArgumentLines(paramsArea.getText()).size();
        summaryLabel.setText(selected != null
            ? I18n.get("ai.swarm.script.dialog.summary", selected.label(), paramCount, targetCount)
            : "");
    }

    private List<SnippetChoice> buildChoices() {
        List<SnippetChoice> choices = new ArrayList<>();
        if (snippetManager == null) {
            return choices;
        }
        for (Snippet snippet : snippetManager.getAllSnippets()) {
            if (snippet == null || snippet.getContent() == null || snippet.getContent().isBlank()) {
                continue;
            }
            String name = snippet.getName() != null && !snippet.getName().isBlank()
                ? snippet.getName().trim()
                : snippet.getId();
            String category = snippet.getCategory() != null && !snippet.getCategory().isBlank()
                ? snippet.getCategory().trim() + " / "
                : "";
            String language = snippet.getLanguage() != null && !snippet.getLanguage().isBlank()
                ? " [" + snippet.getLanguage().trim() + "]"
                : "";
            choices.add(new SnippetChoice(snippet.getId(), category + name + language));
        }
        choices.sort(Comparator.comparing(choice -> choice.label().toLowerCase(Locale.ROOT)));
        return choices;
    }

    private String selectedSnippetId() {
        SnippetChoice selected = snippetCombo.getSelectionModel().getSelectedItem();
        return selected != null ? selected.id() : null;
    }

    private static List<String> searchTerms(String query) {
        List<String> terms = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            for (String term : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!term.isBlank()) {
                    terms.add(term);
                }
            }
        }
        return terms;
    }

    private static boolean matches(SnippetChoice choice, List<String> terms) {
        String haystack = (choice.label() + " " + choice.id()).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private static ListCell<SnippetChoice> choiceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(SnippetChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        };
    }
}
