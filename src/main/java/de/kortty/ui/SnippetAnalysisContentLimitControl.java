package de.kortty.ui;

import de.kortty.core.SnippetAnalysisContentLimit;
import de.kortty.policy.PolicyUiSupport;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The "stored script size per analysis" setting of the Snippet editor tab: a combo of presets
 * (Off, 256 KB, 512 KB, 1 MB, 2 MB, 5 MB), the hint about disk use and, when the enterprise policy limits
 * it, the note that says so.
 *
 * <p>Policy: the choices above the administrator's cap are not offered, the shown value is
 * {@code min(user setting, cap)}, and a cap of 0 disables the combo (script text is never stored).
 * The user's own stored value is only overwritten when they actually pick another entry
 * ({@link #changedByUser()}), so a temporary cap does not silently lower their preference.
 * A value that is not a preset (edited into {@code global-settings.xml}) is kept as an extra entry.
 */
final class SnippetAnalysisContentLimitControl {

    static final long KB = 1024L;
    static final long MB = 1024L * KB;
    /** The presets offered, in bytes; 0 = do not store script text. */
    static final List<Long> PRESETS = List.of(0L, 256 * KB, 512 * KB, 1 * MB, 2 * MB, 5 * MB);

    private final ComboBox<Long> combo = new ComboBox<>();
    private final Label hint = new Label(I18n.get("settings.snippetEditor.analysisContentLimit.hint"));
    private final Label policyNote = new Label();
    private final Label loweredNote = new Label(I18n.get("settings.snippetEditor.analysisContentLimit.lowered"));
    private final long savedUserBytes;
    private final long shownAtStart;
    private final Long policyMax;

    /**
     * @param savedUserBytes the user's own value ({@link de.kortty.model.GlobalSettings#getSnippetAnalysisMaxStoredContentBytes()})
     * @param policyMax      the policy maximum, or {@code null} when none applies
     */
    SnippetAnalysisContentLimitControl(long savedUserBytes, Long policyMax) {
        this.savedUserBytes = savedUserBytes;
        this.policyMax = policyMax;
        long shown = shownValue(savedUserBytes, policyMax);
        this.shownAtStart = shown;

        combo.setId("settings-snippet-analysis-content-limit");
        combo.getItems().setAll(itemsFor(shown, policyMax));
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Long bytes) {
                return bytes == null ? "" : label(bytes);
            }

            @Override
            public Long fromString(String text) {
                return null;
            }
        });
        combo.setButtonCell(new LabelCell());
        combo.setCellFactory(list -> new LabelCell());
        combo.setValue(shown);
        combo.setPrefWidth(200);
        combo.setTooltip(new Tooltip(I18n.get("settings.snippetEditor.analysisContentLimit.tooltip")));

        hint.setId("settings-snippet-analysis-content-limit-hint");
        styleNote(hint, "-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        policyNote.setId("settings-snippet-analysis-content-limit-policy");
        styleNote(policyNote, "-fx-font-size: 0.8462em; -fx-text-fill: #8a6d3b;");
        loweredNote.setId("settings-snippet-analysis-content-limit-lowered");
        styleNote(loweredNote, "-fx-font-size: 0.8462em; -fx-text-fill: #d97706;");
        setVisible(loweredNote, false);
        setVisible(policyNote, policyMax != null);
        if (policyMax != null) {
            policyNote.setText(policyMax == 0L
                ? I18n.get("settings.snippetEditor.analysisContentLimit.policyOff")
                : I18n.get("settings.snippetEditor.analysisContentLimit.policyCap", label(policyMax)));
        }
        if (policyMax != null && policyMax == 0L) {
            combo.setDisable(true);
            combo.setTooltip(new Tooltip(PolicyUiSupport.managedByOrganizationText()));
        }
        combo.valueProperty().addListener((obs, was, now) -> setVisible(loweredNote,
            now != null && now < shownAtStart));
    }

    /** Adds the combo and its notes to {@code grid} starting at {@code row}; returns the next free row. */
    int addTo(GridPane grid, Label caption, int row) {
        grid.add(caption, 0, row);
        grid.add(combo, 1, row++);
        grid.add(hint, 0, row++, 2, 1);
        grid.add(policyNote, 0, row++, 2, 1);
        grid.add(loweredNote, 0, row++, 2, 1);
        return row;
    }

    ComboBox<Long> combo() {
        return combo;
    }

    Label policyNote() {
        return policyNote;
    }

    Label loweredNote() {
        return loweredNote;
    }

    /** The bytes the user selected now (0 = off). */
    long selectedBytes() {
        Long value = combo.getValue();
        return value != null ? value : shownAtStart;
    }

    /** Whether the user picked another entry than the one shown when the dialog opened. */
    boolean changedByUser() {
        return selectedBytes() != shownAtStart;
    }

    /** The value to store, or {@code null} to leave the saved setting untouched. */
    Long valueToStore() {
        return changedByUser() && !(policyMax != null && policyMax == 0L) ? selectedBytes() : null;
    }

    long savedUserBytes() {
        return savedUserBytes;
    }

    // ---- pure helpers (tested without a toolkit) ----

    /** {@code min(user, policy cap)}: what the user gets to see. */
    static long shownValue(long userBytes, Long policyMax) {
        return policyMax != null ? Math.min(userBytes, policyMax) : userBytes;
    }

    /** The entries to offer: presets within the policy cap, plus the shown value if it is none of them. */
    static List<Long> itemsFor(long shown, Long policyMax) {
        List<Long> items = new ArrayList<>();
        for (long preset : PRESETS) {
            if (policyMax == null || preset <= policyMax) {
                items.add(preset);
            }
        }
        if (!items.contains(shown)) {
            items.add(shown);
        }
        items.sort(Comparator.naturalOrder());
        return items;
    }

    /** Human text of a size: "256 KB", "5 MB", "2.5 MB" ... ("Off" for 0). */
    static String format(long bytes) {
        if (bytes <= 0) {
            return I18n.get("settings.snippetEditor.analysisContentLimit.off");
        }
        if (bytes < MB) {
            return I18n.get("settings.snippetEditor.analysisContentLimit.kb", trimmed(bytes / (double) KB));
        }
        return I18n.get("settings.snippetEditor.analysisContentLimit.mb", trimmed(bytes / (double) MB));
    }

    private static String label(long bytes) {
        String text = format(bytes);
        return bytes == SnippetAnalysisContentLimit.DEFAULT_BYTES
            ? I18n.get("settings.snippetEditor.analysisContentLimit.default", text)
            : text;
    }

    private static String trimmed(double value) {
        return value == Math.rint(value)
            ? String.valueOf((long) value)
            : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void styleNote(Label label, String style) {
        label.setStyle(style);
        label.setWrapText(true);
        label.setMaxWidth(400);
    }

    private static void setVisible(Label label, boolean visible) {
        label.setVisible(visible);
        label.setManaged(visible);
    }

    private static final class LabelCell extends ListCell<Long> {
        @Override
        protected void updateItem(Long item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : label(item));
        }
    }
}
