package de.kortty.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SnippetEditorProfile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
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

import java.util.List;

/**
 * Shows the original and AI-generated replacement before applying an editor change. Uses Monaco's
 * side-by-side diff editor so changed regions are highlighted automatically; the security-fix flow can
 * additionally attach per-change hover explanations via {@link #setChangeExplanations(List)}, rendered
 * both as Monaco hover markers and as a themed HTML card panel below the diff.
 */
public class SnippetAiDiffDialog extends ThemeAwareDialog<Boolean> {

    private static final int MIN_PREVIEW_FONT_SIZE = 8;
    private static final int MAX_PREVIEW_FONT_SIZE = 72;
    private static final int PREVIEW_FONT_STEP = 1;
    private static final String ACCENT = "#3b82f6";
    private static final String FALLBACK_BG = "#1e1e1e";
    private static final String FALLBACK_FG = "#d6d6d6";
    private static final List<String> HIGHLIGHT_LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "groovy",
        "powershell", "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "dockerfile");

    private final MonacoDiffPane diffPane;
    private final ComboBox<String> syntaxCombo;
    private final Label fontSizeLabel;
    private final WebView explanationsView;
    private final HBox toolbar;
    private final String originalText;
    private final String replacementText;
    private final EditorSettingsHelper.Settings previewSettings;
    private int fontSize;
    private String lastReasonsJson;

    public SnippetAiDiffDialog(Window owner, String title, String summary, String originalText, String replacementText) {
        this(owner, title, summary, originalText, replacementText, null, EditorSettingsHelper.loadSnippetSettings(), null);
    }

    public SnippetAiDiffDialog(
        Window owner,
        String title,
        String summary,
        String originalText,
        String replacementText,
        String snippetLanguage,
        EditorSettingsHelper.Settings editorSettings,
        SnippetEditorProfile editorProfile) {

        this.previewSettings = editorSettings != null ? editorSettings : EditorSettingsHelper.loadSnippetSettings();
        this.originalText = originalText != null ? originalText : "";
        this.replacementText = replacementText != null ? replacementText : "";
        this.fontSize = clampFontSize(loadPersistedFontSize(previewSettings.fontSize()));

        setTitle(title != null && !title.isBlank() ? title : I18n.get("snippets.ai.diff.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        HBox summaryBanner = buildSummaryBanner(summary);

        String detectedLanguage = SnippetLanguageSupport.detectSnippetLanguage(
            snippetLanguage,
            nonBlank(this.replacementText) ? this.replacementText : this.originalText);
        syntaxCombo = new ComboBox<>();
        syntaxCombo.getItems().addAll(HIGHLIGHT_LANGUAGES);
        syntaxCombo.setValue(HIGHLIGHT_LANGUAGES.contains(detectedLanguage) ? detectedLanguage : "plain");
        syntaxCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyComparison());

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changePreviewFontSize(-PREVIEW_FONT_STEP));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changePreviewFontSize(PREVIEW_FONT_STEP));
        fontSizeLabel = new Label();
        updateFontSizeLabel();

        Button copyButton = new Button("⧉");
        copyButton.setTooltip(new Tooltip(I18n.get("snippets.copyClipboard")));
        copyButton.setOnAction(event -> copyReplacementText());

        Region spacer = new Region();
        toolbar = new HBox(
            8,
            new Label(I18n.get("snippets.ai.diff.syntax")),
            syntaxCombo,
            spacer,
            zoomOutButton,
            fontSizeLabel,
            zoomInButton,
            copyButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        diffPane = new MonacoDiffPane();
        diffPane.setThemeColors(previewSettings.foregroundColor(), previewSettings.backgroundColor());
        diffPane.setStyle("-fx-background-color: " + previewSettings.backgroundColor() + ";");
        applyFont();
        applyComparison();

        explanationsView = new WebView();
        explanationsView.setContextMenuEnabled(false);
        explanationsView.setManaged(false);
        explanationsView.setVisible(false);
        explanationsView.setPrefHeight(132);
        explanationsView.setMinHeight(60);
        explanationsView.setMaxHeight(210);

        VBox root = new VBox(10, summaryBanner, toolbar, diffPane, explanationsView);
        root.setPadding(new Insets(14));
        VBox.setVgrow(diffPane, Priority.ALWAYS);

        ButtonType applyButton = new ButtonType(I18n.get("snippets.ai.diff.apply"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(1040);
        getDialogPane().setPrefHeight(700);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardShortcut);
        setOnHidden(event -> {
            diffPane.dispose();
            explanationsView.getEngine().loadContent("");
        });
        setResultConverter(buttonType -> buttonType == applyButton);
    }

    /**
     * Attaches per-change explanations (security-fix flow): hover annotations on the modified side plus
     * a themed HTML card panel below the diff, so the "why" is understandable even if a hover anchor
     * does not match.
     */
    public void setChangeExplanations(List<SnippetAiResponseSupport.SecurityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        lastReasonsJson = toReasonsJson(changes);
        diffPane.setChangeReasons(lastReasonsJson);

        String html = buildExplanationsHtml(changes);
        if (html == null) {
            return;
        }
        explanationsView.getEngine().loadContent(html);
        explanationsView.setManaged(true);
        explanationsView.setVisible(true);
    }

    /**
     * Enables the transient AI-profile picker and a re-run button so this adjustment can be repeated
     * with a different profile. No-op when {@code onRerun} is {@code null} (e.g. the security-fix flow
     * that manages re-runs from its findings window instead).
     */
    public void setRerunHandler(String activeProfileId, java.util.function.Consumer<String> onRerun) {
        if (onRerun == null) {
            return;
        }
        ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo =
            SnippetAiDialogSupport.buildProfileCombo(activeProfileId);
        Button rerunButton = SnippetAiDialogSupport.buildRerunButton(
            () -> SnippetAiDialogSupport.selectedProfileId(profileCombo), onRerun, this::close);
        toolbar.getChildren().addAll(0, List.of(
            SnippetAiDialogSupport.profileLabel(), profileCombo, rerunButton));
    }

    private HBox buildSummaryBanner(String summary) {
        Region accentBar = new Region();
        accentBar.setMinWidth(3);
        accentBar.setStyle("-fx-background-color: " + ACCENT + "; -fx-background-radius: 3;");

        Label summaryLabel = new Label(summary != null && !summary.isBlank()
            ? summary
            : I18n.get("snippets.ai.diff.summary.empty"));
        summaryLabel.setWrapText(true);
        summaryLabel.setStyle("-fx-font-size: 12.5px;");
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);

        HBox banner = new HBox(10, accentBar, summaryLabel);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setStyle("-fx-background-color: rgba(127,127,127,0.09); -fx-background-radius: 8; -fx-padding: 10 12 10 12;");
        return banner;
    }

    private void applyComparison() {
        String language = syntaxCombo.getValue();
        diffPane.setComparison(originalText, replacementText, language, language);
        // setComparison clears prior decorations, so re-apply any reasons after re-highlighting.
        if (lastReasonsJson != null) {
            diffPane.setChangeReasons(lastReasonsJson);
        }
    }

    private void applyFont() {
        diffPane.setFont(previewSettings.fontFamily(), fontSize);
    }

    private void handleKeyboardShortcut(KeyEvent event) {
        if (!event.isShortcutDown() && !event.isControlDown()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changePreviewFontSize(PREVIEW_FONT_STEP);
            event.consume();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changePreviewFontSize(-PREVIEW_FONT_STEP);
            event.consume();
        }
    }

    private void changePreviewFontSize(int delta) {
        int next = clampFontSize(fontSize + delta);
        if (next == fontSize) {
            updateFontSizeLabel();
            return;
        }
        fontSize = next;
        applyFont();
        persistFontSize();
        updateFontSizeLabel();
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(fontSize + "pt");
        }
    }

    private void copyReplacementText() {
        ClipboardContent content = new ClipboardContent();
        content.putString(replacementText);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ---- Explanations panel (themed HTML) -------------------------------------------------------

    private String buildExplanationsHtml(List<SnippetAiResponseSupport.SecurityChange> changes) {
        StringBuilder items = new StringBuilder();
        int count = 0;
        for (SnippetAiResponseSupport.SecurityChange change : changes) {
            if (change == null || change.reason().isBlank()) {
                continue;
            }
            count++;
            String badge = !change.finding().isBlank() ? escapeHtml(change.finding()) : "•";
            items.append("<div class=\"item\"><span class=\"badge\">").append(badge).append("</span>")
                .append("<span class=\"reason\">").append(escapeHtml(change.reason())).append("</span></div>");
        }
        if (count == 0) {
            return null;
        }

        ThemeCssSupport.ThemeColors colors = resolveThemeColors();
        String background = colors != null ? colors.backgroundColor() : FALLBACK_BG;
        String foreground = colors != null ? colors.foregroundColor() : FALLBACK_FG;
        String header = escapeHtml(I18n.get("snippets.ai.diff.reasons.title"));

        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
            + ":root{color-scheme:dark;}*{box-sizing:border-box;}"
            + "body{margin:0;padding:12px 14px;background:" + background + ";color:" + foreground + ";"
            + "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12.5px;line-height:1.45;}"
            + ".head{font-weight:700;opacity:.9;margin:0 0 8px;display:flex;align-items:center;gap:7px;}"
            + ".head .dot{width:7px;height:7px;border-radius:50%;background:" + ACCENT + ";display:inline-block;}"
            + ".item{display:flex;gap:9px;align-items:flex-start;padding:8px 10px;margin-bottom:7px;border-radius:8px;"
            + "background:rgba(127,127,127,0.08);border-left:3px solid " + ACCENT + ";}"
            + ".badge{flex:0 0 auto;font-family:'SF Mono',Menlo,Consolas,monospace;font-weight:700;font-size:0.82em;"
            + "padding:1px 8px;border-radius:6px;background:" + ACCENT + ";color:#fff;}"
            + ".reason{opacity:.96;}"
            + "</style></head><body>"
            + "<div class=\"head\"><span class=\"dot\"></span>" + header + "</div>"
            + items
            + "</body></html>";
    }

    private ThemeCssSupport.ThemeColors resolveThemeColors() {
        try {
            return ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String toReasonsJson(List<SnippetAiResponseSupport.SecurityChange> changes) {
        JsonArray array = new JsonArray();
        for (SnippetAiResponseSupport.SecurityChange change : changes) {
            if (change == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("finding", change.finding());
            object.addProperty("anchor", change.anchor());
            object.addProperty("reason", change.reason());
            array.add(object);
        }
        return array.toString();
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_PREVIEW_FONT_SIZE, Math.min(MAX_PREVIEW_FONT_SIZE, size));
    }

    private static int loadPersistedFontSize(int fallback) {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings != null && settings.getAiDiffFontSize() != null) {
                return settings.getAiDiffFontSize();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiDiffFontSize(fontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean nonBlank(String text) {
        return text != null && !text.isBlank();
    }
}
