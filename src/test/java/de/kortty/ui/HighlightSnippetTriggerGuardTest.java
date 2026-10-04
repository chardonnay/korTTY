package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import de.kortty.ui.HighlightSnippetTriggerGuard.Request;
import de.kortty.ui.HighlightSnippetTriggerGuard.Verdict;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * When a highlight rule that runs a snippet may run it: never while triggers are off, never for the
 * cursor line, for output answering mirrored keys or while an agent works in the pane; at most once every
 * 30 seconds per rule and pane, never within 5 seconds of the pane's last run, and never more than three
 * times in a row — the loop guard then stops the pane until two minutes pass without a match; and only
 * after the user allowed the rule's snippet for the connection, asked once.
 */
class HighlightSnippetTriggerGuardTest {

    private static final long COOLDOWN = HighlightSnippetTriggerGuard.COOLDOWN_MILLIS;

    private static final long LOOP_WINDOW = HighlightSnippetTriggerGuard.LOOP_WINDOW_MILLIS;

    private final AtomicLong clock = new AtomicLong();

    private final AtomicBoolean allowed = new AtomicBoolean(true);

    private final Object pane = new Object();

    private HighlightSnippetTriggerGuard guard;

    @BeforeMethod
    void setUp() {
        clock.set(5_000_000L);
        allowed.set(true);
        guard = new HighlightSnippetTriggerGuard(clock::get, allowed::get);
    }

    private Request request(Object onPane, String connection, String rule, String snippet) {
        return new Request(onPane, connection, rule, snippet, false, false, false);
    }

    private Request match() {
        return request(pane, "id:web01", "rule-1", "snippet-1");
    }

    /** Asks, answers yes and runs once: what a rule does the first time it matches in a connection. */
    private void approveAndRun(Request request) {
        assertThat(guard.decide(request)).isEqualTo(Verdict.ASK);
        guard.answer(request, true);
        assertThat(guard.decide(request)).isEqualTo(Verdict.RUN);
        guard.ran(request.pane(), request.ruleId());
    }

    /** One match that runs: decide said RUN and the snippet was typed. */
    private void runAgain(Request request) {
        assertThat(guard.decide(request)).isEqualTo(Verdict.RUN);
        guard.ran(request.pane(), request.ruleId());
    }

    @Test
    void theFirstMatchInAConnectionAsksAndFurtherMatchesWaitForTheAnswer() {
        assertThat(guard.decide(match())).isEqualTo(Verdict.ASK);
        assertWithMessage("one question at a time, however often the pattern appears meanwhile")
            .that(guard.decide(match())).isEqualTo(Verdict.ASKING);
        assertThat(guard.decide(request(new Object(), "id:web01", "rule-1", "snippet-1"))).isEqualTo(Verdict.ASKING);

        guard.answer(match(), true);

        assertThat(guard.decide(match())).isEqualTo(Verdict.RUN);
    }

    @Test
    void theAnswerHoldsPerConnectionRuleAndSnippet() {
        approveAndRun(match());
        Object otherPane = new Object();

        assertWithMessage("another pane of the same connection needs no new question")
            .that(guard.decide(request(otherPane, "id:web01", "rule-1", "snippet-1"))).isEqualTo(Verdict.RUN);
        assertWithMessage("another connection asks again")
            .that(guard.decide(request(otherPane, "id:db01", "rule-1", "snippet-1"))).isEqualTo(Verdict.ASK);
        assertWithMessage("a rule now pointing at another snippet asks again")
            .that(guard.decide(request(otherPane, "id:web01", "rule-1", "snippet-2"))).isEqualTo(Verdict.ASK);
        assertWithMessage("another rule asks again")
            .that(guard.decide(request(otherPane, "id:web01", "rule-2", "snippet-1"))).isEqualTo(Verdict.ASK);
    }

    @Test
    void aRefusalDropsEveryLaterMatchOfThatConnection() {
        assertThat(guard.decide(match())).isEqualTo(Verdict.ASK);
        guard.answer(match(), false);

        assertThat(guard.decide(match())).isEqualTo(Verdict.DECLINED);
        clock.addAndGet(10 * LOOP_WINDOW);
        assertThat(guard.decide(match())).isEqualTo(Verdict.DECLINED);
    }

    @Test
    void aQuestionThatCouldNotBeShownIsAskedAgain() {
        assertThat(guard.decide(match())).isEqualTo(Verdict.ASK);
        guard.withdraw(match());

        assertThat(guard.decide(match())).isEqualTo(Verdict.ASK);
    }

    @Test
    void aRuleRunsItsSnippetAtMostOnceEveryThirtySecondsPerPane() {
        approveAndRun(match());

        clock.addAndGet(COOLDOWN - 1L);
        assertThat(guard.decide(match())).isEqualTo(Verdict.COOLDOWN);
        clock.addAndGet(1L);
        assertThat(guard.decide(match())).isEqualTo(Verdict.RUN);

        Object otherPane = new Object();
        guard.ran(pane, "rule-1");
        assertWithMessage("the cooldown is per pane")
            .that(guard.decide(request(otherPane, "id:web01", "rule-1", "snippet-1"))).isEqualTo(Verdict.RUN);
    }

    @Test
    void noRuleRunsASnippetWhileThePanesLastRunSettles() {
        approveAndRun(match());
        Request other = request(pane, "id:web01", "rule-2", "snippet-2");
        guard.answer(other, true);

        clock.addAndGet(HighlightSnippetTriggerGuard.SETTLE_MILLIS - 1L);
        assertWithMessage("the snippet's banner, echo and first output cannot set off another rule")
            .that(guard.decide(other)).isEqualTo(Verdict.SETTLING);
        clock.addAndGet(1L);
        assertThat(guard.decide(other)).isEqualTo(Verdict.RUN);
    }

    @Test
    void aSnippetWhoseOutputMatchesItsOwnRuleStopsAfterThreeRunsInARow() {
        approveAndRun(match());                 // run 1
        clock.addAndGet(COOLDOWN);
        runAgain(match());                      // run 2: its own output matched again
        clock.addAndGet(COOLDOWN);
        runAgain(match());                      // run 3
        clock.addAndGet(COOLDOWN);

        assertWithMessage("the fourth run in a row is the loop guard's: stop, and tell the user once")
            .that(guard.decide(match())).isEqualTo(Verdict.LOOP_STOPPED);
        clock.addAndGet(COOLDOWN);
        assertThat(guard.decide(match())).isEqualTo(Verdict.STOPPED);
        clock.addAndGet(LOOP_WINDOW - 1L);
        assertWithMessage("every match while stopped starts the wait anew")
            .that(guard.decide(match())).isEqualTo(Verdict.STOPPED);
        clock.addAndGet(LOOP_WINDOW);
        assertWithMessage("a whole window without a match lifts the stop")
            .that(guard.decide(match())).isEqualTo(Verdict.RUN);
        guard.ran(pane, "rule-1");
        clock.addAndGet(COOLDOWN);
        assertWithMessage("and the count starts from zero")
            .that(guard.decide(match())).isEqualTo(Verdict.RUN);
    }

    @Test
    void twoRulesThatKeepSettingEachOtherOffAreStoppedToo() {
        Request a = match();
        Request b = request(pane, "id:web01", "rule-2", "snippet-2");
        approveAndRun(a);
        clock.addAndGet(HighlightSnippetTriggerGuard.SETTLE_MILLIS);
        approveAndRun(b);
        clock.addAndGet(COOLDOWN);
        runAgain(a);

        clock.addAndGet(HighlightSnippetTriggerGuard.SETTLE_MILLIS);
        assertWithMessage("the loop guard counts the pane's runs, whichever rule ran them")
            .that(guard.decide(b)).isEqualTo(Verdict.LOOP_STOPPED);
        clock.addAndGet(COOLDOWN);
        assertThat(guard.decide(a)).isEqualTo(Verdict.STOPPED);
    }

    @Test
    void runsFurtherApartThanTheLoopWindowNeverStopThePane() {
        approveAndRun(match());
        for (int run = 0; run < 10; run++) {
            clock.addAndGet(LOOP_WINDOW + 1L);
            runAgain(match());
        }
    }

    @Test
    void theCursorLineMirroredKeysAndAgentsNeverRunASnippet() {
        guard.answer(match(), true);

        assertWithMessage("the command the user types, or a prompt waiting for an answer")
            .that(guard.decide(new Request(pane, "id:web01", "rule-1", "snippet-1", true, false, false)))
            .isEqualTo(Verdict.CURSOR_LINE);
        assertThat(guard.decide(new Request(pane, "id:web01", "rule-1", "snippet-1", false, true, false)))
            .isEqualTo(Verdict.MIRRORED_INPUT);
        assertThat(guard.decide(new Request(pane, "id:web01", "rule-1", "snippet-1", false, false, true)))
            .isEqualTo(Verdict.AGENT_BUSY);
        assertWithMessage("a skipped match takes no slot").that(guard.decide(match())).isEqualTo(Verdict.RUN);
    }

    @Test
    void nothingRunsOrAsksWhileTriggersAreNotAllowed() {
        allowed.set(false);

        assertThat(guard.decide(match())).isEqualTo(Verdict.NOT_ALLOWED);
        allowed.set(true);
        assertWithMessage("a refused match opened no question").that(guard.decide(match())).isEqualTo(Verdict.ASK);
    }

    @Test
    void thePolicyAndTheUsersSwitchGateSnippetRunsAsTheyGateNotifications() {
        GlobalSettings settings = new GlobalSettings();
        AtomicBoolean policyAllows = new AtomicBoolean(true);
        HighlightSnippetTriggerGuard gated = new HighlightSnippetTriggerGuard(clock::get,
            () -> HighlightTriggerDispatcher.triggersAllowed(settings,
                policyAllows.get() ? EffectivePolicy.unrestricted() : EffectivePolicy.lockdown()));

        assertThat(gated.decide(match())).isEqualTo(Verdict.ASK);
        gated.answer(match(), true);
        settings.setTerminalTriggersEnabled(false);
        assertThat(gated.decide(match())).isEqualTo(Verdict.NOT_ALLOWED);
        settings.setTerminalTriggersEnabled(true);
        policyAllows.set(false);
        assertWithMessage("an allowed snippet stops as soon as the policy denies triggers")
            .that(gated.decide(match())).isEqualTo(Verdict.NOT_ALLOWED);
    }

    @Test
    void aFailingSwitchCountsAsOff() {
        HighlightSnippetTriggerGuard broken = new HighlightSnippetTriggerGuard(clock::get, () -> {
            throw new IllegalStateException("settings not readable");
        });

        assertThat(broken.decide(match())).isEqualTo(Verdict.NOT_ALLOWED);
    }

    @Test
    void theUserIsToldSomethingOncePerPane() {
        assertThat(guard.tellOnce(pane, "rule-1:missing")).isTrue();
        assertThat(guard.tellOnce(pane, "rule-1:missing")).isFalse();
        assertThat(guard.tellOnce(new Object(), "rule-1:missing")).isTrue();
        assertThat(guard.tellOnce(pane, "rule-2:missing")).isTrue();
    }

    @Test
    void onlyRunAndAskProceed() {
        for (Verdict verdict : Verdict.values()) {
            assertWithMessage(verdict.name()).that(verdict.proceeds())
                .isEqualTo(verdict == Verdict.RUN || verdict == Verdict.ASK);
        }
    }

    @Test
    void aConnectionIsKnownByItsIdElseByItsName() {
        assertThat(HighlightSnippetTriggerGuard.connectionKey("abc", "ops@web01")).isEqualTo("id:abc");
        assertThat(HighlightSnippetTriggerGuard.connectionKey(" ", "ops@web01")).isEqualTo("name:ops@web01");
        assertThat(HighlightSnippetTriggerGuard.connectionKey(null, null)).isEqualTo("name:");
        assertWithMessage("an id can never pass for a name")
            .that(HighlightSnippetTriggerGuard.connectionKey("x", null))
            .isNotEqualTo(HighlightSnippetTriggerGuard.connectionKey(null, "x"));
    }
}
