package de.kortty.ui;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightPreview;
import de.kortty.core.highlight.HighlightRuleValidator;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the highlight rule-set editor works on, without any JavaFX: the built-in sets (read-only, for
 * looking at and duplicating) and working copies of the user's sets, which reach
 * {@link GlobalSettings#getHighlightRuleSets()} only through {@link #applyTo(GlobalSettings)} when the
 * dialog is confirmed. Cancel therefore discards every change.
 *
 * <p>Validation is {@link HighlightRuleValidator}'s, the same checks the terminal compiles with, so a rule
 * the editor accepts is a rule a pane runs. The editor refuses to save while any problem is left
 * ({@link #firstProblem()}), even in a switched-off rule: a rule that cannot work is fixed or removed,
 * not stored.
 */
final class HighlightRulesEditorModel {

    /** The name of a new set. */
    static final String NEW_SET_NAME_KEY = "highlight.editor.newSetName";
    /** What the editor calls a user set whose name was cleared. */
    static final String UNNAMED_KEY = "highlight.editor.unnamed";
    /** The name of a copy; {0} is the source's name. */
    static final String COPY_NAME_KEY = "highlight.editor.copyName";
    /** A problem of one rule in the footer: {0} the set, {1} the rule's number, {2} the message. */
    static final String PROBLEM_RULE_KEY = "highlight.editor.problem.rule";
    /** A problem of a set as a whole in the footer: {0} the set, {1} the message. */
    static final String PROBLEM_SET_KEY = "highlight.editor.problem.set";

    /** The Check column for a rule that is switched off. */
    static final String STATUS_OFF_KEY = "highlight.editor.status.off";
    /** The Check column for a rule with a validation problem. */
    static final String STATUS_INVALID_KEY = "highlight.editor.status.invalid";
    /** The Check column for a {@link HighlightPreview.Speed#SLOW} rule. */
    static final String STATUS_SLOW_KEY = "highlight.editor.status.slow";
    /** The Check column for a {@link HighlightPreview.Speed#TOO_SLOW} rule. */
    static final String STATUS_TOO_SLOW_KEY = "highlight.editor.status.tooSlow";
    /** The explanation below the rule's details for a slow rule. */
    static final String SLOW_MESSAGE_KEY = "highlight.editor.slowMessage";
    /** The explanation below the rule's details for a rule that ran out of time. */
    static final String TOO_SLOW_MESSAGE_KEY = "highlight.editor.tooSlowMessage";

    static final List<String> KEYS = List.of(NEW_SET_NAME_KEY, UNNAMED_KEY, COPY_NAME_KEY, PROBLEM_RULE_KEY, PROBLEM_SET_KEY,
        STATUS_OFF_KEY, STATUS_INVALID_KEY, STATUS_SLOW_KEY, STATUS_TOO_SLOW_KEY, SLOW_MESSAGE_KEY, TOO_SLOW_MESSAGE_KEY);

    /** What a new rule looks like until the user changes it: bold yellow, a whole word in any case. */
    static final String NEW_RULE_FOREGROUND = "ansi:3";

    /**
     * Something that keeps the editor from saving.
     *
     * @param set the set it is in, or {@code null} for a problem of the list of sets as a whole
     * @param ruleIndex the index of the rule in the set, or -1 for a problem of the set itself
     * @param key the {@link HighlightRuleValidator} message key
     */
    record Problem(@Nullable HighlightRuleSet set, int ruleIndex, @NotNull String key) {

        /** The footer text: which set, which rule and what is wrong. */
        String message() {
            String text = I18n.get(key, HighlightRuleValidator.messageArguments(key));
            if (set == null) {
                return text;
            }
            String setName = displayName(set);
            return ruleIndex >= 0
                ? I18n.get(PROBLEM_RULE_KEY, setName, ruleIndex + 1, text)
                : I18n.get(PROBLEM_SET_KEY, setName, text);
        }
    }

    private final List<HighlightRuleSet> builtins;

    private final List<HighlightRuleSet> userSets = new ArrayList<>();

    private final Set<String> storedIds = new LinkedHashSet<>();

    private final String storedSignature;

    /** Starts from deep copies of {@code storedUserSets}; the stored objects are never touched. */
    HighlightRulesEditorModel(@Nullable List<HighlightRuleSet> storedUserSets) {
        this.builtins = HighlightBuiltinSets.all();
        if (storedUserSets != null) {
            for (HighlightRuleSet set : storedUserSets) {
                if (set != null) {
                    userSets.add(new HighlightRuleSet(set));
                    storedIds.add(set.getId());
                }
            }
        }
        this.storedSignature = signature(userSets);
    }

    /** The working copies of the user's sets, in their stored order. */
    List<HighlightRuleSet> userSets() {
        return Collections.unmodifiableList(userSets);
    }

    /** The built-ins, then the user's sets: the order of the editor's list and the menus. */
    List<HighlightRuleSet> allSets() {
        List<HighlightRuleSet> all = new ArrayList<>(builtins);
        all.addAll(userSets);
        return all;
    }

    /** The set with this id, built-in or user, or {@code null}. */
    @Nullable HighlightRuleSet find(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (HighlightRuleSet set : allSets()) {
            if (id.trim().equals(set.getId())) {
                return set;
            }
        }
        return null;
    }

    /**
     * Built-in sets are shipped with korTTY and can only be looked at or duplicated. A stored set that
     * merely carries a reserved id (a hand-edited file) is the user's: it can be fixed by deleting it.
     */
    boolean isReadOnly(@Nullable HighlightRuleSet set) {
        return set == null || !userSets.contains(set);
    }

    /**
     * A set's name as the editor shows it: a built-in's translated name, a user set's own name, or
     * "Unnamed rule set" while the user has cleared it (never the internal id).
     */
    static String displayName(@NotNull HighlightRuleSet set) {
        if (!HighlightBuiltinSets.isBuiltin(set.getId()) && (set.getName() == null || set.getName().isBlank())) {
            return I18n.get(UNNAMED_KEY);
        }
        return HighlightMenuSupport.label(set.getId(), id -> set.getName().trim());
    }

    /** Whether another user set may be created ({@link HighlightRuleValidator#MAX_USER_SETS}). */
    boolean canAddSet() {
        return userSets.size() < HighlightRuleValidator.MAX_USER_SETS;
    }

    /** A new user set with one empty rule, so the user can start typing; {@code null} at the limit. */
    @Nullable HighlightRuleSet addSet() {
        if (!canAddSet()) {
            return null;
        }
        HighlightRuleSet set = new HighlightRuleSet();
        set.setName(uniqueName(I18n.get(NEW_SET_NAME_KEY)));
        set.getRules().add(newRule());
        userSets.add(set);
        return set;
    }

    /**
     * A copy of {@code source} — built-in or user — under fresh ids and a "(copy)" name, added after the
     * user's sets; {@code null} at the limit.
     */
    @Nullable HighlightRuleSet duplicate(@NotNull HighlightRuleSet source) {
        Objects.requireNonNull(source, "source");
        if (!canAddSet()) {
            return null;
        }
        HighlightRuleSet copy = source.duplicate(uniqueName(I18n.get(COPY_NAME_KEY, displayName(source))));
        userSets.add(copy);
        return copy;
    }

    /** Removes a user set; a built-in cannot be removed. Returns whether something was removed. */
    boolean delete(@Nullable HighlightRuleSet set) {
        return set != null && !isReadOnly(set) && userSets.remove(set);
    }

    /** Whether {@code set} may get another rule: a user set below {@link HighlightRuleValidator#MAX_RULES_PER_SET}. */
    boolean canAddRule(@Nullable HighlightRuleSet set) {
        return set != null && !isReadOnly(set) && userSets.contains(set)
            && set.getRules().size() < HighlightRuleValidator.MAX_RULES_PER_SET;
    }

    /**
     * Adds a new rule to a user set right below {@code afterIndex} (at the end for -1), or returns
     * {@code null} when the set cannot take one.
     */
    @Nullable HighlightRule addRule(@Nullable HighlightRuleSet set, int afterIndex) {
        if (!canAddRule(set)) {
            return null;
        }
        List<HighlightRule> rules = set.getRules();
        int at = afterIndex >= 0 && afterIndex < rules.size() ? afterIndex + 1 : rules.size();
        HighlightRule rule = newRule();
        rules.add(at, rule);
        return rule;
    }

    /** Removes a rule from a user set. */
    boolean removeRule(@Nullable HighlightRuleSet set, @Nullable HighlightRule rule) {
        return set != null && rule != null && !isReadOnly(set) && set.getRules().remove(rule);
    }

    /**
     * Moves the rule at {@code index} of a user set by {@code delta} places — up the list is up in
     * priority. Returns the rule's new index, or -1 when it cannot move.
     */
    int moveRule(@Nullable HighlightRuleSet set, int index, int delta) {
        if (set == null || isReadOnly(set)) {
            return -1;
        }
        List<HighlightRule> rules = set.getRules();
        int target = index + delta;
        if (index < 0 || index >= rules.size() || target < 0 || target >= rules.size() || delta == 0) {
            return -1;
        }
        HighlightRule rule = rules.remove(index);
        rules.add(target, rule);
        return target;
    }

    /** The problems of one rule, as message keys; empty for a usable rule. */
    static List<String> ruleProblems(@Nullable HighlightRule rule) {
        return HighlightRuleValidator.validateRule(rule);
    }

    /**
     * The key of the short text in the rules table's Check column, or {@code null} when there is nothing
     * to say. A problem comes first (the rule cannot run), then a switched-off rule (it does not run),
     * then how the rule fared in the preview.
     */
    static @Nullable String statusKey(@Nullable HighlightRule rule, @Nullable HighlightPreview.RuleStats stats) {
        if (rule == null) {
            return null;
        }
        if (!ruleProblems(rule).isEmpty()) {
            return STATUS_INVALID_KEY;
        }
        if (!rule.isEnabled()) {
            return STATUS_OFF_KEY;
        }
        if (stats == null || !stats.evaluated()) {
            return null;
        }
        return switch (stats.speed()) {
            case TOO_SLOW -> STATUS_TOO_SLOW_KEY;
            case SLOW -> STATUS_SLOW_KEY;
            case OK -> null;
        };
    }

    /** What the editor explains below the selected rule: every problem, then a speed warning. */
    static List<String> ruleMessages(@Nullable HighlightRule rule, @Nullable HighlightPreview.RuleStats stats) {
        List<String> messages = new ArrayList<>();
        if (rule == null) {
            return messages;
        }
        for (String key : ruleProblems(rule)) {
            messages.add(I18n.get(key, HighlightRuleValidator.messageArguments(key)));
        }
        if (messages.isEmpty() && rule.isEnabled() && stats != null && stats.evaluated()) {
            if (stats.speed() == HighlightPreview.Speed.TOO_SLOW) {
                messages.add(I18n.get(TOO_SLOW_MESSAGE_KEY));
            } else if (stats.speed() == HighlightPreview.Speed.SLOW) {
                messages.add(I18n.get(SLOW_MESSAGE_KEY));
            }
        }
        return messages;
    }

    /** The rules table's Hits column: the number of places the rule highlights, or a dash when it does not run. */
    static String hitsText(@Nullable HighlightPreview.RuleStats stats) {
        return stats != null && stats.evaluated() ? Integer.toString(stats.hits()) : "\u2013";
    }

    /**
     * Everything that keeps the editor from saving, in list order: the list of sets as a whole, then per
     * user set its own problems and the first problem of each rule.
     */
    List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        for (String key : HighlightRuleValidator.validateUserSets(userSets)) {
            problems.add(new Problem(null, -1, key));
        }
        for (HighlightRuleSet set : userSets) {
            for (String key : HighlightRuleValidator.validateUserSet(set)) {
                problems.add(new Problem(set, -1, key));
            }
            List<HighlightRule> rules = set.getRules();
            for (int i = 0; i < rules.size(); i++) {
                List<String> ruleProblems = ruleProblems(rules.get(i));
                if (!ruleProblems.isEmpty()) {
                    problems.add(new Problem(set, i, ruleProblems.getFirst()));
                }
            }
        }
        return problems;
    }

    /** The first problem, which the editor's footer shows while OK is disabled. */
    Optional<Problem> firstProblem() {
        List<Problem> problems = problems();
        return problems.isEmpty() ? Optional.empty() : Optional.of(problems.getFirst());
    }

    /** Whether the editor may save: nothing is left to fix. */
    boolean canSave() {
        return problems().isEmpty();
    }

    /** Whether the user's sets differ from what was stored when the editor opened. */
    boolean isModified() {
        return !storedSignature.equals(signature(userSets));
    }

    /** The ids of stored sets that were deleted in the editor. */
    Set<String> deletedIds() {
        Set<String> deleted = new LinkedHashSet<>(storedIds);
        for (HighlightRuleSet set : userSets) {
            deleted.remove(set.getId());
        }
        return deleted;
    }

    /**
     * Stores the user's sets in {@code settings} (as copies, so the editor's working objects stay its
     * own) and, when the default rule set was one of the deleted sets, switches the default back to
     * none, so the settings never point at a set that is gone.
     */
    void applyTo(@NotNull GlobalSettings settings) {
        List<HighlightRuleSet> copies = new ArrayList<>(userSets.size());
        for (HighlightRuleSet set : userSets) {
            HighlightRuleSet copy = new HighlightRuleSet(set);
            if (copy.getName() != null) {
                copy.setName(copy.getName().trim());
            }
            copies.add(copy);
        }
        settings.setHighlightRuleSets(copies);
        String defaultId = settings.getDefaultHighlightRuleSetId();
        if (defaultId != null && deletedIds().contains(defaultId)) {
            settings.setDefaultHighlightRuleSetId(null);
        }
    }

    /** A rule as "Add rule" creates it: empty pattern, whole word, any case, bold yellow. */
    static HighlightRule newRule() {
        HighlightRule rule = new HighlightRule("", false);
        rule.setWholeWord(true);
        rule.setForeground(NEW_RULE_FOREGROUND);
        rule.setBold(true);
        return rule;
    }

    /** {@code base}, or {@code base 2}, {@code base 3} … when a set already has that name. */
    private String uniqueName(String base) {
        Set<String> taken = new LinkedHashSet<>();
        for (HighlightRuleSet set : allSets()) {
            taken.add(displayName(set).trim());
        }
        if (!taken.contains(base.trim())) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + " " + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Everything about the sets that would be stored: ids, names and every rule field. */
    private static String signature(List<HighlightRuleSet> sets) {
        StringBuilder signature = new StringBuilder();
        for (HighlightRuleSet set : sets) {
            signature.append(set.getId()).append('\u0003').append(set.getName()).append('\u0003')
                .append(TerminalHighlightService.signature(set)).append('\u0004');
        }
        return signature.toString();
    }
}
