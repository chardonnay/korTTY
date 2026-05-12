package de.kortty.ui;

import de.kortty.core.SnippetAiResponseSupport;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user choose which AI security findings should be fixed.
 */
public class SnippetSecurityReportDialog extends ThemeAwareDialog<List<SnippetAiResponseSupport.SecurityFinding>> {

    private static final String AI_ACTION_PREFIX = "\u2728 ";

    private final List<FindingRow> rows = new ArrayList<>();

    private record FindingRow(CheckBox checkBox, SnippetAiResponseSupport.SecurityFinding finding) {
    }

    public SnippetSecurityReportDialog(Window owner, List<SnippetAiResponseSupport.SecurityFinding> findings) {
        setTitle(I18n.get("snippets.ai.security.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        Label infoLabel = new Label(I18n.get("snippets.ai.security.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        VBox findingsBox = new VBox(10);
        findingsBox.setPadding(new Insets(4));
        if (findings == null || findings.isEmpty()) {
            findingsBox.getChildren().add(new Label(I18n.get("snippets.ai.security.empty")));
        } else {
            for (SnippetAiResponseSupport.SecurityFinding finding : findings) {
                if (finding == null) {
                    continue;
                }
                findingsBox.getChildren().add(createFindingRow(finding));
            }
        }

        ScrollPane scrollPane = new ScrollPane(findingsBox);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox root = new VBox(10, infoLabel, scrollPane);
        root.setPadding(new Insets(14));

        ButtonType applySelectedButton = new ButtonType(
            AI_ACTION_PREFIX + I18n.get("snippets.ai.security.applySelected"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applySelectedButton, ButtonType.CLOSE);
        getDialogPane().lookupButton(applySelectedButton).disableProperty().bind(
            javafx.beans.binding.Bindings.createBooleanBinding(
                () -> rows.stream().noneMatch(row -> row.checkBox().isSelected()),
                rows.stream().map(row -> row.checkBox().selectedProperty()).toArray(javafx.beans.Observable[]::new)));
        getDialogPane().setPrefWidth(820);
        getDialogPane().setPrefHeight(620);
        setResultConverter(buttonType -> buttonType == applySelectedButton ? selectedFindings() : null);
    }

    private VBox createFindingRow(SnippetAiResponseSupport.SecurityFinding finding) {
        CheckBox checkBox = new CheckBox(finding.id() + " [" + finding.severity() + "] " + finding.title());
        checkBox.setWrapText(true);
        Label impactLabel = new Label(finding.impact());
        impactLabel.setWrapText(true);
        Label recommendationLabel = new Label(I18n.get("snippets.ai.review.recommendation") + " " + finding.recommendation());
        recommendationLabel.setWrapText(true);
        recommendationLabel.setStyle("-fx-font-style: italic;");
        VBox row = new VBox(4, checkBox, impactLabel, recommendationLabel);
        row.setPadding(new Insets(8));
        row.setStyle("-fx-border-color: rgba(128,128,128,0.35); -fx-border-radius: 6; -fx-background-radius: 6;");
        rows.add(new FindingRow(checkBox, finding));
        return row;
    }

    private List<SnippetAiResponseSupport.SecurityFinding> selectedFindings() {
        List<SnippetAiResponseSupport.SecurityFinding> selected = new ArrayList<>();
        for (FindingRow row : rows) {
            if (row.checkBox().isSelected()) {
                selected.add(row.finding());
            }
        }
        return selected;
    }
}
