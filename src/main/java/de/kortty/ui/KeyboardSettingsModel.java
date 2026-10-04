package de.kortty.ui;

import de.kortty.core.KeyChord;
import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeyChord.Physical;
import de.kortty.core.KeymapOverrides;
import de.kortty.core.KeymapOverrides.Problem;
import de.kortty.core.KeymapOverrides.Rules;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the Settings → Keyboard page shows and edits, free of JavaFX controls so it runs in plain
 * unit tests: the actions of the frontmost window's menu bar with their default chords
 * ({@link Catalog}), the user's shortcut overrides being edited, and for every action the chord
 * it would get on saving, with why an override is not in effect or which other action shares its
 * chord.
 *
 * <p>The rules are those of {@link KeymapOverrides}: a chord that breaks one (the shell needs it,
 * it types a character, it is one of the terminal's or the system's own keys, a fixed korTTY
 * shortcut uses it, it has no modifier) is refused when it is recorded and never enters the
 * overrides. A chord another action uses is taken, so a swap can be done in two steps, but both
 * actions are then marked and {@link #canSave()} is false until each chord is used once: the page
 * blocks saving with a badge. A stored override that breaks a rule on this computer (a settings
 * file shared with a Mac, where Cmd+L is fine but Ctrl+L belongs to the shell) is shown as not in
 * effect and kept on saving, as are overrides of actions this version does not know.
 */
final class KeyboardSettingsModel {

    /** One action of the page: a rebindable menu action or a fixed shortcut listed for reference. */
    record Action(@NotNull String id, @NotNull String label, @NotNull String category,
                  @Nullable KeyChord defaultChord, boolean fixed, @Nullable String keysText) {
        Action {
            Objects.requireNonNull(id, "id");
            label = label != null && !label.isBlank() ? label : id;
            category = category != null ? category : "";
        }

        static Action rebindable(String id, String label, String category, @Nullable KeyChord defaultChord) {
            return new Action(id, label, category, defaultChord, false, null);
        }

        static Action fixed(String id, String label, String category, @Nullable KeyChord chord, @Nullable String keysText) {
            return new Action(id, label, category, chord, true, keysText);
        }
    }

    /**
     * The actions the page lists, in menu order, and the rules a chord is checked against on the
     * platform {@code os}.
     */
    record Catalog(@NotNull Os os, @NotNull List<Action> actions, @NotNull Rules rules) {
        Catalog {
            Objects.requireNonNull(os, "os");
            actions = List.copyOf(actions);
            Objects.requireNonNull(rules, "rules");
        }

        /** Every rebindable action with its default chord ({@code null} for none), in menu order. */
        Map<String, KeyChord> defaults() {
            Map<String, KeyChord> defaults = new LinkedHashMap<>();
            for (Action action : actions) {
                if (!action.fixed()) {
                    defaults.put(action.id(), action.defaultChord());
                }
            }
            return defaults;
        }

        @Nullable Action action(String id) {
            for (Action action : actions) {
                if (action.id().equals(id)) {
                    return action;
                }
            }
            return null;
        }
    }

    /** What a row shows in its status column. */
    enum Status {
        /** The default chord, no override. */
        DEFAULT,
        /** The user chose another chord, or removed the shortcut. */
        CHANGED,
        /** A stored override breaks a rule on this computer; the default stays in effect. */
        NOT_IN_EFFECT,
        /** Another action uses the same chord; saving is blocked. */
        CONFLICT,
        /** A korTTY shortcut that cannot be changed. */
        FIXED
    }

    /**
     * One row of the page.
     *
     * @param chord        the chord the action gets on saving; {@code null} for none
     * @param overridden   whether the overrides hold an entry for the action (also one not in effect)
     * @param problem      why a stored override is not in effect ({@link Status#NOT_IN_EFFECT})
     * @param otherAction  the action that shares the chord ({@link Status#CONFLICT})
     */
    record Row(@NotNull Action action, @Nullable KeyChord chord, @NotNull Status status, boolean overridden,
               @Nullable Problem problem, @Nullable String otherAction) {

        String id() {
            return action.id();
        }

        boolean editable() {
            return !action.fixed();
        }
    }

    /**
     * The outcome of an edit.
     *
     * @param applied  whether the overrides changed
     * @param problem  why a chord was refused, or {@link Problem#CONFLICT} when it was taken but
     *                 another action uses it too; {@code null} otherwise
     * @param message  one sentence for the page, in the UI language
     */
    record Edit(boolean applied, @Nullable Problem problem, @NotNull String message) {
    }

    static final String TAB_KEY = "settings.tab.keyboard";
    static final String HEADER_KEY = "settings.keyboard.header";
    static final String DESCRIPTION_KEY = "settings.keyboard.description";
    static final String NONE_KEY = "settings.keyboard.none";
    static final String STATUS_CHANGED_KEY = "settings.keyboard.status.changed";
    static final String STATUS_REMOVED_KEY = "settings.keyboard.status.removed";
    static final String STATUS_FIXED_KEY = "settings.keyboard.status.fixed";
    static final String STATUS_CONFLICT_KEY = "settings.keyboard.status.conflict";
    static final String STATUS_NOT_IN_EFFECT_KEY = "settings.keyboard.status.notInEffect";
    static final String FIXED_ROW_KEY = "settings.keyboard.fixedRow";
    static final String ASSIGNED_KEY = "settings.keyboard.assigned";
    static final String REMOVED_KEY = "settings.keyboard.removed";
    static final String RESTORED_KEY = "settings.keyboard.restored";
    static final String CONFLICTS_KEY = "settings.keyboard.conflicts";
    static final String FIXED_TAB_JUMP_KEY = "settings.keyboard.fixed.tabJump";
    static final String FIXED_NEXT_TAB_KEY = "settings.keyboard.fixed.nextTab";
    static final String FIXED_PREVIOUS_TAB_KEY = "settings.keyboard.fixed.previousTab";
    static final String CATEGORY_TABS_KEY = "settings.keyboard.category.tabs";

    /** The message of each {@link Problem}: {0} the chord, {1} the other action (or the action itself). */
    static final Map<Problem, String> PROBLEM_KEYS = problemKeys();

    /**
     * The settings.keyboard key that names each fixed shortcut without a menu item of its own, by the
     * owner id {@link KeymapSupport#fixedOwner} reports; menu actions are named by their menu label.
     */
    static final Map<String, String> FIXED_OWNER_KEYS = Map.of(
        KeymapSupport.FIXED_TAB_JUMP, FIXED_TAB_JUMP_KEY,
        KeymapSupport.FIXED_NEXT_TAB, FIXED_NEXT_TAB_KEY,
        KeymapSupport.FIXED_PREVIOUS_TAB, FIXED_PREVIOUS_TAB_KEY);

    /** Every key this model reads, for the i18n coverage test. */
    static final List<String> KEYS = keys();

    private final Catalog catalog;
    private final Map<String, KeyChord> defaults;
    private final KeymapOverrides stored;
    private KeymapOverrides pending;

    /**
     * @param catalog the actions and rules of the frontmost window
     * @param stored  the overrides in the global settings
     */
    KeyboardSettingsModel(@NotNull Catalog catalog, @NotNull KeymapOverrides stored) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.defaults = catalog.defaults();
        this.stored = Objects.requireNonNull(stored, "stored");
        this.pending = stored;
    }

    @NotNull Catalog catalog() {
        return catalog;
    }

    /** The overrides as edited, to be stored on saving. */
    @NotNull KeymapOverrides overrides() {
        return pending;
    }

    /** Whether the overrides differ from the stored ones. */
    boolean hasChanges() {
        return !pending.equals(stored);
    }

    /**
     * The chord every rebindable action gets on saving, in menu order: its override where one is in
     * effect, else its default; {@code null} for an action without a shortcut.
     */
    @NotNull Map<String, KeyChord> effective() {
        Map<String, KeyChord> effective = new LinkedHashMap<>(defaults);
        for (String id : defaults.keySet()) {
            if (pending.overrides(id) && problemOfOverride(id) == null) {
                effective.put(id, pending.chord(id));
            }
        }
        return effective;
    }

    /**
     * Why the override of {@code actionId} is not in effect on this computer; {@code null} when it
     * is, when it is the action's default or when there is none.
     */
    @Nullable Problem problemOfOverride(String actionId) {
        if (!pending.overrides(actionId) || !defaults.containsKey(actionId)) {
            return null;
        }
        KeyChord chord = pending.chord(actionId);
        if (Objects.equals(chord, defaults.get(actionId))) {
            return null;
        }
        if (chord == null) {
            return catalog.rules().required().contains(actionId) ? Problem.REQUIRED : null;
        }
        return KeymapOverrides.problemOf(chord, catalog.rules());
    }

    /** The chords that more than one action would get on saving, each with those actions. */
    @NotNull Map<Physical, List<String>> conflicts() {
        return KeymapOverrides.conflicts(effective(), catalog.os());
    }

    /** How many chords more than one action uses; the badge shows it. */
    int conflictCount() {
        return conflicts().size();
    }

    /** Whether the page may save: every chord is used by one action only. */
    boolean canSave() {
        return conflicts().isEmpty();
    }

    /**
     * Gives {@code actionId} the recorded {@code chord}. Its default chord only removes the
     * override. A chord that breaks a rule is refused; one another action uses is taken and both
     * are marked as a conflict.
     */
    @NotNull Edit assign(@NotNull String actionId, @NotNull KeyChord chord) {
        Objects.requireNonNull(chord, "chord");
        Action action = editableAction(actionId);
        if (chord.equals(action.defaultChord())) {
            pending = pending.without(actionId);
            return afterEdit(action, I18n.get(RESTORED_KEY, action.label()));
        }
        Problem problem = KeymapOverrides.problemOf(chord, catalog.rules());
        if (problem != null) {
            String owner = problem == Problem.FIXED ? catalog.rules().fixedOwner().apply(chord) : null;
            return new Edit(false, problem, problemText(problem, chord, owner != null ? owner : actionId));
        }
        pending = pending.with(actionId, chord);
        return afterEdit(action, I18n.get(ASSIGNED_KEY, chordText(chord), action.label()));
    }

    /** Leaves {@code actionId} without a shortcut; refused for an action that must keep one. */
    @NotNull Edit remove(@NotNull String actionId) {
        Action action = editableAction(actionId);
        if (catalog.rules().required().contains(actionId)) {
            return new Edit(false, Problem.REQUIRED, problemText(Problem.REQUIRED, action.defaultChord(), actionId));
        }
        pending = action.defaultChord() == null ? pending.without(actionId) : pending.with(actionId, null);
        return afterEdit(action, I18n.get(REMOVED_KEY, action.label()));
    }

    /** Puts {@code actionId} back on its default chord. */
    @NotNull Edit reset(@NotNull String actionId) {
        Action action = editableAction(actionId);
        pending = pending.without(actionId);
        return afterEdit(action, I18n.get(RESTORED_KEY, action.label()));
    }

    /**
     * Puts every action of the page back on its default chord. Overrides of actions this version
     * does not know stay, so a newer korTTY's bindings survive.
     */
    void resetAll() {
        KeymapOverrides reset = pending;
        for (String id : pending.actionIds()) {
            if (defaults.containsKey(id)) {
                reset = reset.without(id);
            }
        }
        pending = reset;
    }

    /** Every row, in catalog order. */
    @NotNull List<Row> rows() {
        Map<String, KeyChord> effective = effective();
        Map<String, String> sharing = new LinkedHashMap<>();
        for (List<String> actions : KeymapOverrides.conflicts(effective, catalog.os()).values()) {
            for (String id : actions) {
                sharing.put(id, actions.stream().filter(other -> !other.equals(id)).findFirst().orElse(null));
            }
        }
        List<Row> rows = new ArrayList<>(catalog.actions().size());
        for (Action action : catalog.actions()) {
            rows.add(row(action, effective, sharing));
        }
        return rows;
    }

    /**
     * The rows whose action, menu or shortcut contains {@code filter} (ignoring case), and with
     * {@code onlyChanged} only the overridden and conflicting ones.
     */
    @NotNull List<Row> rows(@Nullable String filter, boolean onlyChanged) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (Row row : rows()) {
            if (onlyChanged && !row.overridden() && row.status() != Status.CONFLICT) {
                continue;
            }
            if (!needle.isEmpty() && !searchText(row).contains(needle)) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    /** The row of {@code actionId}, or {@code null} when the page does not list it. */
    @Nullable Row row(String actionId) {
        for (Row row : rows()) {
            if (row.id().equals(actionId)) {
                return row;
            }
        }
        return null;
    }

    /** The override entries of actions this version does not know, kept on saving but not used. */
    @NotNull List<String> unknownActionIds() {
        List<String> unknown = new ArrayList<>();
        for (String id : pending.actionIds()) {
            if (!defaults.containsKey(id)) {
                unknown.add(id);
            }
        }
        return unknown;
    }

    /** The notice about overrides of unknown actions, or {@code ""} when there are none. */
    @NotNull String unknownActionsText() {
        List<String> unknown = unknownActionIds();
        return unknown.isEmpty() ? "" : problemText(Problem.UNKNOWN_ACTION, null, String.join(", ", unknown));
    }

    /** The banner above the table while chords are used twice, or {@code ""}. */
    @NotNull String conflictsText() {
        Map<Physical, List<String>> conflicts = conflicts();
        if (conflicts.isEmpty()) {
            return "";
        }
        Map<String, KeyChord> effective = effective();
        Set<String> chords = new LinkedHashSet<>();
        for (List<String> actions : conflicts.values()) {
            chords.add(chordText(effective.get(actions.get(0))));
        }
        return I18n.get(CONFLICTS_KEY, String.join(", ", chords));
    }

    /** {@code chord} as a person reads it on the catalog's platform, or "None". */
    @NotNull String chordText(@Nullable KeyChord chord) {
        return chord == null ? I18n.get(NONE_KEY) : chord.displayLabel(catalog.os());
    }

    /** The keys a row shows in its shortcut column. */
    @NotNull String shortcutText(@NotNull Row row) {
        if (row.action().fixed() && row.action().keysText() != null) {
            return row.action().keysText();
        }
        return chordText(row.chord());
    }

    /** The short text of a row's status column; {@code ""} for an action on its default. */
    @NotNull String statusText(@NotNull Row row) {
        return switch (row.status()) {
            case DEFAULT -> "";
            case CHANGED -> I18n.get(row.chord() == null ? STATUS_REMOVED_KEY : STATUS_CHANGED_KEY);
            case NOT_IN_EFFECT -> I18n.get(STATUS_NOT_IN_EFFECT_KEY, chordText(pending.chord(row.id())));
            case CONFLICT -> I18n.get(STATUS_CONFLICT_KEY, actionLabel(row.otherAction()));
            case FIXED -> I18n.get(STATUS_FIXED_KEY);
        };
    }

    /** The explanation of a row's status, for its tooltip and the editor below the table; {@code ""} if none. */
    @NotNull String detailText(@NotNull Row row) {
        return switch (row.status()) {
            case FIXED -> I18n.get(FIXED_ROW_KEY);
            case NOT_IN_EFFECT -> problemText(Objects.requireNonNull(row.problem()), pending.chord(row.id()),
                row.problem() == Problem.FIXED
                    ? catalog.rules().fixedOwner().apply(pending.chord(row.id())) : row.id());
            case CONFLICT -> problemText(Problem.CONFLICT, row.chord(), row.otherAction());
            default -> "";
        };
    }

    /**
     * The sentence for {@code problem}: {0} is {@code chord} as the user reads it, {1} names
     * {@code other} (the other action of a conflict, the fixed shortcut, or the action itself).
     */
    @NotNull String problemText(@NotNull Problem problem, @Nullable KeyChord chord, @Nullable String other) {
        String otherText = problem == Problem.UNKNOWN_ACTION ? Objects.toString(other, "") : actionLabel(other);
        if (problem == Problem.UNKNOWN_ACTION) {
            return I18n.get(PROBLEM_KEYS.get(problem), otherText);
        }
        return I18n.get(PROBLEM_KEYS.get(problem), chordText(chord), otherText);
    }

    /**
     * The name of an action or a fixed shortcut: its label in the catalog, the settings.keyboard text
     * of a fixed shortcut without a menu item, the i18n text of a menu key the catalog lacks, or the id.
     */
    @NotNull String actionLabel(@Nullable String id) {
        if (id == null) {
            return "";
        }
        Action action = catalog.action(id);
        if (action != null) {
            return action.label();
        }
        String key = FIXED_OWNER_KEYS.get(id);
        if (key != null) {
            return I18n.get(key);
        }
        String text = I18n.get(id);
        return text == null || text.isBlank() ? id : stripEllipsis(text);
    }

    private Row row(Action action, Map<String, KeyChord> effective, Map<String, String> sharing) {
        if (action.fixed()) {
            return new Row(action, action.defaultChord(), Status.FIXED, false, null, null);
        }
        String id = action.id();
        KeyChord chord = effective.get(id);
        boolean overridden = pending.overrides(id) && !Objects.equals(pending.chord(id), action.defaultChord());
        Problem problem = problemOfOverride(id);
        if (sharing.containsKey(id)) {
            return new Row(action, chord, Status.CONFLICT, overridden, problem, sharing.get(id));
        }
        if (problem != null) {
            return new Row(action, chord, Status.NOT_IN_EFFECT, true, problem, null);
        }
        return new Row(action, chord, overridden ? Status.CHANGED : Status.DEFAULT, overridden, null, null);
    }

    private Edit afterEdit(Action action, String message) {
        for (List<String> actions : conflicts().values()) {
            if (actions.contains(action.id())) {
                String other = actions.stream().filter(id -> !id.equals(action.id())).findFirst().orElse(null);
                return new Edit(true, Problem.CONFLICT,
                    problemText(Problem.CONFLICT, effective().get(action.id()), other));
            }
        }
        return new Edit(true, null, message);
    }

    private Action editableAction(String actionId) {
        Action action = catalog.action(actionId);
        if (action == null || action.fixed()) {
            throw new IllegalArgumentException("Not a rebindable action: " + actionId);
        }
        return action;
    }

    private String searchText(Row row) {
        return (row.action().label() + "\n" + row.action().category() + "\n" + shortcutText(row) + "\n"
            + statusText(row)).toLowerCase(Locale.ROOT);
    }

    private static String stripEllipsis(String text) {
        String label = text.strip();
        if (label.endsWith("...")) {
            label = label.substring(0, label.length() - 3);
        } else if (label.endsWith("…")) {
            label = label.substring(0, label.length() - 1);
        }
        return label.strip();
    }

    private static Map<Problem, String> problemKeys() {
        Map<Problem, String> keys = new EnumMap<>(Problem.class);
        keys.put(Problem.UNKNOWN_ACTION, "settings.keyboard.problem.unknownAction");
        keys.put(Problem.NEEDS_MODIFIER, "settings.keyboard.problem.needsModifier");
        keys.put(Problem.RESERVED_SHELL, "settings.keyboard.problem.reservedShell");
        keys.put(Problem.ALTGR_RANGE, "settings.keyboard.problem.altgrRange");
        keys.put(Problem.TERMINAL, "settings.keyboard.problem.terminal");
        keys.put(Problem.SYSTEM, "settings.keyboard.problem.system");
        keys.put(Problem.FIXED, "settings.keyboard.problem.fixed");
        keys.put(Problem.REQUIRED, "settings.keyboard.problem.required");
        keys.put(Problem.CONFLICT, "settings.keyboard.problem.conflict");
        return java.util.Collections.unmodifiableMap(keys);
    }

    private static List<String> keys() {
        List<String> keys = new ArrayList<>(List.of(TAB_KEY, HEADER_KEY, DESCRIPTION_KEY, NONE_KEY,
            STATUS_CHANGED_KEY, STATUS_REMOVED_KEY, STATUS_FIXED_KEY, STATUS_CONFLICT_KEY, STATUS_NOT_IN_EFFECT_KEY,
            FIXED_ROW_KEY, ASSIGNED_KEY, REMOVED_KEY, RESTORED_KEY, CONFLICTS_KEY, CATEGORY_TABS_KEY));
        keys.addAll(PROBLEM_KEYS.values());
        keys.addAll(FIXED_OWNER_KEYS.values());
        return List.copyOf(new LinkedHashSet<>(keys));
    }
}
