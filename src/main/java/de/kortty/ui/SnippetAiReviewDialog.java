package de.kortty.ui;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Displays AI code-review / syntax-check findings without changing editor content. Findings are rendered
 * as a themed HTML page inside a {@link WebView} (severity pills, cards, readable typography) so the
 * window matches the security report. Supports a persisted font zoom, copy-to-clipboard, and — when a
 * re-run callback is supplied — a transient AI-profile picker plus a re-run button to repeat the check
 * with a different profile.
 */
public class SnippetAiReviewDialog extends ThemeAwareDialog<Void> {

    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_FONT_SIZE = 14;

    private final List<SnippetAiResponseSupport.CodeReviewFinding> findings;
    private final WebView findingsView = new WebView();
    private final Label fontSizeLabel = new Label();
    private int fontSize;
    private boolean pageReady;

    public SnippetAiReviewDialog(Window owner, String title, List<SnippetAiResponseSupport.CodeReviewFinding> findings) {
        this(owner, title, findings, null, null);
    }

    public SnippetAiReviewDialog(
            Window owner,
            String title,
            List<SnippetAiResponseSupport.CodeReviewFinding> findings,
            String activeProfileId,
            Consumer<String> onRerun) {

        setTitle(title != null && !title.isBlank() ? title : I18n.get("snippets.ai.review.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        // Unload the findings page on close so its WebKit engine releases its native memory.
        setOnHidden(event -> findingsView.getEngine().loadContent(""));
        this.fontSize = clampFontSize(loadPersistedFontSize());
        this.findings = sortedBySeverity(findings);

        Label infoLabel = new Label(I18n.get("snippets.ai.review.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        findingsView.setContextMenuEnabled(false);
        findingsView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                pageReady = true;
            }
        });
        findingsView.getEngine().loadContent(buildFindingsHtml());
        VBox.setVgrow(findingsView, Priority.ALWAYS);

        HBox toolbar = buildToolbar(activeProfileId, onRerun);

        VBox root = new VBox(10, infoLabel, toolbar, findingsView);
        root.setPadding(new Insets(14));

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(820);
        getDialogPane().setPrefHeight(600);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardZoom);
    }

    private HBox buildToolbar(String activeProfileId, Consumer<String> onRerun) {
        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changeFontSize(-1));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changeFontSize(1));
        updateFontSizeLabel();

        Button copyButton = new Button(I18n.get("snippets.copyClipboard"));
        copyButton.setDisable(findings.isEmpty());
        copyButton.setOnAction(event -> copyFindings(copyButton));

        Region spacer = new Region();
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        if (onRerun != null) {
            ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo =
                SnippetAiDialogSupport.buildProfileCombo(activeProfileId);
            Button rerunButton = SnippetAiDialogSupport.buildRerunButton(
                () -> SnippetAiDialogSupport.selectedProfileId(profileCombo), onRerun, this::close);
            toolbar.getChildren().addAll(SnippetAiDialogSupport.profileLabel(), profileCombo, rerunButton);
        }
        toolbar.getChildren().addAll(spacer, zoomOutButton, fontSizeLabel, zoomInButton, copyButton);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return toolbar;
    }

    private void handleKeyboardZoom(KeyEvent event) {
        if (!event.isControlDown() && !event.isShortcutDown()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changeFontSize(1);
            event.consume();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changeFontSize(-1);
            event.consume();
        }
    }

    private void changeFontSize(int delta) {
        int next = clampFontSize(fontSize + delta);
        if (next == fontSize) {
            return;
        }
        fontSize = next;
        executeIfReady("document.body.style.fontSize='" + fontSize + "px';");
        updateFontSizeLabel();
        persistFontSize();
    }

    private void executeIfReady(String script) {
        if (!pageReady) {
            return;
        }
        try {
            findingsView.getEngine().executeScript(script);
        } catch (RuntimeException ignored) {
            // A transient WebView state should never break the dialog.
        }
    }

    private void updateFontSizeLabel() {
        fontSizeLabel.setText(fontSize + "pt");
    }

    private void copyFindings(Button button) {
        StringBuilder text = new StringBuilder();
        for (SnippetAiResponseSupport.CodeReviewFinding finding : findings) {
            if (finding == null) {
                continue;
            }
            text.append(finding.id())
                .append(" [").append(finding.severity()).append("] ")
                .append(finding.title());
            if (finding.line() != null && finding.line() > 0) {
                text.append(" (").append(I18n.get("common.line")).append(' ').append(finding.line()).append(')');
            }
            text.append('\n');
            if (!finding.detail().isBlank()) {
                text.append(finding.detail()).append('\n');
            }
            if (!finding.recommendation().isBlank()) {
                text.append(I18n.get("snippets.ai.review.recommendation"))
                    .append(' ').append(finding.recommendation()).append('\n');
            }
            text.append('\n');
        }
        de.kortty.core.KorttyClipboard.setText(text.toString().strip());

        String original = I18n.get("snippets.copyClipboard");
        button.setText(I18n.get("snippets.copied"));
        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(event -> button.setText(original));
        pause.play();
    }

    // ---- HTML rendering -------------------------------------------------------------------------

    private String buildFindingsHtml() {
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String background = colors != null ? colors.backgroundColor() : SnippetAiDialogSupport.FALLBACK_BG;
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        String recommendationLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.review.recommendation"));
        String lineLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("common.line"));

        StringBuilder body = new StringBuilder();
        if (findings.isEmpty()) {
            body.append("<div class=\"empty\">")
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.review.empty")))
                .append("</div>");
        } else {
            for (SnippetAiResponseSupport.CodeReviewFinding finding : findings) {
                body.append(renderFindingCard(finding, recommendationLabel, lineLabel));
            }
        }

        return "<!doctype html><html><head><meta charset=\"UTF-8\">"
            + "<style>" + SnippetAiDialogSupport.cardCss(background, foreground, fontSize) + "</style></head>"
            + "<body>" + body + "</body></html>";
    }

    private String renderFindingCard(
            SnippetAiResponseSupport.CodeReviewFinding finding,
            String recommendationLabel,
            String lineLabel) {

        String severityClass = SnippetAiDialogSupport.severityCssClass(finding.severity());
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card\">");
        card.append("<div class=\"card-head\">");
        card.append("<span class=\"pill ").append(severityClass).append("\">")
            .append(SnippetAiDialogSupport.escapeHtml(finding.severity())).append("</span>");
        card.append("<span class=\"title\"><span class=\"finding-id\">")
            .append(SnippetAiDialogSupport.escapeHtml(finding.id())).append("</span>")
            .append(SnippetAiDialogSupport.escapeHtml(finding.title()));
        if (finding.line() != null && finding.line() > 0) {
            card.append("<span class=\"loc\">").append(lineLabel).append(' ').append(finding.line()).append("</span>");
        }
        card.append("</span></div>");
        if (!finding.detail().isBlank()) {
            card.append("<p class=\"impact\">").append(SnippetAiDialogSupport.escapeHtml(finding.detail())).append("</p>");
        }
        if (!finding.recommendation().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(recommendationLabel)
                .append("</span>").append(SnippetAiDialogSupport.escapeHtml(finding.recommendation())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static List<SnippetAiResponseSupport.CodeReviewFinding> sortedBySeverity(
            List<SnippetAiResponseSupport.CodeReviewFinding> findings) {
        if (findings == null) {
            return List.of();
        }
        return findings.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(finding -> SnippetAiDialogSupport.severityRank(finding.severity())))
            .toList();
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    private int loadPersistedFontSize() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        if (settings != null && settings.getAiReviewFontSize() != null) {
            return settings.getAiReviewFontSize();
        }
        return DEFAULT_FONT_SIZE;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiReviewFontSize(fontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }
}
