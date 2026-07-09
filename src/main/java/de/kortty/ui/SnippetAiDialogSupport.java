package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared building blocks for the snippet-editor AI dialogs (code review, security report, description,
 * alternatives, diff) so they look and behave uniformly: a transient AI-profile picker that lets the
 * user repeat a check/adjustment with a different profile, a matching re-run button, and the themed
 * "findings" HTML (severity pills, cards, readable typography) used by the review and security reports.
 *
 * <p>The profile choice is transient: the combo always starts on the profile that produced the current
 * result and applies only to the next re-run, so no global default behaviour changes. The dedicated
 * security-check profile keeps its own persisted setting elsewhere.
 */
final class SnippetAiDialogSupport {

    static final String AI_ACTION_PREFIX = "✨ ";
    static final String ACCENT = "#3b82f6";
    static final String FALLBACK_BG = "#1e1e1e";
    static final String FALLBACK_FG = "#d6d6d6";

    private SnippetAiDialogSupport() {
    }

    /** A selectable AI profile; {@code id == null} means "use the default profile". */
    record ProfileChoice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    static List<ProfileChoice> profileChoices() {
        List<ProfileChoice> choices = new ArrayList<>();
        choices.add(new ProfileChoice(null, I18n.get("snippets.ai.profile.default")));
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
        return choices;
    }

    /** Builds the transient profile combo, selecting {@code activeProfileId} (null = default profile). */
    static ComboBox<ProfileChoice> buildProfileCombo(String activeProfileId) {
        ComboBox<ProfileChoice> combo = new ComboBox<>();
        combo.setTooltip(new Tooltip(I18n.get("snippets.ai.profile.hint")));
        List<ProfileChoice> choices = profileChoices();
        combo.getItems().setAll(choices);
        combo.setValue(choices.stream()
            .filter(choice -> Objects.equals(choice.id(), activeProfileId))
            .findFirst()
            .orElse(choices.get(0)));
        return combo;
    }

    static Label profileLabel() {
        return new Label(I18n.get("snippets.ai.profile"));
    }

    /**
     * A human-readable name for the profile a result was (or will be) produced with: the named profile for
     * {@code activeProfileId}, otherwise the configured default profile's name, falling back to the generic
     * "Default profile" label. Lets a dialog state which profile it is actually using — the combo alone only
     * shows the literal "Default profile" for the null selection, never the default's real name.
     */
    static String resolveProfileDisplayName(String activeProfileId) {
        GlobalSettings settings = currentSettings();
        if (settings != null && settings.getAiProfiles() != null) {
            String targetId = activeProfileId != null && !activeProfileId.isBlank()
                ? activeProfileId
                : settings.getDefaultAiProfileId();
            if (targetId != null && !targetId.isBlank()) {
                for (AiProfile profile : settings.getAiProfiles()) {
                    if (profile != null && targetId.equals(profile.getId())
                        && profile.getName() != null && !profile.getName().isBlank()) {
                        return profile.getName();
                    }
                }
            }
        }
        return I18n.get("snippets.ai.profile.default");
    }

    /** The profile id currently selected in {@code combo}, or {@code null} for the default profile. */
    static String selectedProfileId(ComboBox<ProfileChoice> combo) {
        ProfileChoice choice = combo != null ? combo.getValue() : null;
        return choice != null ? choice.id() : null;
    }

    /**
     * Builds a re-run button that closes the dialog (via {@code beforeRerun}) and then invokes
     * {@code onRerun} with the selected profile id on the JavaFX thread, mirroring the security report's
     * re-run action. Returns {@code null} when {@code onRerun} is {@code null} (feature unavailable).
     */
    static Button buildRerunButton(
            Supplier<String> selectedProfileId,
            Consumer<String> onRerun,
            Runnable beforeRerun) {
        if (onRerun == null) {
            return null;
        }
        Button button = new Button(I18n.get("snippets.ai.rerun"));
        button.setTooltip(new Tooltip(I18n.get("snippets.ai.rerun.hint")));
        button.setOnAction(event -> {
            String profileId = selectedProfileId != null ? selectedProfileId.get() : null;
            if (beforeRerun != null) {
                beforeRerun.run();
            }
            Platform.runLater(() -> onRerun.accept(profileId));
        });
        return button;
    }

    static ThemeCssSupport.ThemeColors resolveThemeColors() {
        try {
            return ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        } catch (Exception ignored) {
            return null;
        }
    }

    static GlobalSettings currentSettings() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- Findings HTML (shared severity pills / cards) ------------------------------------------

    static int severityRank(String severity) {
        String value = severity != null ? severity.trim().toLowerCase() : "";
        return switch (value) {
            case "critical", "crit" -> 0;
            case "high" -> 1;
            case "medium", "moderate", "med" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }

    static String severityCssClass(String severity) {
        return switch (severityRank(severity)) {
            case 0 -> "sev-critical";
            case 1 -> "sev-high";
            case 2 -> "sev-medium";
            case 3 -> "sev-low";
            default -> "sev-info";
        };
    }

    /**
     * The shared card/pill CSS used by the security report and code-review dialogs so they stay visually
     * identical. Includes the (optional) selection styles used only by the security report's checkboxes;
     * dialogs that render no checkboxes simply never trigger those rules.
     */
    static String cardCss(String background, String foreground, int fontSize) {
        return ":root{color-scheme:dark;}"
            + "*{box-sizing:border-box;}"
            + "body{margin:0;padding:14px;background:" + background + ";color:" + foreground + ";"
            + "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:" + fontSize + "px;line-height:1.5;}"
            + ".card{border:1px solid rgba(128,128,128,0.28);border-radius:10px;padding:12px 14px;margin-bottom:12px;"
            + "background:rgba(127,127,127,0.06);transition:background .12s,border-color .12s;}"
            + ".card.selectable{cursor:pointer;}"
            + ".card.selectable:hover{background:rgba(127,127,127,0.12);border-color:rgba(128,128,128,0.5);}"
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
            + ".loc{opacity:.6;font-size:0.82em;margin-left:6px;font-family:'SF Mono',Menlo,Consolas,monospace;white-space:nowrap;}"
            + ".impact{margin:9px 0 0;opacity:.92;white-space:pre-wrap;}"
            + ".rec{margin:10px 0 0;padding:8px 11px;border-left:3px solid " + ACCENT + ";"
            + "background:rgba(127,127,127,0.09);border-radius:0 6px 6px 0;white-space:pre-wrap;}"
            + ".rec-label{font-weight:700;opacity:.85;margin-right:5px;}"
            + ".empty{opacity:.7;padding:24px;text-align:center;}";
    }

    // ---- Section icons (analysis categories) -----------------------------------------------------

    /** Accent colour for an analysis section/category (security, optimization, design, dependencies). */
    static String sectionColor(String category) {
        return switch (category != null ? category : "") {
            case "security" -> "#e5484d";
            case "optimization" -> "#f59e0b";
            case "dependencies" -> "#14b8a6";
            default -> "#8b5cf6"; // design / catch-all
        };
    }

    /**
     * A small inline-SVG glyph for an analysis category, coloured via CSS {@code currentColor} (style the
     * surrounding element). Inline SVG instead of emoji because the WebView's default fonts cannot render
     * the supplementary-plane emoji — they show up as replacement boxes.
     */
    static String sectionIconSvg(String category) {
        String path = switch (category != null ? category : "") {
            case "security" -> "M8 1.3 13.5 3.3V7.6C13.5 10.8 11.2 13.4 8 14.4 4.8 13.4 2.5 10.8 2.5 7.6V3.3Z";
            case "optimization" -> "M9 1 4 8.5H7L6.5 15 12 6.5H8.5L9 1Z";
            case "dependencies" -> "M8 1.4 13.6 4.2V9.8L8 12.6 2.4 9.8V4.2Z";
            default -> "M8 1.5C11 5 13 7.5 13 10A5 5 0 0 1 3 10C3 7.5 5 5 8 1.5Z"; // design / catch-all
        };
        return "<svg class=\"sec-ic\" viewBox=\"0 0 16 16\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\""
            + path + "\"/></svg>";
    }

    /**
     * Maps a finding/change id onto its analysis category by its letter prefix — {@code SEC-1}/{@code S1}
     * &rarr; security, {@code OPT-1} &rarr; optimization, {@code DES-4} &rarr; design, {@code D1} &rarr;
     * dependencies. Returns {@code null} when the id carries no recognizable prefix.
     */
    static String categoryForFindingId(String findingId) {
        if (findingId == null) {
            return null;
        }
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < findingId.length(); i++) {
            char c = findingId.charAt(i);
            if (!Character.isLetter(c)) {
                break;
            }
            letters.append(Character.toUpperCase(c));
        }
        return switch (letters.toString()) {
            case "SEC", "S" -> "security";
            case "OPT", "O" -> "optimization";
            case "DES" -> "design";
            case "D", "DEP" -> "dependencies";
            default -> null;
        };
    }

    static String escapeHtml(String value) {
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
}
