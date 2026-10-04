package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.TerminalOutputHighlighter.LineMatch;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyDecision;
import de.kortty.policy.PolicyFeature;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What a highlight trigger leads to once the pane's highlighter reported it: nothing while the policy
 * or the user's switch forbids triggers, nothing in a seen tab or for output that answers mirrored keys,
 * otherwise the tab's mark and at most one desktop notification per rule and pane every 30 seconds —
 * titled {@code korTTY · <tab>}, saying the rule's name, and carrying matched text only when the rule
 * asks for it, cleaned of control and bidi characters. That old output (a restored screen, a sweep)
 * never fires at all is the engine's part, pinned in {@code TerminalOutputHighlighterTriggerTest}.
 */
class HighlightTriggerDispatcherTest {

    /** English-like translations that show which key and arguments were used. */
    private static final BiFunction<String, Object[], String> I18N = (key, args) -> switch (key) {
        case HighlightTriggerDispatcher.BODY_WITH_TEXT_KEY -> args[0] + ": " + args[1];
        case HighlightTriggerDispatcher.TOOLTIP_KEY -> "A highlight rule matched new output in this tab: " + args[0];
        case HighlightTriggerDispatcher.UNNAMED_KEY -> "Highlight rule";
        default -> key;
    };

    private static final PaneState UNSEEN = new PaneState(false, false, false, false);

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private final AtomicBoolean allowed = new AtomicBoolean(true);

    private final Object pane = new Object();

    private HighlightTriggerDispatcher dispatcher;

    @BeforeMethod
    void setUp() {
        clock.set(1_000_000L);
        allowed.set(true);
        dispatcher = new HighlightTriggerDispatcher(new TerminalNotificationPolicy(clock::get), allowed::get);
    }

    private static CompiledHighlightSet.Rule rule(String name, String pattern, boolean withText) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setName(name);
        rule.setAction(HighlightRule.Action.NOTIFY);
        rule.setNotifyWithText(withText);
        return CompiledHighlightSet.compile(new HighlightRuleSet("s", "S", List.of(rule))).rule(0);
    }

    private static LineMatch match(CompiledHighlightSet.Rule rule, String text) {
        return new LineMatch(rule, rule.notifyWithText() ? text : null);
    }

    private List<HighlightTriggerDispatcher.Notice> dispatch(PaneState state, LineMatch... matches) {
        return dispatcher.dispatch(pane, state, "web01", List.of(matches), I18N);
    }

    @Test
    void anUnseenTabIsMarkedAndNotifiedWithThePrefixedTitleAndTheRuleName() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space left", false);

        List<HighlightTriggerDispatcher.Notice> notices = dispatch(UNSEEN, match(disk, "No space left on device"));

        assertThat(notices).hasSize(1);
        HighlightTriggerDispatcher.Notice notice = notices.getFirst();
        assertThat(notice.badge()).isTrue();
        assertThat(notice.toast()).isTrue();
        assertThat(notice.title()).isEqualTo("korTTY · web01");
        assertWithMessage("by default the notification names the rule and carries no output")
            .that(notice.body()).isEqualTo("Disk full");
        assertThat(notice.tooltip()).isEqualTo("A highlight rule matched new output in this tab: Disk full");
        assertThat(notice.ruleId()).isEqualTo(disk.ruleId());
    }

    @Test
    void theTitleIsPrefixedEvenForATabNamedLikeAnotherApplication() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space left", false);

        List<HighlightTriggerDispatcher.Notice> notices = dispatcher.dispatch(pane, UNSEEN,
            "‮System Update", List.of(match(disk, "x")), I18N);

        assertThat(notices.getFirst().title()).isEqualTo("korTTY · System Update");
    }

    @Test
    void theMatchedTextIsOptInCleanedAndShortened() {
        CompiledHighlightSet.Rule withText = rule("Panic", "panic", true);
        String hostile = "panic\u001b[2J ‮evil\u0007" + "x".repeat(300);

        String body = dispatch(UNSEEN, match(withText, hostile)).getFirst().body();

        assertThat(body).startsWith("Panic: panic");
        assertThat(body).doesNotContain("\u001b");
        assertThat(body).doesNotContain("‮");
        assertThat(body).doesNotContain("\u0007");
        assertWithMessage("name, separator and at most %s characters of text", HighlightTriggerDispatcher.MAX_MATCHED_TEXT_CHARS)
            .that(body.length()).isAtMost("Panic: ".length() + HighlightTriggerDispatcher.MAX_MATCHED_TEXT_CHARS);
    }

    @Test
    void aRuleThatDoesNotAskForTheTextNeverShowsItEvenIfTheEngineSentIt() {
        CompiledHighlightSet.Rule quiet = rule("Errors", "ERROR", false);

        String body = dispatch(UNSEEN, new LineMatch(quiet, "ERROR secret token abc123")).getFirst().body();

        assertThat(body).isEqualTo("Errors");
    }

    @Test
    void aRuleWithoutANameIsCalledByItsPatternAndAnInvisibleNameByAGenericOne() {
        CompiledHighlightSet.Rule unnamed = rule(null, "segfault", false);
        assertThat(dispatch(UNSEEN, match(unnamed, "x")).getFirst().body()).isEqualTo("segfault");

        CompiledHighlightSet.Rule invisible = rule("‮‏", "oops", false);
        clock.addAndGet(60_000L);
        assertThat(dispatch(UNSEEN, match(invisible, "x")).getFirst().body()).isEqualTo("Highlight rule");
    }

    @Test
    void theRuleNameIsCleanedAndShortened() {
        CompiledHighlightSet.Rule longName = rule("\u001b]0;evil\u0007" + "N".repeat(200), "x", false);

        String body = dispatch(UNSEEN, match(longName, "x")).getFirst().body();

        assertThat(body).doesNotContain("\u001b");
        assertThat(body.length()).isAtMost(HighlightTriggerDispatcher.MAX_RULE_LABEL_CHARS);
    }

    @Test
    void oneRuleNotifiesAtMostOncePerPaneEveryThirtySecondsButKeepsMarkingTheTab() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space", false);

        assertThat(dispatch(UNSEEN, match(disk, "x")).getFirst().toast()).isTrue();
        clock.addAndGet(29_999L);
        HighlightTriggerDispatcher.Notice again = dispatch(UNSEEN, match(disk, "x")).getFirst();
        assertThat(again.toast()).isFalse();
        assertThat(again.badge()).isTrue();
        clock.addAndGet(1L);
        assertThat(dispatch(UNSEEN, match(disk, "x")).getFirst().toast()).isTrue();
    }

    @Test
    void twoRulesDoNotSilenceEachOtherAndNorDoTwoPanes() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space", false);
        CompiledHighlightSet.Rule oom = rule("Out of memory", "OOM", false);

        List<HighlightTriggerDispatcher.Notice> notices = dispatch(UNSEEN, match(disk, "x"), match(oom, "y"));
        assertThat(notices).hasSize(2);
        assertThat(notices.get(0).toast()).isTrue();
        assertThat(notices.get(1).toast()).isTrue();

        Object otherPane = new Object();
        assertThat(dispatcher.dispatch(otherPane, UNSEEN, "db01", List.of(match(disk, "x")), I18N)
            .getFirst().toast()).isTrue();
    }

    @Test
    void aSeenTabIsLeftAlone() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space", false);

        assertThat(dispatch(new PaneState(true, false, false, false), match(disk, "x"))).isEmpty();
        assertWithMessage("a seen tab takes no slot, so the next unseen match still notifies")
            .that(dispatch(UNSEEN, match(disk, "x")).getFirst().toast()).isTrue();
    }

    @Test
    void outputThatAnswersMirroredKeysLeadsToNothing() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space", false);

        assertThat(dispatch(new PaneState(false, false, false, true), match(disk, "x"))).isEmpty();
    }

    @Test
    void nothingAtAllWhileTriggersAreNotAllowed() {
        CompiledHighlightSet.Rule disk = rule("Disk full", "No space", false);
        allowed.set(false);

        assertThat(dispatch(UNSEEN, match(disk, "x"))).isEmpty();
        allowed.set(true);
        assertWithMessage("a refused trigger took no slot").that(dispatch(UNSEEN, match(disk, "x")).getFirst().toast())
            .isTrue();
    }

    @Test
    void aRuleWithoutTheNotifyActionNeverNotifies() {
        HighlightRule plain = new HighlightRule("ERROR", false);
        plain.setBold(true);
        CompiledHighlightSet.Rule highlightOnly =
            CompiledHighlightSet.compile(new HighlightRuleSet("s", "S", List.of(plain))).rule(0);

        assertThat(dispatch(UNSEEN, new LineMatch(highlightOnly, null))).isEmpty();
    }

    @Test
    void aRuleThatRunsASnippetIsLeftToTheSnippetRunner() {
        HighlightRule runs = new HighlightRule("BUILD FAILED", false);
        runs.setAction(HighlightRule.Action.RUN_SNIPPET);
        runs.setSnippetId("snippet-1");
        CompiledHighlightSet.Rule snippetRule =
            CompiledHighlightSet.compile(new HighlightRuleSet("s", "S", List.of(runs))).rule(0);

        assertWithMessage("no notification and no mark: HighlightSnippetTrigger handles it")
            .that(dispatch(UNSEEN, new LineMatch(snippetRule, null))).isEmpty();
    }

    @Test
    void triggersNeedTheUsersSwitchAndThePolicy() {
        GlobalSettings settings = new GlobalSettings();
        assertWithMessage("on for a fresh installation").that(settings.isTerminalTriggersEnabled()).isTrue();
        assertThat(HighlightTriggerDispatcher.triggersAllowed(settings, EffectivePolicy.unrestricted())).isTrue();
        assertThat(HighlightTriggerDispatcher.triggersAllowed(null, EffectivePolicy.unrestricted())).isTrue();

        settings.setTerminalTriggersEnabled(false);
        assertThat(HighlightTriggerDispatcher.triggersAllowed(settings, EffectivePolicy.unrestricted())).isFalse();

        settings.setTerminalTriggersEnabled(true);
        assertThat(HighlightTriggerDispatcher.triggersAllowed(settings, policyWith(PolicyDecision.DENY))).isFalse();
        assertThat(HighlightTriggerDispatcher.triggersAllowed(settings, policyWith(PolicyDecision.ALLOW))).isTrue();
        assertThat(HighlightTriggerDispatcher.triggersAllowed(settings, EffectivePolicy.lockdown())).isFalse();
    }

    private static EffectivePolicy policyWith(PolicyDecision decision) {
        PolicyRule rule = PolicyRule.builder()
            .features(Map.of(PolicyFeature.TERMINAL_TRIGGERS, decision))
            .build();
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of());
        return EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });
    }
}
