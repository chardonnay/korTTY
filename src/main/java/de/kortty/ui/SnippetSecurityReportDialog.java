package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
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
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lets the user choose which AI security findings should be fixed. The findings are rendered as a
 * themed HTML page inside a {@link WebView} (severity pills, cards, readable typography); the checkbox
 * selection is read back with {@code executeScript} on apply, so no fragile JS&rarr;Java bridge is needed.
 * Supports a persisted font zoom, copy-to-clipboard, a dedicated security-check AI profile, severity
 * sorting, a select-all toggle and a re-run action.
 */
public class SnippetSecurityReportDialog extends ThemeAwareDialog<List<SnippetAiResponseSupport.SecurityFinding>> {

    private static final String AI_ACTION_PREFIX = "✨ ";
    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final String ACCENT = "#3b82f6";
    private static final String FALLBACK_BG = "#1e1e1e";
    private static final String FALLBACK_FG = "#d6d6d6";

    private final List<SnippetAiResponseSupport.SecurityFinding> findings;
    private final WebView findingsView = new WebView();
    private final Label fontSizeLabel = new Label();
    private int fontSize;
    private boolean pageReady;

    /** A selectable AI profile; {@code id == null} means "use the default profile". */
    private record ProfileChoice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public SnippetSecurityReportDialog(
            Window owner,
            List<SnippetAiResponseSupport.SecurityFinding> findings,
            Runnable onRerun) {

        setTitle(I18n.get("snippets.ai.security.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        // Unload the findings page on close so its WebKit engine releases its native memory.
        setOnHidden(event -> findingsView.getEngine().loadContent(""));
        this.fontSize = clampFontSize(loadPersistedFontSize());
        this.findings = sortedBySeverity(findings);

        Label infoLabel = new Label(I18n.get("snippets.ai.security.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 0.9231em; -fx-text-fill: gray;");

        findingsView.setContextMenuEnabled(false);
        findingsView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                pageReady = true;
            }
        });
        findingsView.getEngine().loadContent(buildFindingsHtml());
        VBox.setVgrow(findingsView, Priority.ALWAYS);

        HBox toolbar = buildToolbar(onRerun);

        VBox root = new VBox(10, infoLabel, toolbar, findingsView);
        root.setPadding(new Insets(14));

        ButtonType applySelectedButton = new ButtonType(
            AI_ACTION_PREFIX + I18n.get("snippets.ai.security.applySelected"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applySelectedButton, ButtonType.CLOSE);
        // No JS->Java bridge: keep the apply button enabled and instead block closing when nothing is
        // selected, so the dialog never returns an empty fix request.
        Button applyButton = (Button) getDialogPane().lookupButton(applySelectedButton);
        applyButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (selectedFindings().isEmpty()) {
                event.consume();
            }
        });
        getDialogPane().setPrefWidth(880);
        getDialogPane().setPrefHeight(660);
        setResultConverter(buttonType -> buttonType == applySelectedButton ? selectedFindings() : null);
    }

    private HBox buildToolbar(Runnable onRerun) {
        ComboBox<ProfileChoice> profileCombo = buildProfileCombo();

        Button rerunButton = new Button(I18n.get("snippets.ai.security.rerun"));
        rerunButton.setTooltip(new Tooltip(I18n.get("snippets.ai.security.rerun.hint")));
        rerunButton.setDisable(onRerun == null);
        rerunButton.setOnAction(event -> {
            if (onRerun != null) {
                close();
                javafx.application.Platform.runLater(onRerun);
            }
        });

        CheckBox selectAll = new CheckBox(I18n.get("snippets.ai.security.selectAll"));
        selectAll.setDisable(findings.isEmpty());
        selectAll.setOnAction(event -> executeIfReady(
            "window.korttySecReport.setAll(" + selectAll.isSelected() + ");"));

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
        HBox toolbar = new HBox(
            8,
            new Label(I18n.get("snippets.ai.security.profile")),
            profileCombo,
            rerunButton,
            spacer,
            selectAll,
            zoomOutButton,
            fontSizeLabel,
            zoomInButton,
            copyButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return toolbar;
    }

    private ComboBox<ProfileChoice> buildProfileCombo() {
        ComboBox<ProfileChoice> combo = new ComboBox<>();
        combo.setTooltip(new Tooltip(I18n.get("snippets.ai.security.profile.hint")));
        List<ProfileChoice> choices = new ArrayList<>();
        choices.add(new ProfileChoice(null, I18n.get("snippets.ai.security.profile.default")));
        GlobalSettings settings = currentSettings();
        if (settings != null && settings.getAiProfiles() != null) {
            for (AiProfile profile : settings.getAiProfiles()) {
                if (profile == null || profile.getId() == null || profile.getId().isBlank()) {
                    continue;
                }
                String label = profile.getName() != null && !profile.getName().isBlank()
                    ? profile.getName()
                    : profile.getId();
                choices.add(new ProfileChoice(profile.getId(), label));
            }
        }
        combo.getItems().setAll(choices);
        String currentId = settings != null ? settings.getSecurityCheckAiProfileId() : null;
        combo.setValue(choices.stream()
            .filter(choice -> Objects.equals(choice.id(), currentId))
            .findFirst()
            .orElse(choices.get(0)));
        combo.valueProperty().addListener((obs, oldValue, newValue) ->
            persistSecurityProfile(newValue != null ? newValue.id() : null));
        return combo;
    }

    private List<SnippetAiResponseSupport.SecurityFinding> selectedFindings() {
        List<SnippetAiResponseSupport.SecurityFinding> selected = new ArrayList<>();
        if (!pageReady) {
            return selected;
        }
        Object result;
        try {
            result = findingsView.getEngine().executeScript("window.korttySecReport.getSelected();");
        } catch (RuntimeException ignored) {
            return selected;
        }
        if (result instanceof String value && !value.isBlank()) {
            for (String part : value.split(",")) {
                try {
                    int index = Integer.parseInt(part.trim());
                    if (index >= 0 && index < findings.size()) {
                        selected.add(findings.get(index));
                    }
                } catch (NumberFormatException ignored) {
                    // Skip malformed indices defensively.
                }
            }
        }
        return selected;
    }

    private void copyFindings(Button button) {
        StringBuilder text = new StringBuilder();
        for (SnippetAiResponseSupport.SecurityFinding finding : findings) {
            if (finding == null) {
                continue;
            }
            text.append(finding.id())
                .append(" [").append(finding.severity()).append("] ")
                .append(finding.title()).append('\n');
            if (!finding.impact().isBlank()) {
                text.append(finding.impact()).append('\n');
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

    private void changeFontSize(int delta) {
        int next = clampFontSize(fontSize + delta);
        if (next == fontSize) {
            return;
        }
        fontSize = next;
        executeIfReady("window.korttySecReport.setFontSize(" + fontSize + ");");
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

    // ---- HTML rendering -------------------------------------------------------------------------

    private String buildFindingsHtml() {
        ThemeCssSupport.ThemeColors colors = resolveThemeColors();
        String background = colors != null ? colors.backgroundColor() : FALLBACK_BG;
        String foreground = colors != null ? colors.foregroundColor() : FALLBACK_FG;
        String recommendationLabel = escapeHtml(I18n.get("snippets.ai.review.recommendation"));

        StringBuilder body = new StringBuilder();
        if (findings.isEmpty()) {
            body.append("<div class=\"empty\">").append(escapeHtml(I18n.get("snippets.ai.security.empty"))).append("</div>");
        } else {
            int index = 0;
            for (SnippetAiResponseSupport.SecurityFinding finding : findings) {
                body.append(renderFindingCard(finding, index++, recommendationLabel));
            }
        }

        return "<!doctype html><html><head><meta charset=\"UTF-8\">"
            + "<style>" + buildCss(background, foreground) + "</style></head>"
            + "<body>" + body + buildScript() + "</body></html>";
    }

    private String renderFindingCard(SnippetAiResponseSupport.SecurityFinding finding, int index, String recommendationLabel) {
        String severityClass = severityCssClass(finding.severity());
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card\" data-idx=\"").append(index).append("\">");
        card.append("<div class=\"card-head\">");
        card.append("<input type=\"checkbox\" class=\"finding-check\" data-idx=\"").append(index).append("\">");
        card.append("<span class=\"pill ").append(severityClass).append("\">")
            .append(escapeHtml(finding.severity())).append("</span>");
        card.append("<span class=\"title\"><span class=\"finding-id\">")
            .append(escapeHtml(finding.id())).append("</span>")
            .append(escapeHtml(finding.title())).append("</span>");
        card.append("</div>");
        if (!finding.impact().isBlank()) {
            card.append("<p class=\"impact\">").append(escapeHtml(finding.impact())).append("</p>");
        }
        if (!finding.recommendation().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(recommendationLabel)
                .append("</span>").append(escapeHtml(finding.recommendation())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    private String buildCss(String background, String foreground) {
        return ":root{color-scheme:dark;}"
            + "*{box-sizing:border-box;}"
            + "body{margin:0;padding:14px;background:" + background + ";color:" + foreground + ";"
            + "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:" + fontSize + "px;line-height:1.5;}"
            + ".card{border:1px solid rgba(128,128,128,0.28);border-radius:10px;padding:12px 14px;margin-bottom:12px;"
            + "background:rgba(127,127,127,0.06);transition:background .12s,border-color .12s;cursor:pointer;}"
            + ".card:hover{background:rgba(127,127,127,0.12);border-color:rgba(128,128,128,0.5);}"
            + ".card.selected{border-color:" + ACCENT + ";background:rgba(59,130,246,0.12);}"
            + ".card-head{display:flex;align-items:center;gap:10px;}"
            + ".finding-check{width:1.05em;height:1.05em;accent-color:" + ACCENT + ";flex:0 0 auto;cursor:pointer;margin:0;}"
            + ".pill{flex:0 0 auto;font-size:0.72em;font-weight:700;letter-spacing:.04em;padding:2px 9px;border-radius:999px;"
            + "text-transform:uppercase;white-space:nowrap;}"
            + ".pill.sev-critical,.pill.sev-high{background:#c0392b;color:#fff;}"
            + ".pill.sev-medium{background:#e67e22;color:#fff;}"
            + ".pill.sev-low{background:#f1c40f;color:#1e1e1e;}"
            + ".pill.sev-info{background:#7f8c8d;color:#fff;}"
            + ".title{font-weight:600;font-size:1.03em;}"
            + ".finding-id{font-family:'SF Mono',Menlo,Consolas,monospace;opacity:.75;margin-right:5px;}"
            + ".impact{margin:9px 0 0;opacity:.92;}"
            + ".rec{margin:10px 0 0;padding:8px 11px;border-left:3px solid " + ACCENT + ";"
            + "background:rgba(127,127,127,0.09);border-radius:0 6px 6px 0;}"
            + ".rec-label{font-weight:700;opacity:.85;margin-right:5px;}"
            + ".empty{opacity:.7;padding:24px;text-align:center;}";
    }

    private String buildScript() {
        return "<script>"
            + "window.korttySecReport={"
            + "setAll:function(c){document.querySelectorAll('input.finding-check').forEach(function(b){b.checked=c;mark(b);});},"
            + "getSelected:function(){var o=[];document.querySelectorAll('input.finding-check').forEach(function(b){if(b.checked)o.push(b.getAttribute('data-idx'));});return o.join(',');},"
            + "setFontSize:function(p){document.body.style.fontSize=p+'px';}"
            + "};"
            + "function mark(b){var c=b.closest('.card');if(c){c.classList.toggle('selected',b.checked);}}"
            + "document.addEventListener('change',function(e){if(e.target&&e.target.classList.contains('finding-check')){mark(e.target);}});"
            + "document.addEventListener('click',function(e){var c=e.target.closest?e.target.closest('.card'):null;"
            + "if(c&&e.target.tagName!=='INPUT'){var b=c.querySelector('input.finding-check');if(b){b.checked=!b.checked;mark(b);}}});"
            + "</script>";
    }

    private ThemeCssSupport.ThemeColors resolveThemeColors() {
        try {
            return ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static List<SnippetAiResponseSupport.SecurityFinding> sortedBySeverity(
            List<SnippetAiResponseSupport.SecurityFinding> findings) {
        if (findings == null) {
            return List.of();
        }
        return findings.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(finding -> severityRank(finding.severity())))
            .toList();
    }

    private static int severityRank(String severity) {
        String value = severity != null ? severity.trim().toLowerCase() : "";
        return switch (value) {
            case "critical", "crit" -> 0;
            case "high" -> 1;
            case "medium", "moderate", "med" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }

    private static String severityCssClass(String severity) {
        return switch (severityRank(severity)) {
            case 0 -> "sev-critical";
            case 1 -> "sev-high";
            case 2 -> "sev-medium";
            case 3 -> "sev-low";
            default -> "sev-info";
        };
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
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    private static GlobalSettings currentSettings() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        } catch (Exception ignored) {
            return null;
        }
    }

    private int loadPersistedFontSize() {
        GlobalSettings settings = currentSettings();
        if (settings != null && settings.getSecurityReportFontSize() != null) {
            return settings.getSecurityReportFontSize();
        }
        return DEFAULT_FONT_SIZE;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setSecurityReportFontSize(fontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private void persistSecurityProfile(String profileId) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setSecurityCheckAiProfileId(profileId);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }
}
