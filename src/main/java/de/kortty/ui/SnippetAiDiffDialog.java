package de.kortty.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SnippetEditorProfile;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
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
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private static final double SUMMARY_MIN_HEIGHT = 52;
    private static final double SUMMARY_PREF_HEIGHT = 116;
    /** A staged apply's summary can run to a dozen paragraphs; never let it take the diff's room. */
    private static final double SUMMARY_MAX_SHARE = 0.34;
    /** Governs until the window is shown and {@link #fitSummaryHeight()} measures the real content. */
    private static final double SUMMARY_INITIAL_SPLIT = 0.16;
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
    private final HBox findingFilterBar;
    private final SplitPane summarySplit;
    private final Region summaryContent;
    private ComboBox<String> findingFilterCombo;
    private String activeFindingFilter;
    private final String originalText;
    private final String replacementText;
    private final EditorSettingsHelper.Settings previewSettings;
    private int fontSize;
    private String lastReasonsJson;
    private List<SnippetAiResponseSupport.SecurityChange> lastChanges;
    private Map<Integer, int[]> lastReasonRanges = Map.of();

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

        ScrollPane summaryBanner = buildSummaryBanner(summary);
        summaryContent = (Region) summaryBanner.getContent();
        findingFilterBar = buildFindingFilterBar();

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

        VBox reviewArea = new VBox(10, toolbar, diffPane, findingFilterBar, explanationsView);
        VBox.setVgrow(diffPane, Priority.ALWAYS);
        // Split so the summary's share of the window is the reviewer's choice: a staged apply writes
        // one paragraph per stage, a single-stage fix one line.
        summarySplit = new SplitPane(summaryBanner, reviewArea);
        summarySplit.setOrientation(Orientation.VERTICAL);
        summarySplit.setDividerPositions(SUMMARY_INITIAL_SPLIT);
        SplitPane.setResizableWithParent(summaryBanner, Boolean.FALSE);
        summarySplit.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        VBox root = new VBox(10, summarySplit);
        root.setPadding(new Insets(14));
        VBox.setVgrow(summarySplit, Priority.ALWAYS);

        ButtonType applyButton = new ButtonType(I18n.get("snippets.ai.diff.apply"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(1040);
        getDialogPane().setPrefHeight(700);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardShortcut);
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
            diffPane.dispose();
            explanationsView.getEngine().loadContent("");
        });
        addEventHandler(DialogEvent.DIALOG_SHOWN, event -> Platform.runLater(() -> {
            bringToFront();
            fitSummaryHeight();
        }));
        setResultConverter(buttonType -> buttonType == applyButton);
    }

    /**
     * Starts the divider at the summary's own height instead of a fixed share: a one-line security-fix
     * summary gets a strip, a staged apply's multi-paragraph summary gets up to a third of the window
     * and scrolls beyond that. The reviewer can drag it either way afterwards.
     */
    private void fitSummaryHeight() {
        double available = summarySplit.getHeight();
        if (available <= 0) {
            return;
        }
        double width = summaryContent.getWidth() > 0 ? summaryContent.getWidth() : getDialogPane().getWidth();
        double needed = summaryContent.prefHeight(width) + 4;
        double target = Math.min(Math.max(needed, SUMMARY_MIN_HEIGHT), available * SUMMARY_MAX_SHARE);
        summarySplit.setDividerPositions(target / available);
    }

    /** Keeps a newly opened review window above its non-modal editor/analysis window on macOS. */
    private void bringToFront() {
        Window window = getDialogPane().getScene() != null
            ? getDialogPane().getScene().getWindow()
            : null;
        if (window == null || !window.isShowing()) {
            return;
        }
        if (window instanceof Stage stage) {
            stage.toFront();
        }
        window.requestFocus();
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
        lastChanges = changes;
        lastReasonsJson = toReasonsJson(changes);
        populateFindingFilter(changes);
        // The diff host reports each reason's resolved line range (modified side) once Monaco has
        // computed the diff; re-render the cards so they carry a "Lines 23-40" chip.
        diffPane.setChangeReasonRangesHandler(this::applyReasonRanges);
        diffPane.setChangeReasons(lastReasonsJson);
        renderExplanations();
    }

    private void populateFindingFilter(List<SnippetAiResponseSupport.SecurityChange> changes) {
        List<String> choices = findingFilterChoices(changes);
        if (choices.size() < 2) {
            return;
        }
        String allLabel = I18n.get("snippets.ai.diff.focus.all");
        findingFilterCombo.getItems().setAll(allLabel);
        findingFilterCombo.getItems().addAll(choices);
        findingFilterCombo.setValue(allLabel);
        findingFilterBar.setManaged(true);
        findingFilterBar.setVisible(true);
    }

    private void renderExplanations() {
        if (lastChanges == null) {
            return;
        }
        String html = buildExplanationsHtml(lastChanges, lastReasonRanges);
        if (html == null) {
            return;
        }
        explanationsView.getEngine().loadContent(html);
        explanationsView.setManaged(true);
        explanationsView.setVisible(true);
    }

    /** Consumes the {@code [{idx,start,end}]} ranges reported by the diff host (see MonacoDiffPane). */
    private void applyReasonRanges(String rangesJson) {
        Map<Integer, int[]> ranges = new HashMap<>();
        try {
            JsonElement parsed = JsonParser.parseString(rangesJson != null ? rangesJson : "[]");
            if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("idx") && object.has("start") && object.has("end")) {
                        ranges.put(object.get("idx").getAsInt(),
                            new int[]{object.get("start").getAsInt(), object.get("end").getAsInt()});
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return; // Malformed range report: keep the cards without line chips.
        }
        if (Objects.equals(rangesToKey(ranges), rangesToKey(lastReasonRanges))) {
            return; // Monaco re-applies decorations on every diff update; avoid redundant reloads.
        }
        lastReasonRanges = ranges;
        renderExplanations();
    }

    private static String rangesToKey(Map<Integer, int[]> ranges) {
        StringBuilder key = new StringBuilder();
        ranges.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> key.append(entry.getKey()).append(':')
                .append(entry.getValue()[0]).append('-').append(entry.getValue()[1]).append(';'));
        return key.toString();
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

    /**
     * The summary of a staged apply concatenates one paragraph per stage and can outgrow the window,
     * so it scrolls inside its own pane and the split divider below it lets the reviewer trade its
     * height against the diff.
     */
    private ScrollPane buildSummaryBanner(String summary) {
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
        banner.setAlignment(Pos.TOP_LEFT);
        banner.setStyle("-fx-background-color: rgba(127,127,127,0.09); -fx-background-radius: 8; -fx-padding: 10 12 10 12;");

        ScrollPane scroll = new ScrollPane(banner);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinHeight(SUMMARY_MIN_HEIGHT);
        scroll.setPrefHeight(SUMMARY_PREF_HEIGHT);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    /**
     * Lets the reviewer narrow the diff annotations to one finding. Hidden while fewer than two
     * findings carry a reason, where filtering could only ever hide work.
     */
    private HBox buildFindingFilterBar() {
        findingFilterCombo = new ComboBox<>();
        findingFilterCombo.setVisibleRowCount(12);
        findingFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyFindingFilter(newValue));
        HBox bar = new HBox(8, new Label(I18n.get("snippets.ai.diff.focus")), findingFilterCombo);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setManaged(false);
        bar.setVisible(false);
        return bar;
    }

    /** Distinct finding ids that actually carry a reason, in the order the model reported them. */
    static List<String> findingFilterChoices(List<SnippetAiResponseSupport.SecurityChange> changes) {
        if (changes == null) {
            return List.of();
        }
        List<String> ids = new java.util.ArrayList<>();
        for (SnippetAiResponseSupport.SecurityChange change : changes) {
            if (change == null || change.reason().isBlank()) {
                continue;
            }
            String finding = change.finding().trim();
            if (!finding.isEmpty() && !ids.contains(finding)) {
                ids.add(finding);
            }
        }
        return List.copyOf(ids);
    }

    /** A blank or absent filter keeps every change; otherwise the finding id must match exactly. */
    static boolean matchesFindingFilter(SnippetAiResponseSupport.SecurityChange change, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return change != null && change.finding().trim().equals(filter.trim());
    }

    private void applyFindingFilter(String selected) {
        // Index 0 is the "all changes" entry; every later item is a finding id. Going by position
        // rather than by label keeps a finding that happens to read like the label out of the way.
        boolean showAll = selected == null || findingFilterCombo.getSelectionModel().getSelectedIndex() <= 0;
        activeFindingFilter = showAll ? null : selected;
        diffPane.setReasonFilter(activeFindingFilter);
        renderExplanations();
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
        de.kortty.core.KorttyClipboard.setText(replacementText);
    }

    // ---- Explanations panel (themed HTML) -------------------------------------------------------

    private String buildExplanationsHtml(
        List<SnippetAiResponseSupport.SecurityChange> changes,
        Map<Integer, int[]> reasonRanges) {

        StringBuilder items = new StringBuilder();
        int count = 0;
        for (int index = 0; index < changes.size(); index++) {
            SnippetAiResponseSupport.SecurityChange change = changes.get(index);
            if (change == null || change.reason().isBlank()
                || !matchesFindingFilter(change, activeFindingFilter)) {
                continue;
            }
            count++;
            String category = SnippetAiDialogSupport.categoryForFindingId(change.finding());
            String color = category != null ? SnippetAiDialogSupport.sectionColor(category) : ACCENT;
            String badge = !change.finding().isBlank() ? escapeHtml(change.finding()) : "•";
            items.append("<div class=\"item\" style=\"border-left-color:").append(color).append(";\">")
                .append("<span class=\"badge\" style=\"background:").append(color).append(";\">")
                .append(badge).append("</span>");
            if (category != null) {
                // Category glyph (shield/bolt/drop/box) so the section is recognizable at a glance,
                // matching the icons in the analysis window's section titles.
                items.append("<span class=\"cat\" style=\"color:").append(color).append(";\">")
                    .append(SnippetAiDialogSupport.sectionIconSvg(category)).append("</span>");
            }
            int[] range = reasonRanges.get(index);
            if (range != null) {
                items.append("<span class=\"lines\">").append(escapeHtml(formatLineRange(range[0], range[1])))
                    .append("</span>");
            }
            items.append("<span class=\"reason\">").append(escapeHtml(change.reason())).append("</span></div>");
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
            + ".cat{flex:0 0 auto;line-height:1;}"
            + ".cat .sec-ic{width:1.05em;height:1.05em;fill:currentColor;vertical-align:-.12em;}"
            + ".lines{flex:0 0 auto;font-family:'SF Mono',Menlo,Consolas,monospace;font-size:0.82em;opacity:.65;"
            + "white-space:nowrap;padding-top:1px;}"
            + ".reason{opacity:.96;}"
            + "</style></head><body>"
            + "<div class=\"head\"><span class=\"dot\"></span>" + header + "</div>"
            + items
            + "</body></html>";
    }

    /** "Line 23" for a single line, "Lines 23-40" for a block (modified/right side of the diff). */
    private static String formatLineRange(int start, int end) {
        if (end <= start) {
            return I18n.get("common.line") + " " + start;
        }
        return I18n.get("snippets.ai.diff.reasons.lines", start, end);
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
        for (int index = 0; index < changes.size(); index++) {
            SnippetAiResponseSupport.SecurityChange change = changes.get(index);
            if (change == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            // The list index keys the range report back to its explanation card (see applyReasonRanges).
            object.addProperty("idx", index);
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
