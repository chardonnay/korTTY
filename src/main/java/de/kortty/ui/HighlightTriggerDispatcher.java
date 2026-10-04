package de.kortty.ui;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.TerminalOutputHighlighter.LineMatch;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.policy.EffectivePolicy;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Decides what the triggers of a pane's keyword highlighting lead to: a highlight rule with the
 * notification action ({@link HighlightRule.Action#NOTIFY}) matched new output, and the pane's
 * highlighter reported it ({@link LineMatch}). FX-free, so the rules below are unit-tested;
 * {@link TerminalAttentionNotifier#onHighlightTrigger} applies the result to the tab and the desktop.
 *
 * <ul>
 *   <li>Nothing at all while triggers are not allowed: the enterprise policy key
 *       {@code terminal-triggers} or the user's switch in Settings → Terminal is off.</li>
 *   <li>Otherwise as every terminal notification ({@link TerminalNotificationPolicy#decideTrigger}):
 *       nothing in a tab the user is looking at or for output that answers mirrored keys; else the tab's
 *       mark, and a desktop notification at most once per rule and pane every 30 seconds.</li>
 *   <li>The notification is titled {@code korTTY · <tab>} like every terminal notification, so output
 *       can never make it look like a message from another application. Its text is the rule's name (or
 *       its pattern when it has none) — never terminal output, unless the rule asks for the matched text,
 *       which is then cleaned of control and bidi characters and shortened.</li>
 * </ul>
 */
final class HighlightTriggerDispatcher {

    /** At most this many characters of a rule's name go into a notification or the tab's tooltip. */
    static final int MAX_RULE_LABEL_CHARS = 80;

    /**
     * At most this many characters of matched text go into a notification, so name and text together
     * stay within the desktop notifier's 200 characters.
     */
    static final int MAX_MATCHED_TEXT_CHARS = 100;

    /** The notification text with the matched text: {0} the rule's name, {1} the text. */
    static final String BODY_WITH_TEXT_KEY = "terminal.notify.trigger.bodyWithText";

    /** The tab's tooltip line: {0} the rule's name. */
    static final String TOOLTIP_KEY = "terminal.notify.trigger.tooltip";

    /** What a rule whose name holds nothing visible is called. */
    static final String UNNAMED_KEY = "terminal.notify.trigger.unnamed";

    /** Every key this class reads, for the i18n coverage test. */
    static final List<String> KEYS = List.of(BODY_WITH_TEXT_KEY, TOOLTIP_KEY, UNNAMED_KEY);

    /**
     * What one trigger leads to.
     *
     * @param ruleId  the rule that matched
     * @param badge   whether the tab gets the attention mark, with {@code tooltip}
     * @param toast   whether a desktop notification is shown, with {@code title} and {@code body}
     */
    record Notice(String ruleId, boolean badge, boolean toast, String title, String body, String tooltip) {
    }

    private final TerminalNotificationPolicy policy;

    private final BooleanSupplier triggersAllowed;

    /**
     * @param policy          the notification policy every terminal notification shares
     * @param triggersAllowed whether trigger actions may run now ({@link #triggersAllowed(GlobalSettings,
     *                        EffectivePolicy)})
     */
    HighlightTriggerDispatcher(TerminalNotificationPolicy policy, BooleanSupplier triggersAllowed) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.triggersAllowed = Objects.requireNonNull(triggersAllowed, "triggersAllowed");
    }

    /**
     * Decides about the triggers one pass of a pane's highlighter reported. JavaFX thread (the policy is
     * not thread-safe).
     *
     * @param slot    the notification slot: the pane, or the multi-exec session it takes part in
     * @param state   what is known about the pane now
     * @param tabName the tab's visible name, for the notification's title
     * @param matches what the pane's highlighter reported
     * @param i18n    the translations, {@code I18n::get}
     * @return one notice per trigger that leads to something, in rule order
     */
    List<Notice> dispatch(Object slot, PaneState state, @Nullable String tabName, @Nullable List<LineMatch> matches,
            BiFunction<String, Object[], String> i18n) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(state, "state");
        List<Notice> notices = new ArrayList<>();
        if (matches == null || matches.isEmpty() || !allowed()) {
            return notices;
        }
        for (LineMatch match : matches) {
            CompiledHighlightSet.Rule rule = match != null ? match.rule() : null;
            if (rule == null || rule.action() != HighlightRule.Action.NOTIFY) {
                continue;
            }
            Decision decision = policy.decideTrigger(slot, rule.ruleId(), state);
            if (!decision.badge() && !decision.toast()) {
                continue;
            }
            notices.add(new Notice(rule.ruleId(), decision.badge(), decision.toast(),
                TerminalAttentionNotifier.toastTitle(tabName), body(rule, match.matchedText(), i18n),
                tooltip(rule, i18n)));
        }
        return notices;
    }

    private boolean allowed() {
        try {
            return triggersAllowed.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether trigger actions may run: the policy allows them and the user's switch is on (a fresh
     * installation's when the settings cannot be read).
     */
    static boolean triggersAllowed(@Nullable GlobalSettings settings, @Nullable EffectivePolicy policy) {
        boolean userSwitch = settings == null || settings.isTerminalTriggersEnabled();
        return userSwitch && (policy == null || policy.terminalTriggersAllowed());
    }

    /**
     * A notification's text: the rule's name; with the rule's consent ({@link
     * CompiledHighlightSet.Rule#notifyWithText()}) followed by the matched text, both without control or
     * bidi characters and shortened.
     *
     * @param matchedText the text the highlighter reported, or {@code null}
     */
    static String body(CompiledHighlightSet.Rule rule, @Nullable String matchedText,
            BiFunction<String, Object[], String> i18n) {
        String label = label(rule, i18n);
        if (!rule.notifyWithText() || matchedText == null) {
            return label;
        }
        String text = DisplayTextSanitizer.sanitize(matchedText, MAX_MATCHED_TEXT_CHARS);
        return text.isEmpty() ? label : i18n.apply(BODY_WITH_TEXT_KEY, new Object[] {label, text});
    }

    /** The tab's tooltip line for a rule: its name, never output. */
    static String tooltip(CompiledHighlightSet.Rule rule, BiFunction<String, Object[], String> i18n) {
        return i18n.apply(TOOLTIP_KEY, new Object[] {label(rule, i18n)});
    }

    /** The rule's name (or pattern) as shown: cleaned and shortened, or a generic name when nothing is left. */
    static String label(CompiledHighlightSet.Rule rule, BiFunction<String, Object[], String> i18n) {
        String label = DisplayTextSanitizer.sanitize(rule.label(), MAX_RULE_LABEL_CHARS);
        return label.isEmpty() ? i18n.apply(UNNAMED_KEY, new Object[0]) : label;
    }
}
