package de.kortty.ui;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightTelemetry;
import de.kortty.core.highlight.TerminalHighlightService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The <b>Keyword highlighting</b> section of <i>Settings → Terminal</i>: the master switch, the
 * option for full-screen programs, the default rule set and the switch for trigger actions. Only the choices of the default-set
 * dropdown and their round trip to {@code GlobalSettings.defaultHighlightRuleSetId} live here, so
 * they are unit-tested without the JavaFX toolkit; {@code SettingsDialog} lays out the controls.
 *
 * <p>The dropdown offers <b>None</b>, the built-in sets and the user's sets, in the order of the
 * View → Highlighting menu; <b>Edit Rules…</b> next to it opens the rule-set editor, after which the
 * dropdown is rebuilt ({@link #selectionAfterRuleEdit}). A stored default that names no set the panes can show (a deleted user
 * set, or one the service ignores) stays selectable under a "missing" label, so saving the page for
 * an unrelated change does not silently drop it; the panes treat it as none either way.
 */
final class HighlightSettingsSupport {

    /** The section header. */
    static final String HEADER_KEY = "settings.terminal.highlighting.header";
    /** The master switch. */
    static final String ENABLED_KEY = "settings.terminal.highlighting.enabled";
    static final String ENABLED_TOOLTIP_KEY = "settings.terminal.highlighting.enabled.tooltip";
    /** Highlighting inside full-screen (alternate-screen) programs. */
    static final String ALTERNATE_SCREEN_KEY = "settings.terminal.highlighting.alternateScreen";
    static final String ALTERNATE_SCREEN_TOOLTIP_KEY = "settings.terminal.highlighting.alternateScreen.tooltip";
    /** The default rule set dropdown's label. */
    static final String DEFAULT_SET_KEY = "settings.terminal.highlighting.defaultSet";
    static final String DEFAULT_SET_TOOLTIP_KEY = "settings.terminal.highlighting.defaultSet.tooltip";
    /** The dropdown entry for "no default set". */
    static final String DEFAULT_SET_NONE_KEY = "settings.terminal.highlighting.defaultSet.none";
    /** The dropdown entry for a stored id that names no set; {0} is the id. */
    static final String DEFAULT_SET_UNKNOWN_KEY = "settings.terminal.highlighting.defaultSet.unknown";
    /** The hint below the section. */
    static final String INFO_KEY = "settings.terminal.highlighting.info";
    /** The button next to the default-set dropdown that opens the rule-set editor. */
    static final String EDIT_RULES_KEY = "settings.terminal.highlighting.editRules";
    static final String EDIT_RULES_TOOLTIP_KEY = "settings.terminal.highlighting.editRules.tooltip";
    /** The switch for what rules with an action do (a desktop notification, a snippet); the policy can lock it. */
    static final String TRIGGERS_KEY = "settings.terminal.highlighting.triggers";
    static final String TRIGGERS_TOOLTIP_KEY = "settings.terminal.highlighting.triggers.tooltip";

    /** Every key of the section, for the i18n coverage test. */
    static final List<String> KEYS = List.of(HEADER_KEY, ENABLED_KEY, ENABLED_TOOLTIP_KEY, ALTERNATE_SCREEN_KEY,
        ALTERNATE_SCREEN_TOOLTIP_KEY, DEFAULT_SET_KEY, DEFAULT_SET_TOOLTIP_KEY, DEFAULT_SET_NONE_KEY,
        DEFAULT_SET_UNKNOWN_KEY, INFO_KEY, EDIT_RULES_KEY, EDIT_RULES_TOOLTIP_KEY, TRIGGERS_KEY, TRIGGERS_TOOLTIP_KEY);

    private HighlightSettingsSupport() {
    }

    /**
     * One entry of the default-set dropdown.
     *
     * @param setId the set id to store, or {@code null} for none
     * @param label what the dropdown shows
     */
    record DefaultSetChoice(@Nullable String setId, @NotNull String label) {

        DefaultSetChoice {
            Objects.requireNonNull(label, "label");
        }

        /** The dropdown shows the label. */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The ids the dropdown offers: those of the running highlighting service (built-ins first, then the
     * user's sets), or the built-ins alone when the service is not running.
     */
    static List<String> selectableSetIds(@Nullable TerminalHighlightService service) {
        if (service == null || service.isClosed()) {
            return HighlightBuiltinSets.IDS;
        }
        return service.setIds();
    }

    /**
     * The dropdown's entries: None, then every set in {@code setIds}, then — only when the stored
     * default names none of them — that id under the "missing" label.
     *
     * @param setIds the selectable ids, built-ins first
     * @param userSetName the stored name of a user set, or {@code null}
     * @param storedDefault {@code GlobalSettings.getDefaultHighlightRuleSetId()}
     */
    static List<DefaultSetChoice> defaultSetChoices(@NotNull List<String> setIds,
                                                    @Nullable Function<String, String> userSetName,
                                                    @Nullable String storedDefault) {
        List<DefaultSetChoice> choices = new ArrayList<>();
        choices.add(new DefaultSetChoice(null, I18n.get(DEFAULT_SET_NONE_KEY)));
        for (String id : setIds) {
            if (id == null || id.isBlank() || TerminalHighlightService.NONE_ID.equals(id.trim())) {
                continue;
            }
            String trimmed = id.trim();
            choices.add(new DefaultSetChoice(trimmed, HighlightMenuSupport.label(trimmed, userSetName)));
        }
        String stored = normalize(storedDefault);
        if (stored != null && indexOf(choices, stored) < 0) {
            choices.add(new DefaultSetChoice(stored, I18n.get(DEFAULT_SET_UNKNOWN_KEY, stored)));
        }
        return choices;
    }

    /** The entry that shows {@code storedDefault}: its set, or None. */
    static DefaultSetChoice selected(@NotNull List<DefaultSetChoice> choices, @Nullable String storedDefault) {
        int index = indexOf(choices, normalize(storedDefault));
        return index >= 0 ? choices.get(index) : choices.getFirst();
    }

    /** The value to store for {@code choice}: its set id, or {@code null} for None. */
    static @Nullable String storedValue(@Nullable DefaultSetChoice choice) {
        return choice != null ? normalize(choice.setId()) : null;
    }

    /**
     * What the anonymous {@code setting_changed} event reports for the default set: the built-in's id,
     * {@code custom} for any set of the user's, or {@code none} — never a set's name.
     */
    static String telemetryValue(@Nullable String defaultSetId) {
        return HighlightTelemetry.setClass(defaultSetId);
    }

    /**
     * The dropdown's selection after the rule-set editor saved: a set the editor deleted (one the panes
     * could show before and cannot now) becomes None — the editor already cleared it as the stored
     * default — and every other selection, including a set that was missing before, stays as it was.
     *
     * @param selection the dropdown's set id before the editor opened, {@code null} for None
     * @param idsBefore {@link #selectableSetIds} before the editor opened
     * @param idsAfter {@link #selectableSetIds} after it saved
     */
    static @Nullable String selectionAfterRuleEdit(@Nullable String selection, @NotNull List<String> idsBefore,
                                                   @NotNull List<String> idsAfter) {
        String id = normalize(selection);
        if (id == null) {
            return null;
        }
        return idsBefore.contains(id) && !idsAfter.contains(id) ? null : id;
    }

    /** A stored id as the dropdown compares it: trimmed, and {@code null} for blank or "none". */
    private static @Nullable String normalize(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        return TerminalHighlightService.NONE_ID.equals(trimmed) ? null : trimmed;
    }

    private static int indexOf(List<DefaultSetChoice> choices, @Nullable String setId) {
        for (int i = 0; i < choices.size(); i++) {
            if (Objects.equals(choices.get(i).setId(), setId)) {
                return i;
            }
        }
        return -1;
    }
}
