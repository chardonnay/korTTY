package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The rich "AI Code Review" window: a themed, checkbox-selectable analysis on the left (summary,
 * categorized improvements, external dependencies) and an activity/flow diagram on the right that
 * renders asynchronously. Mirrors {@link SnippetSecurityReportDialog}'s HTML + {@code executeScript}
 * read-back and {@link SnippetDiagramDialog}'s async render, reusing {@link SnippetAiDialogSupport}.
 * The result is the mixed set of selected items to apply.
 */
public class SnippetCodeAnalysisDialog extends ThemeAwareDialog<SnippetCodeAnalysisDialog.ApplySelection> {

    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final List<String> CATEGORY_ORDER = List.of("security", "optimization", "design");

    /** The mixed selection the user ticked for a combined apply, plus any chosen script-hardening options. */
    public record ApplySelection(List<SnippetAiResponseSupport.ScriptImprovement> improvements,
                                 List<SnippetAiResponseSupport.ScriptDependency> dependencies,
                                 EnumSet<HardeningOption> hardening) {
        public boolean isEmpty() {
            return improvements.isEmpty() && dependencies.isEmpty() && hardening.isEmpty();
        }
    }

    private final SnippetAiResponseSupport.ScriptAnalysis analysis;
    private final Map<String, SnippetAiResponseSupport.ScriptImprovement> improvementsById = new LinkedHashMap<>();
    private final Map<String, SnippetAiResponseSupport.ScriptDependency> dependenciesById = new LinkedHashMap<>();
    private final HardeningOptionsSelector hardeningSelector = new HardeningOptionsSelector();

    private final WebView findingsView = new WebView();
    private final Label fontSizeLabel = new Label();
    private boolean pageReady;
    private int fontSize;

    private final SnippetDiagramView diagramView;

    public SnippetCodeAnalysisDialog(
            Window owner,
            String scriptName,
            SnippetAiResponseSupport.ScriptAnalysis analysis,
            Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramPlantUmlSupplier,
            String activeProfileId,
            Consumer<String> onRerun) {

        this.analysis = analysis != null ? analysis : new SnippetAiResponseSupport.ScriptAnalysis("", List.of(), List.of());
        this.fontSize = clampFontSize(loadPersistedFontSize());
        indexItems();

        String title = I18n.get("snippets.ai.analysis.title");
        setTitle(scriptName != null && !scriptName.isBlank() ? title + " — " + scriptName.trim() : title);
        setResizable(true);
        // Non-modal so the snippet editor stays usable while the analysis window is open.
        initModality(javafx.stage.Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        Label infoLabel = new Label(I18n.get("snippets.ai.analysis.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        findingsView.setContextMenuEnabled(false);
        findingsView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                pageReady = true;
            }
        });
        findingsView.getEngine().loadContent(buildAnalysisHtml());

        // Right pane: the full-featured, embeddable diagram viewer (fit-to-window, zoom, save/copy,
        // background, regenerate) — same functionality as the standalone "Snippet diagrams" dialog.
        diagramView = new SnippetDiagramView(diagramPlantUmlSupplier, true);
        Label diagramTitle = new Label(I18n.get("snippets.ai.analysis.diagram.title"));
        VBox rightPane = new VBox(6, diagramTitle, diagramView);
        VBox.setVgrow(diagramView, Priority.ALWAYS);
        rightPane.setPadding(new Insets(0, 0, 0, 4));

        SplitPane splitPane = new SplitPane(findingsView, rightPane);
        splitPane.setDividerPositions(0.52);
        SplitPane.setResizableWithParent(rightPane, true);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        Platform.runLater(() -> splitPane.setDividerPositions(0.52));

        VBox root = new VBox(10, infoLabel, buildToolbar(activeProfileId, onRerun), splitPane, buildHardeningPane());
        root.setPadding(new Insets(14));

        ButtonType applyButton = new ButtonType(
            SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.applySelected"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CLOSE);
        Button apply = (Button) getDialogPane().lookupButton(applyButton);
        apply.addEventFilter(ActionEvent.ACTION, event -> {
            if (readSelection().isEmpty()) {
                event.consume();
            }
        });
        getDialogPane().setPrefWidth(1160);
        getDialogPane().setPrefHeight(720);
        setResultConverter(buttonType -> buttonType == applyButton ? readSelection() : null);

        setOnShown(event -> diagramView.loadIfNeeded());
        setOnHidden(event -> {
            diagramView.dispose();
            // Unload the findings page so its WebKit engine releases its native memory.
            findingsView.getEngine().loadContent("");
        });
    }

    private HBox buildToolbar(String activeProfileId, Consumer<String> onRerun) {
        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changeFontSize(-1));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changeFontSize(1));
        updateFontSizeLabel();

        CheckBox selectAll = new CheckBox(I18n.get("snippets.ai.security.selectAll"));
        selectAll.setDisable(improvementsById.isEmpty() && dependenciesById.isEmpty());
        selectAll.setOnAction(event -> executeIfReady("window.korttyAnalysis.setAll(" + selectAll.isSelected() + ");"));

        Button copyButton = new Button(I18n.get("snippets.copyClipboard"));
        copyButton.setOnAction(event -> copyAnalysis(copyButton));

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
        toolbar.getChildren().addAll(spacer, selectAll, zoomOutButton, fontSizeLabel, zoomInButton, copyButton);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return toolbar;
    }

    private void indexItems() {
        for (SnippetAiResponseSupport.ScriptImprovement improvement : analysis.improvements()) {
            improvementsById.put(improvement.id(), improvement);
        }
        for (SnippetAiResponseSupport.ScriptDependency dependency : analysis.dependencies()) {
            dependenciesById.put(dependency.id(), dependency);
        }
    }

    // ---- Selection read-back --------------------------------------------------------------------

    private ApplySelection readSelection() {
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = new ArrayList<>();
        List<SnippetAiResponseSupport.ScriptDependency> dependencies = new ArrayList<>();
        if (!pageReady) {
            return new ApplySelection(improvements, dependencies, selectedHardening());
        }
        Object result;
        try {
            result = findingsView.getEngine().executeScript("window.korttyAnalysis.getSelected();");
        } catch (RuntimeException ignored) {
            return new ApplySelection(improvements, dependencies, selectedHardening());
        }
        if (result instanceof String value && !value.isBlank()) {
            for (String token : value.split(",")) {
                int sep = token.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String kind = token.substring(0, sep);
                String id = token.substring(sep + 1);
                if ("imp".equals(kind)) {
                    SnippetAiResponseSupport.ScriptImprovement item = improvementsById.get(id);
                    if (item != null) {
                        improvements.add(item);
                    }
                } else if ("dep".equals(kind)) {
                    SnippetAiResponseSupport.ScriptDependency item = dependenciesById.get(id);
                    if (item != null) {
                        dependencies.add(item);
                    }
                }
            }
        }
        return new ApplySelection(improvements, dependencies, selectedHardening());
    }

    private TitledPane buildHardeningPane() {
        // Use a bold Label as the title graphic so the section name is clearly visible next to the
        // expand arrow (the theme's TitledPane title text is otherwise too faint and easy to miss).
        Label header = new Label(I18n.get("ai.workflow.options.title"));
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: " + foreground + ";");
        TitledPane pane = new TitledPane();
        pane.setText(null);
        pane.setGraphic(header);
        pane.setContent(hardeningSelector);
        pane.setExpanded(false);
        return pane;
    }

    private EnumSet<HardeningOption> selectedHardening() {
        return hardeningSelector.selectedOptions();
    }

    // ---- Font zoom + copy -----------------------------------------------------------------------

    private void changeFontSize(int delta) {
        int next = clampFontSize(fontSize + delta);
        if (next == fontSize) {
            return;
        }
        fontSize = next;
        executeIfReady("window.korttyAnalysis.setFontSize(" + fontSize + ");");
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

    private void copyAnalysis(Button button) {
        StringBuilder text = new StringBuilder();
        if (!analysis.summary().isBlank()) {
            text.append(analysis.summary()).append("\n\n");
        }
        for (SnippetAiResponseSupport.ScriptImprovement improvement : analysis.improvements()) {
            text.append(improvement.id()).append(" [").append(improvement.category())
                .append('/').append(improvement.severity()).append("] ").append(improvement.title()).append('\n');
            if (!improvement.detail().isBlank()) {
                text.append(improvement.detail()).append('\n');
            }
            if (!improvement.recommendation().isBlank()) {
                text.append(I18n.get("snippets.ai.review.recommendation")).append(' ')
                    .append(improvement.recommendation()).append('\n');
            }
            text.append('\n');
        }
        for (SnippetAiResponseSupport.ScriptDependency dependency : analysis.dependencies()) {
            text.append(dependency.id()).append(" [").append(dependency.kind()).append("] ")
                .append(dependency.name()).append('\n');
            if (!dependency.suggestion().isBlank()) {
                text.append(dependency.suggestion()).append('\n');
            }
            text.append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.toString().strip());
        Clipboard.getSystemClipboard().setContent(content);

        String original = I18n.get("snippets.copyClipboard");
        button.setText(I18n.get("snippets.copied"));
        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(event -> button.setText(original));
        pause.play();
    }

    // ---- Left-pane HTML -------------------------------------------------------------------------

    private String buildAnalysisHtml() {
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String background = colors != null ? colors.backgroundColor() : SnippetAiDialogSupport.FALLBACK_BG;
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        String recommendationLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.review.recommendation"));

        StringBuilder body = new StringBuilder();
        if (!analysis.summary().isBlank()) {
            body.append("<div class=\"summary\">").append(SnippetAiDialogSupport.escapeHtml(analysis.summary())).append("</div>");
        }

        if (!analysis.improvements().isEmpty()) {
            for (String category : CATEGORY_ORDER) {
                List<SnippetAiResponseSupport.ScriptImprovement> group = analysis.improvements().stream()
                    .filter(item -> belongsToDisplayCategory(item.category(), category))
                    .sorted(Comparator.comparingInt(item -> SnippetAiDialogSupport.severityRank(item.severity())))
                    .toList();
                if (group.isEmpty()) {
                    continue;
                }
                body.append("<div class=\"section-title\">")
                    .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.section." + category)))
                    .append(" <span class=\"cat-count\">(").append(group.size()).append(")</span></div>");
                for (SnippetAiResponseSupport.ScriptImprovement item : group) {
                    body.append(renderImprovementCard(item, recommendationLabel));
                }
            }
        } else {
            body.append("<div class=\"empty\">")
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.improvements.empty")))
                .append("</div>");
        }

        if (!analysis.dependencies().isEmpty()) {
            String suggestionLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.dependency.suggestion"));
            String purposeLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.dependency.purpose"));
            body.append("<details class=\"dep-group\"><summary class=\"section-title\">")
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.section.dependencies")))
                .append(" <span class=\"cat-count\">(").append(analysis.dependencies().size()).append(")</span></summary>");
            for (SnippetAiResponseSupport.ScriptDependency dependency : analysis.dependencies()) {
                body.append(renderDependencyCard(dependency, suggestionLabel, purposeLabel));
            }
            body.append("</details>");
        }

        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
            + SnippetAiDialogSupport.cardCss(background, foreground, fontSize) + extraCss()
            + "</style></head><body>" + body + buildScript() + "</body></html>";
    }

    private String renderImprovementCard(SnippetAiResponseSupport.ScriptImprovement item, String recommendationLabel) {
        String severityClass = SnippetAiDialogSupport.severityCssClass(item.severity());
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card selectable\">");
        card.append("<div class=\"card-head\">");
        card.append("<input type=\"checkbox\" class=\"analysis-check\" data-kind=\"imp\" data-id=\"")
            .append(SnippetAiDialogSupport.escapeHtml(item.id())).append("\">");
        card.append("<span class=\"pill ").append(severityClass).append("\">")
            .append(SnippetAiDialogSupport.escapeHtml(item.severity())).append("</span>");
        card.append("<span class=\"title\"><span class=\"finding-id\">")
            .append(SnippetAiDialogSupport.escapeHtml(item.id())).append("</span>")
            .append(SnippetAiDialogSupport.escapeHtml(item.title()));
        if (item.line() != null && item.line() > 0) {
            card.append("<span class=\"loc\">").append(SnippetAiDialogSupport.escapeHtml(I18n.get("common.line")))
                .append(' ').append(item.line()).append("</span>");
        }
        card.append("</span></div>");
        if (!item.detail().isBlank()) {
            card.append("<p class=\"impact\">").append(SnippetAiDialogSupport.escapeHtml(item.detail())).append("</p>");
        }
        if (!item.recommendation().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(recommendationLabel).append("</span>")
                .append(SnippetAiDialogSupport.escapeHtml(item.recommendation())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    private String renderDependencyCard(SnippetAiResponseSupport.ScriptDependency dependency,
                                        String suggestionLabel, String purposeLabel) {
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card selectable\">");
        card.append("<div class=\"card-head\">");
        card.append("<input type=\"checkbox\" class=\"analysis-check\" data-kind=\"dep\" data-id=\"")
            .append(SnippetAiDialogSupport.escapeHtml(dependency.id())).append("\">");
        if (!dependency.kind().isBlank()) {
            card.append("<span class=\"pill sev-info\">").append(SnippetAiDialogSupport.escapeHtml(dependency.kind())).append("</span>");
        }
        card.append("<span class=\"title\"><span class=\"finding-id\">")
            .append(SnippetAiDialogSupport.escapeHtml(dependency.id())).append("</span>")
            .append(SnippetAiDialogSupport.escapeHtml(dependency.name()));
        if (!dependency.purpose().isBlank()) {
            card.append("<span class=\"dep-meta\">").append(purposeLabel).append(' ')
                .append(SnippetAiDialogSupport.escapeHtml(dependency.purpose())).append("</span>");
        }
        card.append("</span></div>");
        if (!dependency.suggestion().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(suggestionLabel).append("</span>")
                .append(SnippetAiDialogSupport.escapeHtml(dependency.suggestion())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    private static String extraCss() {
        return ".section-title{font-weight:700;font-size:1.06em;letter-spacing:.02em;margin:18px 0 8px;opacity:.9;}"
            + ".summary{border-left:3px solid " + SnippetAiDialogSupport.ACCENT + ";background:rgba(127,127,127,0.09);"
            + "padding:11px 13px;border-radius:0 6px 6px 0;margin-bottom:6px;white-space:pre-wrap;}"
            + ".cat-count{opacity:.5;font-weight:400;font-size:0.8em;}"
            + ".dep-meta{opacity:.72;font-size:0.85em;margin-left:6px;}"
            + "details.dep-group>summary{cursor:pointer;list-style:none;}"
            + "details.dep-group>summary::-webkit-details-marker{display:none;}";
    }

    private static String buildScript() {
        return "<script>"
            + "window.korttyAnalysis={"
            + "setAll:function(c){document.querySelectorAll('input.analysis-check').forEach(function(b){b.checked=c;mark(b);});},"
            + "getSelected:function(){var o=[];document.querySelectorAll('input.analysis-check').forEach(function(b){"
            + "if(b.checked)o.push(b.getAttribute('data-kind')+':'+b.getAttribute('data-id'));});return o.join(',');},"
            + "setFontSize:function(p){document.body.style.fontSize=p+'px';}"
            + "};"
            + "function mark(b){var c=b.closest('.card');if(c){c.classList.toggle('selected',b.checked);}}"
            + "document.addEventListener('change',function(e){if(e.target&&e.target.classList.contains('analysis-check')){mark(e.target);}});"
            + "document.addEventListener('click',function(e){var c=e.target.closest?e.target.closest('.card'):null;"
            + "if(c&&e.target.tagName!=='INPUT'){var b=c.querySelector('input.analysis-check');if(b){b.checked=!b.checked;mark(b);}}});"
            + "</script>";
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Maps an improvement's category onto a display group; "design" is the catch-all so nothing is dropped. */
    private static boolean belongsToDisplayCategory(String itemCategory, String displayCategory) {
        if ("design".equals(displayCategory)) {
            return !"security".equals(itemCategory) && !"optimization".equals(itemCategory);
        }
        return displayCategory.equals(itemCategory);
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    private int loadPersistedFontSize() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        if (settings != null && settings.getCodeAnalysisFontSize() != null) {
            return settings.getCodeAnalysisFontSize();
        }
        return DEFAULT_FONT_SIZE;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisFontSize(fontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }
}
