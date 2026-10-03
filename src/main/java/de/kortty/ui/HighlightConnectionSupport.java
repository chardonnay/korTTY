package de.kortty.ui;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.TerminalHighlightService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The <b>Keyword highlighting</b> row of the connection editor's <i>Terminal behavior</i> section: which
 * rule set the connection's terminal panes show. Only the dropdown's choices and their round trip to
 * {@code ServerConnection.highlightRuleSetId} live here, so they are unit-tested without the JavaFX
 * toolkit; {@code ConnectionEditDialog} lays out the controls.
 *
 * <p>The dropdown offers <b>Use the default</b> (stored as {@code null}, so the connection follows the
 * default rule set of <i>Settings → Terminal</i>, whose current set the entry names), <b>None</b> (stored
 * as {@value TerminalHighlightService#NONE_ID}: no highlighting for this connection whatever the default
 * is), then the built-in sets and the user's sets in the order of the highlighting menus. A stored id
 * that names no set the panes can show — a deleted user set, or one a shared teamwork file brought from
 * another machine — stays selectable under a "missing" label, so saving the connection for an unrelated
 * change does not silently drop it; the panes skip it and follow the default either way.
 */
final class HighlightConnectionSupport {

    /** The section header, shared with the other per-connection terminal behavior settings. */
    static final String SECTION_KEY = "connEdit.terminalBehavior";
    /** The dropdown's label. */
    static final String LABEL_KEY = "connEdit.highlighting";
    static final String TOOLTIP_KEY = "connEdit.highlighting.tooltip";
    /** The entry that follows the default rule set; {0} names the default's current set. */
    static final String DEFAULT_KEY = "connEdit.highlighting.default";
    /** The entry that switches highlighting off for the connection. */
    static final String NONE_KEY = "connEdit.highlighting.none";
    /** The entry for a stored id that names no set; {0} is the id. */
    static final String UNKNOWN_KEY = "connEdit.highlighting.unknown";
    /** The hint shown while the master switch in Settings → Terminal is off. */
    static final String DISABLED_KEY = "connEdit.highlighting.disabled";

    /** Every key of the row, for the i18n coverage test. */
    static final List<String> KEYS = List.of(SECTION_KEY, LABEL_KEY, TOOLTIP_KEY, DEFAULT_KEY, NONE_KEY,
        UNKNOWN_KEY, DISABLED_KEY);

    private HighlightConnectionSupport() {
    }

    /**
     * One entry of the dropdown.
     *
     * @param storedValue what {@code ServerConnection.highlightRuleSetId} stores: a set id,
     *                    {@value TerminalHighlightService#NONE_ID}, or {@code null} to use the default
     * @param label what the dropdown shows
     */
    record Choice(@Nullable String storedValue, @NotNull String label) {

        Choice {
            Objects.requireNonNull(label, "label");
        }

        /** The dropdown shows the label. */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The dropdown's entries: Use the default, None, every set in {@code setIds}, then — only when the
     * stored value names none of them — that id under the "missing" label.
     *
     * @param setIds the selectable ids, built-ins first ({@link HighlightSettingsSupport#selectableSetIds})
     * @param userSetName the stored name of a user set, or {@code null}
     * @param defaultSetId the set the global default resolves to now, {@code null} for none
     * @param stored {@code ServerConnection.getHighlightRuleSetId()}
     */
    static List<Choice> choices(@NotNull List<String> setIds, @Nullable Function<String, String> userSetName,
                                @Nullable String defaultSetId, @Nullable String stored) {
        List<Choice> choices = new ArrayList<>();
        String defaultName = defaultSetId != null && !defaultSetId.isBlank()
            ? HighlightMenuSupport.label(defaultSetId.trim(), userSetName)
            : I18n.get(HighlightMenuSupport.NONE_KEY);
        choices.add(new Choice(null, I18n.get(DEFAULT_KEY, defaultName)));
        choices.add(new Choice(TerminalHighlightService.NONE_ID, I18n.get(NONE_KEY)));
        for (String id : setIds) {
            if (id == null || id.isBlank() || TerminalHighlightService.NONE_ID.equals(id.trim())) {
                continue;
            }
            String trimmed = id.trim();
            choices.add(new Choice(trimmed, HighlightMenuSupport.label(trimmed, userSetName)));
        }
        String normalized = normalize(stored);
        if (normalized != null && indexOf(choices, normalized) < 0) {
            choices.add(new Choice(normalized, I18n.get(UNKNOWN_KEY, normalized)));
        }
        return choices;
    }

    /**
     * The set the global default names, for the label of Use the default: the stored default when the
     * panes can show it, {@code null} for none (no default, {@value TerminalHighlightService#NONE_ID}, or
     * a set that no longer exists). The master switch is left out on purpose: the dialog says separately
     * that highlighting is switched off.
     *
     * @param storedDefault {@code GlobalSettings.getDefaultHighlightRuleSetId()}
     * @param known which ids name a set the panes can show
     */
    static @Nullable String defaultSetId(@Nullable String storedDefault, @NotNull Predicate<String> known) {
        String id = normalize(storedDefault);
        if (id == null || TerminalHighlightService.NONE_ID.equals(id)) {
            return null;
        }
        return known.test(id) ? id : null;
    }

    /**
     * Which ids name a set: those of the running highlighting service, or the built-ins alone when it is
     * not running (as {@link HighlightSettingsSupport#selectableSetIds} lists them).
     */
    static Predicate<String> knownSets(@Nullable TerminalHighlightService service) {
        if (service == null || service.isClosed()) {
            return HighlightBuiltinSets::isBuiltin;
        }
        return service::isKnownSet;
    }

    /** The entry that shows {@code stored}: its set, None, or Use the default. */
    static Choice selected(@NotNull List<Choice> choices, @Nullable String stored) {
        int index = indexOf(choices, normalize(stored));
        return index >= 0 ? choices.get(index) : choices.getFirst();
    }

    /** The value to store for {@code choice}; {@code null} (no choice) means Use the default. */
    static @Nullable String storedValue(@Nullable Choice choice) {
        return choice != null ? normalize(choice.storedValue()) : null;
    }

    /** A stored value as the dropdown compares it: trimmed, and {@code null} for blank. */
    private static @Nullable String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int indexOf(List<Choice> choices, @Nullable String storedValue) {
        for (int i = 0; i < choices.size(); i++) {
            if (Objects.equals(choices.get(i).storedValue(), storedValue)) {
                return i;
            }
        }
        return -1;
    }
}
