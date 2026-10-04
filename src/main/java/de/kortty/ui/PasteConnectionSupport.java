package de.kortty.ui;

import de.kortty.paste.PastePacer;
import de.kortty.paste.PasteWarningMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The paste protection rows of the connection editor's <i>Terminal behavior</i> section: when a paste with
 * line breaks into the connection's terminals asks, and the pause after each pasted line. Only the choices
 * and their round trip to {@code ServerConnection.pasteWarningMode} and {@code pasteLineDelayMs} live
 * here, so they are unit-tested without the JavaFX toolkit; {@code ConnectionEditDialog} lays out the
 * controls.
 *
 * <p>The warning dropdown offers <b>Use the default</b> (stored as {@code null}, so the connection follows
 * <i>Settings → Terminal → Paste protection</i>, whose current mode the entry names), then the three modes
 * by the names the Settings page uses. The line delay is stored only while its box is ticked; without the
 * tick it follows the Settings page, whose pause the box names. Neither ever changes the Settings page.
 */
final class PasteConnectionSupport {

    /** The warning dropdown's label. */
    static final String MODE_LABEL_KEY = "connEdit.paste.warningMode";
    static final String MODE_TOOLTIP_KEY = "connEdit.paste.warningMode.tooltip";
    /** The entry that follows the global mode; {0} names that mode. */
    static final String MODE_DEFAULT_KEY = "connEdit.paste.warningMode.default";
    /** The line delay row's label. */
    static final String DELAY_LABEL_KEY = "connEdit.paste.lineDelay";
    /** The box that gives the connection a pause of its own; {0} is the global pause in milliseconds. */
    static final String DELAY_OVERRIDE_KEY = "connEdit.paste.lineDelay.override";
    static final String DELAY_UNIT_KEY = "connEdit.paste.lineDelay.unit";
    static final String DELAY_TOOLTIP_KEY = "connEdit.paste.lineDelay.tooltip";
    /** The note under the rows for a connection from a shared teamwork file. */
    static final String TEAMWORK_KEY = "connEdit.paste.teamwork";

    /** Every key of the rows, for the i18n coverage test. */
    static final List<String> KEYS = List.of(MODE_LABEL_KEY, MODE_TOOLTIP_KEY, MODE_DEFAULT_KEY, DELAY_LABEL_KEY,
        DELAY_OVERRIDE_KEY, DELAY_UNIT_KEY, DELAY_TOOLTIP_KEY, TEAMWORK_KEY);

    private PasteConnectionSupport() {
    }

    /**
     * One entry of the warning dropdown.
     *
     * @param storedValue what {@code ServerConnection.setPasteWarningMode} stores; {@code null} follows the
     *                    global mode
     * @param label what the dropdown shows
     */
    record ModeChoice(@Nullable PasteWarningMode storedValue, @NotNull String label) {

        ModeChoice {
            Objects.requireNonNull(label, "label");
        }

        /** The dropdown shows the label. */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The warning dropdown's entries: Use the default (naming {@code globalMode}), Off, Unless the program
     * uses bracketed paste, Always.
     *
     * @param globalMode the mode of Settings → Terminal; null means {@link PasteWarningMode#DEFAULT}
     */
    static List<ModeChoice> modeChoices(@Nullable PasteWarningMode globalMode) {
        PasteWarningMode global = globalMode != null ? globalMode : PasteWarningMode.DEFAULT;
        List<ModeChoice> choices = new ArrayList<>();
        choices.add(new ModeChoice(null, I18n.get(MODE_DEFAULT_KEY, I18n.get(modeKey(global)))));
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            choices.add(new ModeChoice(mode, I18n.get(modeKey(mode))));
        }
        return choices;
    }

    /** The entry that shows {@code stored}: its mode, or Use the default for {@code null}. */
    static ModeChoice selectedMode(@NotNull List<ModeChoice> choices, @Nullable PasteWarningMode stored) {
        for (ModeChoice choice : choices) {
            if (choice.storedValue() == stored) {
                return choice;
            }
        }
        return choices.getFirst();
    }

    /** The mode to store for {@code choice}; {@code null} (also for no choice) follows the global mode. */
    static @Nullable PasteWarningMode storedMode(@Nullable ModeChoice choice) {
        return choice != null ? choice.storedValue() : null;
    }

    /** The key of a mode's name, the one the Settings page shows. */
    static String modeKey(@NotNull PasteWarningMode mode) {
        return switch (mode) {
            case OFF -> "settings.terminal.paste.mode.off";
            case UNLESS_BRACKETED -> "settings.terminal.paste.mode.unlessBracketed";
            case ALWAYS -> "settings.terminal.paste.mode.always";
        };
    }

    /**
     * The pause the spinner starts with: the connection's own, else the global one, so ticking the box
     * starts from the pause that applies now.
     */
    static int initialLineDelay(@Nullable Integer stored, int globalDelayMs) {
        return PastePacer.clampLineDelayMs(stored != null ? stored : globalDelayMs);
    }

    /**
     * The pause to store: the spinner's value, clamped to {@code 0..1000}, while the box is ticked (0 then
     * pastes this connection at once whatever Settings says); {@code null}, which follows Settings, without
     * the tick.
     */
    static @Nullable Integer storedLineDelay(boolean ownDelay, @Nullable Integer spinnerValue) {
        if (!ownDelay) {
            return null;
        }
        return PastePacer.clampLineDelayMs(spinnerValue != null ? spinnerValue : 0);
    }
}
