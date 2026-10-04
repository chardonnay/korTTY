package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import com.sithtermfx.core.model.TerminalLine;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class TerminalHighlightServiceTest {

    private ScheduledExecutorService executor;

    private TerminalHighlightService service;

    @BeforeMethod
    void setUp() {
        executor = TerminalHighlightService.defaultScheduler();
        service = new TerminalHighlightService(executor);
    }

    @AfterMethod
    void tearDown() {
        service.stop();
    }

    private static HighlightRuleSet userSet(String id, String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setId(id + ".rule");
        rule.setBold(true);
        return new HighlightRuleSet(id, "Set " + id, new ArrayList<>(List.of(rule)));
    }

    private static GlobalSettings settingsWith(HighlightRuleSet... sets) {
        GlobalSettings settings = new GlobalSettings();
        settings.setHighlightRuleSets(new ArrayList<>(List.of(sets)));
        return settings;
    }

    private TerminalOutputHighlighter attach(HeadlessTerminalSession session, AtomicReference<String> choice) {
        return service.attach(session.buffer, choice::get, () -> { }, () -> { }, () -> false);
    }

    @Test
    void theThreadIsOneNamedDaemon() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        executor.submit(() -> thread.set(Thread.currentThread())).get(5, TimeUnit.SECONDS);
        assertThat(thread.get().getName()).isEqualTo(TerminalHighlightService.THREAD_NAME);
        assertThat(thread.get().isDaemon()).isTrue();
    }

    @Test
    void anUnchangedSetKeepsItsCompiledInstanceAcrossReloads() {
        service.reload(settingsWith(userSet("user-1", "deploy")));
        CompiledHighlightSet first = service.resolve(() -> "user-1");

        service.reload(settingsWith(userSet("user-1", "deploy")));
        assertThat(service.resolve(() -> "user-1")).isSameInstanceAs(first);

        service.reload(settingsWith(userSet("user-1", "release")));
        assertThat(service.resolve(() -> "user-1")).isNotSameInstanceAs(first);
        assertThat(service.resolve(() -> HighlightBuiltinSets.ERRORS))
            .isSameInstanceAs(service.resolve(() -> HighlightBuiltinSets.ERRORS));
    }

    @Test
    void aChangedActionNameOrTextOptionRecompilesTheSet() {
        HighlightRuleSet base = userSet("user-1", "deploy");
        String plain = TerminalHighlightService.signature(base);

        HighlightRuleSet notify = new HighlightRuleSet(base);
        notify.getRules().getFirst().setAction(HighlightRule.Action.NOTIFY);
        HighlightRuleSet named = new HighlightRuleSet(notify);
        named.getRules().getFirst().setName("Deploys");
        HighlightRuleSet withText = new HighlightRuleSet(named);
        withText.getRules().getFirst().setNotifyWithText(true);

        assertThat(TerminalHighlightService.signature(notify)).isNotEqualTo(plain);
        assertThat(TerminalHighlightService.signature(named)).isNotEqualTo(TerminalHighlightService.signature(notify));
        assertThat(TerminalHighlightService.signature(withText)).isNotEqualTo(TerminalHighlightService.signature(named));

        service.reload(settingsWith(base));
        CompiledHighlightSet first = service.resolve(() -> "user-1");
        service.reload(settingsWith(withText));
        CompiledHighlightSet second = service.resolve(() -> "user-1");
        assertThat(second).isNotSameInstanceAs(first);
        assertThat(first.hasTriggers()).isFalse();
        assertThat(second.hasTriggers()).isTrue();
        assertThat(second.rule(0).label()).isEqualTo("Deploys");
        assertThat(second.rule(0).notifyWithText()).isTrue();
    }

    @Test
    void anotherSnippetRecompilesTheSet() {
        HighlightRuleSet base = userSet("user-1", "BUILD FAILED");
        base.getRules().getFirst().setAction(HighlightRule.Action.RUN_SNIPPET);
        base.getRules().getFirst().setSnippetId("snippet-1");
        HighlightRuleSet other = new HighlightRuleSet(base);
        other.getRules().getFirst().setSnippetId("snippet-2");

        assertThat(TerminalHighlightService.signature(other)).isNotEqualTo(TerminalHighlightService.signature(base));

        service.reload(settingsWith(base));
        CompiledHighlightSet first = service.resolve(() -> "user-1");
        service.reload(settingsWith(other));
        CompiledHighlightSet second = service.resolve(() -> "user-1");
        assertThat(second).isNotSameInstanceAs(first);
        assertThat(second.rule(0).snippetId()).isEqualTo("snippet-2");
    }

    @Test
    void attachHandsTheTriggerSinkToTheHighlighter() throws Exception {
        HighlightRuleSet set = userSet("user-1", "ERROR");
        set.getRules().getFirst().setAction(HighlightRule.Action.NOTIFY);
        service.reload(settingsWith(set));
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        java.util.concurrent.CountDownLatch fired = new java.util.concurrent.CountDownLatch(1);
        TerminalOutputHighlighter highlighter = service.attach(session.buffer, () -> "user-1", () -> { }, () -> { },
            () -> false, matches -> fired.countDown());
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS); // the attach pass, on the still empty pane

        session.println("ERROR disk full");

        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        service.detach(highlighter);
    }

    @Test
    void userSetsWithAReservedOrDuplicateIdAreIgnored() {
        service.reload(settingsWith(
            userSet("builtin.mine", "a"),
            userSet(TerminalHighlightService.NONE_ID, "b"),
            userSet("user-1", "c"),
            userSet("user-1", "d")));

        assertThat(service.setIds()).containsExactly(HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK,
            HighlightBuiltinSets.NETWORK_DEVICES, "user-1").inOrder();
        assertThat(service.isKnownSet("builtin.mine")).isFalse();
        assertThat(service.resolve(() -> HighlightBuiltinSets.ERRORS).setId()).isEqualTo(HighlightBuiltinSets.ERRORS);
    }

    @Test
    void attachStartsOnTheResolvedSetAndRefreshFollowsTheChoice() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        AtomicReference<String> choice = new AtomicReference<>();
        TerminalOutputHighlighter highlighter = attach(session, choice);

        assertThat(highlighter.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);
        assertThat(service.attachedCount()).isEqualTo(1);

        choice.set(HighlightBuiltinSets.ERRORS);
        service.refresh(highlighter);
        assertThat(highlighter.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.ERRORS);

        choice.set(TerminalHighlightService.NONE_ID);
        service.refresh(highlighter);
        assertThat(highlighter.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);

        service.detach(highlighter);
        assertThat(highlighter.isClosed()).isTrue();
        assertThat(service.attachedCount()).isEqualTo(0);
    }

    @Test
    void aReloadMovesEveryAttachedPane() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        TerminalOutputHighlighter highlighter = attach(session, new AtomicReference<>());
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.NETWORK);

        service.reload(settings);
        assertThat(highlighter.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);

        settings.setTerminalHighlightingEnabled(false);
        service.reload(settings);
        assertThat(highlighter.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);
    }

    @Test
    void outputIsHighlightedOnTheServiceThread() throws Exception {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        CountDownLatch restyled = new CountDownLatch(1);
        TerminalOutputHighlighter highlighter = service.attach(session.buffer, () -> HighlightBuiltinSets.ERRORS,
            () -> { }, restyled::countDown, () -> false);
        assertThat(highlighter.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.ERRORS);

        session.println("build failed: fatal error");

        assertThat(restyled.await(10, TimeUnit.SECONDS)).isTrue();
        TerminalLine line = session.line(0);
        assertThat(HeadlessTerminalSession.isHighlighted(line, "build ".length())).isTrue();
        assertThat(HeadlessTerminalSession.isHighlighted(line, 0)).isFalse();
    }

    @Test
    void allowingFullScreenProgramsHighlightsARunningOneWithoutNewOutput() throws Exception {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.ERRORS);
        service.reload(settings);
        session.terminal.useAlternateBuffer(true);
        session.print("ERROR in a full-screen program");
        CountDownLatch restyled = new CountDownLatch(1);
        TerminalOutputHighlighter highlighter = service.attach(session.buffer, () -> null, () -> { },
            restyled::countDown, () -> false);

        highlighter.runPassNow();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isFalse();

        // The program prints nothing more; saving the option alone must reach it.
        settings.setTerminalHighlightAlternateScreen(true);
        service.reload(settings);

        assertThat(restyled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
    }

    @Test
    void stopClosesEveryPaneAndRefusesNewOnes() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        TerminalOutputHighlighter highlighter = attach(session, new AtomicReference<>());

        service.stop();

        assertThat(service.isClosed()).isTrue();
        assertThat(highlighter.isClosed()).isTrue();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(attach(session, new AtomicReference<>())).isNull();
        service.stop(); // idempotent
    }

    @Test
    void theDutyCycleLetsPassesUseAtMostHalfTheTime() {
        TerminalHighlightService.DutyCycle duty = new TerminalHighlightService.DutyCycle(TerminalHighlightService.MAX_DUTY);
        assertThat(duty.delayNanos(0L)).isEqualTo(0L);

        duty.record(1_000L, 1_010L);

        assertThat(duty.delayNanos(1_010L)).isEqualTo(10L);
        assertThat(duty.delayNanos(1_015L)).isEqualTo(5L);
        assertThat(duty.delayNanos(1_020L)).isEqualTo(0L);
    }

    @Test
    void theMenusReadTheMasterSwitchAndTheUserSetNames() {
        assertThat(service.isEnabled()).isTrue();
        assertThat(service.userSetName("user-1")).isNull();

        GlobalSettings settings = settingsWith(userSet("user-1", "error"));
        settings.setTerminalHighlightingEnabled(false);
        service.reload(settings);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.userSetName("user-1")).isEqualTo("Set user-1");
        assertThat(service.userSetName(" user-1 ")).isEqualTo("Set user-1");
        // Built-ins are named through i18n, not by the service.
        assertThat(service.userSetName(HighlightBuiltinSets.ERRORS)).isNull();
        assertThat(service.userSetName("unknown")).isNull();
        assertThat(service.userSetName(null)).isNull();
    }
}
