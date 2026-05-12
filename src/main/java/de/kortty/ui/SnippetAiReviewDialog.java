package de.kortty.ui;

import de.kortty.core.SnippetAiResponseSupport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Displays AI review findings without changing editor content.
 */
public class SnippetAiReviewDialog extends ThemeAwareDialog<Void> {

    private static final int DEFAULT_REPORT_FONT_SIZE = 14;
    private static final int MIN_REPORT_FONT_SIZE = 8;
    private static final int MAX_REPORT_FONT_SIZE = 32;
    private static final int REPORT_FONT_STEP = 1;

    private final TextArea reportArea;
    private final Label fontSizeLabel;
    private int reportFontSize = DEFAULT_REPORT_FONT_SIZE;

    public SnippetAiReviewDialog(Window owner, String title, List<SnippetAiResponseSupport.CodeReviewFinding> findings) {
        setTitle(title != null && !title.isBlank() ? title : I18n.get("snippets.ai.review.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        Label infoLabel = new Label(I18n.get("snippets.ai.review.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        reportArea = new TextArea(formatFindings(findings));
        reportArea.setEditable(false);
        reportArea.setWrapText(true);
        applyReportFontSize();
        VBox.setVgrow(reportArea, Priority.ALWAYS);

        Button zoomOutButton = new Button("A-");
        zoomOutButton.setOnAction(event -> changeReportFontSize(-REPORT_FONT_STEP));
        Button zoomInButton = new Button("A+");
        zoomInButton.setOnAction(event -> changeReportFontSize(REPORT_FONT_STEP));
        fontSizeLabel = new Label();
        updateFontSizeLabel();
        HBox zoomBar = new HBox(8, zoomOutButton, fontSizeLabel, zoomInButton);
        zoomBar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, infoLabel, zoomBar, reportArea);
        root.setPadding(new Insets(14));

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(780);
        getDialogPane().setPrefHeight(560);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardZoom);
    }

    private void handleKeyboardZoom(KeyEvent event) {
        if (!event.isControlDown() && !event.isShortcutDown()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changeReportFontSize(REPORT_FONT_STEP);
            event.consume();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changeReportFontSize(-REPORT_FONT_STEP);
            event.consume();
        }
    }

    private void changeReportFontSize(int delta) {
        int nextSize = Math.max(MIN_REPORT_FONT_SIZE, Math.min(MAX_REPORT_FONT_SIZE, reportFontSize + delta));
        if (nextSize == reportFontSize) {
            return;
        }
        reportFontSize = nextSize;
        applyReportFontSize();
        updateFontSizeLabel();
    }

    private void applyReportFontSize() {
        reportArea.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: " + reportFontSize + "px;");
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(reportFontSize + "pt");
        }
    }

    private String formatFindings(List<SnippetAiResponseSupport.CodeReviewFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return I18n.get("snippets.ai.review.empty");
        }
        StringBuilder builder = new StringBuilder();
        for (SnippetAiResponseSupport.CodeReviewFinding finding : findings) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(finding.id()).append(" [").append(finding.severity()).append("] ")
                .append(finding.title());
            if (finding.line() != null && finding.line() > 0) {
                builder.append(" (").append(I18n.get("common.line")).append(' ').append(finding.line()).append(')');
            }
            if (!finding.detail().isBlank()) {
                builder.append("\n").append(finding.detail());
            }
            if (!finding.recommendation().isBlank()) {
                builder.append("\n\n").append(I18n.get("snippets.ai.review.recommendation")).append(" ")
                    .append(finding.recommendation());
            }
        }
        return builder.toString();
    }
}
