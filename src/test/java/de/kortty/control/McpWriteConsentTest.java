package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The consent every MCP client write needs, driven through the real {@link ControlPaneWriter} over a
 * {@link FakeControlSurface} and a scripted prompter; no toolkit and no socket.
 */
class McpWriteConsentTest {

    private static final String PANE = "p1a2b";

    private static final String OTHER_PANE = "p9z8y";

    private FakeControlSurface surface;

    private List<String> audit;

    private ScriptedPrompter prompter;

    private ControlPaneWriter writer;

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, "t1", "w1", 0, true, true, 4711L));
        surface.addPane(FakeControlSurface.pane(OTHER_PANE, "t1", "w1", 1, true, true, 4712L));
        audit = new ArrayList<>();
        prompter = new ScriptedPrompter();
        ControlAuditSink sink = (verb, pane, detail) -> audit.add(verb + " " + pane + " " + detail);
        writer = new ControlPaneWriter(surface, UiDispatcher.DIRECT, sink, new McpWriteConsent(prompter, sink));
    }

    // --- allow once and deny ---------------------------------------------------------------------

    @Test
    void allowOnceWritesThisOneTextAndTheNextWriteAsksAgain() throws Exception {
        prompter.answer(McpWriteConsent.Decision.ALLOW_ONCE, McpWriteConsent.Decision.ALLOW_ONCE);

        writer.sendText(mcp("conn-1", "session-aaaaaaaaaaaa"), PANE, "ls", false, "never", false);
        writer.sendText(mcp("conn-1", "session-aaaaaaaaaaaa"), PANE, "ls", false, "never", false);

        assertThat(prompter.asked).hasSize(2);
        assertThat(written(PANE)).isEqualTo("lsls");
        McpWriteConsent.Request request = prompter.asked.get(0);
        assertThat(request.client()).isEqualTo("Some Host");
        assertThat(request.paneId()).isEqualTo(PANE);
        assertThat(request.paneLabel()).contains(PANE);
        assertThat(request.verb()).isEqualTo("pane.send_text");
        assertThat(request.text()).isEqualTo("ls");
        assertThat(request.submits()).isFalse();
        assertThat(request.sessionOffered()).isTrue();
        assertThat(audit).contains("mcp.consent " + PANE
            + " verb=pane.send_text decision=allowed reason=once chars=2 submits=false client=Some_Host");
    }

    @Test
    void denyRefusesTheWriteWithMcpWriteDeniedAndTypesNothing() {
        prompter.answer(McpWriteConsent.Decision.DENY);

        ControlApiException e = expectThrows(ControlApiException.class, () ->
            writer.run(mcp("conn-1", null), PANE, "rm -rf ~/tmp"));

        assertThat(e.code()).isEqualTo(ControlErrorCode.MCP_WRITE_DENIED);
        assertThat(e.data()).containsEntry("reason", McpWriteConsent.REASON_DENIED);
        assertThat(e.data()).containsEntry("pane", PANE);
        assertThat(written(PANE)).isEmpty();
        assertWithMessage("the audit line records the decision but never the text")
            .that(String.join("\n", audit)).doesNotContain("rm -rf");
        assertThat(audit).contains("mcp.consent " + PANE
            + " verb=pane.run decision=denied reason=denied chars=12 submits=true client=Some_Host");
    }

    @Test
    void aPromptThatTimesOutFailsOrAnswersNothingIsADenial() {
        prompter.answerWith((request, timeout) -> {
            throw new TimeoutException("nobody answered");
        });
        ControlApiException timeout = expectThrows(ControlApiException.class, () ->
            writer.sendText(mcp("c", null), PANE, "ls", false, "never", false));
        assertThat(timeout.code()).isEqualTo(ControlErrorCode.MCP_WRITE_DENIED);
        assertThat(timeout.data()).containsEntry("reason", McpWriteConsent.REASON_TIMEOUT);

        prompter.answerWith((request, timeoutMillis) -> {
            throw new IllegalStateException("Toolkit not initialized");
        });
        ControlApiException noPrompt = expectThrows(ControlApiException.class, () ->
            writer.sendText(mcp("c", null), PANE, "ls", false, "never", false));
        assertThat(noPrompt.data()).containsEntry("reason", McpWriteConsent.REASON_NO_PROMPT);

        prompter.answerWith((request, timeoutMillis) -> null);
        ControlApiException silent = expectThrows(ControlApiException.class, () ->
            writer.sendText(mcp("c", null), PANE, "ls", false, "never", false));
        assertThat(silent.data()).containsEntry("reason", McpWriteConsent.REASON_DENIED);

        assertThat(written(PANE)).isEmpty();
    }

    @Test
    void theDefaultWiringWithoutAPromptDeniesEveryMcpWrite() {
        ControlPaneWriter unwired = new ControlPaneWriter(surface, UiDispatcher.DIRECT, (v, p, d) -> { });
        ControlApiException e = expectThrows(ControlApiException.class, () ->
            unwired.sendText(mcp("c", null), PANE, "ls", false, "never", false));
        assertThat(e.code()).isEqualTo(ControlErrorCode.MCP_WRITE_DENIED);
        assertThat(e.data()).containsEntry("reason", McpWriteConsent.REASON_NO_PROMPT);
        assertThat(written(PANE)).isEmpty();
    }

    @Test
    void aSecondPromptWaitsForTheFirstAndTimesOutRatherThanStackingModals() throws Exception {
        java.util.concurrent.CountDownLatch open = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        McpWriteConsent consent = new McpWriteConsent((request, timeoutMillis) -> {
            open.countDown();
            release.await();
            return McpWriteConsent.Decision.ALLOW_ONCE;
        }, (v, p, d) -> { }, 200L);
        Thread first = new Thread(() -> {
            try {
                consent.authorize(mcp("c1", null), "pane.run", PANE, PANE, "ls", true);
            } catch (ControlApiException e) {
                throw new IllegalStateException(e);
            }
        });
        first.start();
        open.await();
        ControlApiException e = expectThrows(ControlApiException.class, () ->
            consent.authorize(mcp("c2", null), "pane.run", PANE, PANE, "ls", true));
        assertThat(e.data()).containsEntry("reason", McpWriteConsent.REASON_TIMEOUT);
        release.countDown();
        first.join(5_000L);
    }

    // --- the pane-scoped session grant -------------------------------------------------------------

    @Test
    void allowForThisPaneCoversLaterPlainTextForThatPaneOnly() throws Exception {
        String sessionId = "session-aaaaaaaaaaaa";
        prompter.answer(McpWriteConsent.Decision.ALLOW_PANE_FOR_SESSION);
        writer.sendText(mcp("conn-1", sessionId), PANE, "git status", false, "never", false);
        assertThat(prompter.asked).hasSize(1);

        // A later connection of the same MCP server process: no question for the same pane.
        writer.sendText(mcp("conn-2", sessionId), PANE, "more text", false, "never", false);
        assertThat(prompter.asked).hasSize(1);
        assertThat(audit).contains("mcp.consent " + PANE
            + " verb=pane.send_text decision=allowed reason=session_grant chars=9 submits=false"
            + " client=Some_Host");

        prompter.answer(McpWriteConsent.Decision.ALLOW_ONCE, McpWriteConsent.Decision.ALLOW_ONCE,
            McpWriteConsent.Decision.ALLOW_ONCE, McpWriteConsent.Decision.ALLOW_ONCE,
            McpWriteConsent.Decision.ALLOW_ONCE, McpWriteConsent.Decision.ALLOW_ONCE);
        writer.sendText(mcp("conn-3", sessionId), OTHER_PANE, "ls", false, "never", false);
        assertWithMessage("another pane asks").that(prompter.asked).hasSize(2);
        writer.run(mcp("conn-3", sessionId), PANE, "ls");
        assertWithMessage("pane.run always asks").that(prompter.asked).hasSize(3);
        assertThat(prompter.asked.get(2).sessionOffered()).isFalse();
        writer.sendText(mcp("conn-3", sessionId), PANE, "ls", true, "never", false);
        assertWithMessage("submit always asks").that(prompter.asked).hasSize(4);
        writer.sendText(mcp("conn-3", sessionId), PANE, "a\nb", false, "never", false);
        assertWithMessage("a line break always asks").that(prompter.asked).hasSize(5);
        writer.sendKeys(mcp("conn-3", sessionId), PANE, List.of("ctrl+c"));
        assertWithMessage("keys always ask").that(prompter.asked).hasSize(6);
        assertThat(prompter.asked.get(5).sessionOffered()).isFalse();
        writer.sendText(mcp("conn-4", "session-bbbbbbbbbbbb"), PANE, "ls", false, "never", false);
        assertWithMessage("another MCP session asks").that(prompter.asked).hasSize(7);
    }

    @Test
    void withoutAnMcpSessionIdTheGrantLastsOnlyForTheOneConnection() throws Exception {
        prompter.answer(McpWriteConsent.Decision.ALLOW_PANE_FOR_SESSION, McpWriteConsent.Decision.ALLOW_ONCE);
        writer.sendText(mcp("conn-1", null), PANE, "ls", false, "never", false);
        writer.sendText(mcp("conn-1", null), PANE, "ls", false, "never", false);
        assertThat(prompter.asked).hasSize(1);
        writer.sendText(mcp("conn-2", null), PANE, "ls", false, "never", false);
        assertThat(prompter.asked).hasSize(2);
    }

    @Test
    void aSessionAnswerForAQuestionThatDidNotOfferItAllowsOnlyOnce() throws Exception {
        String sessionId = "session-aaaaaaaaaaaa";
        prompter.answer(McpWriteConsent.Decision.ALLOW_PANE_FOR_SESSION, McpWriteConsent.Decision.ALLOW_ONCE);
        writer.run(mcp("conn-1", sessionId), PANE, "ls");
        writer.sendText(mcp("conn-1", sessionId), PANE, "ls", false, "never", false);
        assertThat(prompter.asked).hasSize(2);
        assertThat(audit).contains("mcp.consent " + PANE
            + " verb=pane.run decision=allowed reason=once chars=2 submits=true client=Some_Host");
    }

    @Test
    void revokeAllDropsEveryGrant() throws Exception {
        McpWriteConsent consent = new McpWriteConsent(prompter, (v, p, d) -> { });
        prompter.answer(McpWriteConsent.Decision.ALLOW_PANE_FOR_SESSION, McpWriteConsent.Decision.ALLOW_ONCE);
        consent.authorize(mcp("c", null), "pane.send_text", PANE, PANE, "ls", false);
        assertThat(consent.grantCount()).isEqualTo(1);
        consent.revokeAll();
        consent.authorize(mcp("c", null), "pane.send_text", PANE, PANE, "ls", false);
        assertThat(prompter.asked).hasSize(2);
    }

    // --- refusals before any prompt ----------------------------------------------------------------

    @Test
    void aGuardedPaneIsRefusedBeforeAnyPromptForEveryGuard() {
        Map<String, McpPaneWriteState> guarded = Map.of(
            McpPaneWriteState.REASON_PASTE_PACING, state(true, false, false, false, false, false),
            McpPaneWriteState.REASON_ALTERNATE_SCREEN, state(false, true, false, false, false, false),
            McpPaneWriteState.REASON_FOREIGN_SESSION, state(false, false, true, false, false, false),
            McpPaneWriteState.REASON_BROADCAST, state(false, false, false, true, false, false),
            McpPaneWriteState.REASON_MULTI_EXEC, state(false, false, false, false, true, false),
            McpPaneWriteState.REASON_CODING_AGENT, state(false, false, false, false, false, true),
            McpPaneWriteState.REASON_UNKNOWN, McpPaneWriteState.unknown(PANE));
        for (Map.Entry<String, McpPaneWriteState> entry : guarded.entrySet()) {
            surface.setMcpWriteState(PANE, entry.getValue());
            for (ThrowingWrite write : List.<ThrowingWrite>of(
                    () -> writer.sendText(mcp("c", null), PANE, "ls", false, "never", false),
                    () -> writer.run(mcp("c", null), PANE, "ls"),
                    () -> writer.sendKeys(mcp("c", null), PANE, List.of("enter")))) {
                ControlApiException e = expectThrows(ControlApiException.class, write::run);
                assertWithMessage("%s", entry.getKey()).that(e.code())
                    .isEqualTo(ControlErrorCode.MCP_WRITE_REFUSED);
                assertWithMessage("%s", entry.getKey()).that(e.data()).containsEntry("reason", entry.getKey());
            }
            assertThat(audit).contains("mcp.consent " + PANE + " verb=pane.run decision=refused reason="
                + entry.getKey() + " client=Some_Host");
        }
        assertWithMessage("nobody is asked about a write korTTY refuses anyway").that(prompter.asked).isEmpty();
        assertThat(written(PANE)).isEmpty();
    }

    @Test
    void aPaneThatTurnsBusyWhileThePromptIsOpenIsStillRefused() {
        prompter.answerWith((request, timeoutMillis) -> {
            surface.setMcpWriteState(PANE, state(false, true, false, false, false, false));
            return McpWriteConsent.Decision.ALLOW_ONCE;
        });
        ControlApiException e = expectThrows(ControlApiException.class, () ->
            writer.sendText(mcp("c", null), PANE, ":q", false, "never", false));
        assertThat(e.code()).isEqualTo(ControlErrorCode.MCP_WRITE_REFUSED);
        assertThat(e.data()).containsEntry("reason", McpPaneWriteState.REASON_ALTERNATE_SCREEN);
        assertThat(written(PANE)).isEmpty();
    }

    @Test
    void invalidInputIsRefusedBeforeTheUserIsAsked() {
        expectThrows(ControlApiException.class, () ->
            writer.sendText(mcp("c", null), PANE, "  ", false, "never", false));
        expectThrows(ControlApiException.class, () ->
            writer.run(mcp("c", null), PANE, "ls\nrm -rf /"));
        expectThrows(ControlApiException.class, () ->
            writer.sendKeys(mcp("c", null), PANE, List.of("no-such-key")));
        assertThat(prompter.asked).isEmpty();
    }

    @Test
    void aCliClientIsNeverAskedOrGuarded() throws Exception {
        surface.setMcpWriteState(PANE, state(false, true, false, true, false, true));
        ControlSession cli = new ControlSession("c", EndpointDescriptor.TRANSPORT_UNIX, true, "kortty-cli",
            frame -> { });
        writer.sendText(cli, PANE, "ls", true, "never", false);
        writer.sendKeys(cli, PANE, List.of("enter"));
        assertThat(prompter.asked).isEmpty();
        assertThat(written(PANE)).isEqualTo("ls\r\r");
    }

    // --- what the prompt shows ---------------------------------------------------------------------

    @Test
    void keysThatPressEnterAreShownAsSubmittingAndByName() throws Exception {
        prompter.answer(McpWriteConsent.Decision.ALLOW_ONCE, McpWriteConsent.Decision.ALLOW_ONCE);
        writer.sendKeys(mcp("c", null), PANE, List.of("up", "enter"));
        writer.sendKeys(mcp("c", null), PANE, List.of("ctrl+c"));
        assertThat(prompter.asked.get(0).text()).isEqualTo("up enter");
        assertThat(prompter.asked.get(0).submits()).isTrue();
        assertThat(prompter.asked.get(1).submits()).isFalse();
    }

    @Test
    void controlAndInvisibleCharactersAreMadeVisible() {
        assertThat(McpWriteConsent.visible("a\tb\u001b[31m")).isEqualTo("a␉b␛[31m");
        assertThat(McpWriteConsent.visible("one\r\ntwo\nthree\r")).isEqualTo("one␍␊\ntwo␊\nthree␍\n");
        assertThat(McpWriteConsent.visible("x‮y​z\u007f\u0085")).isEqualTo("x<U+202E>y<U+200B>z␡<U+0085>");
        assertThat(McpWriteConsent.visible(null)).isEmpty();
    }

    @Test
    void theSessionOptionIsOfferedOnlyForPlainTextThatSubmitsNothing() {
        assertThat(McpWriteConsent.offersSession("pane.send_text", "git status", false)).isTrue();
        assertThat(McpWriteConsent.offersSession("pane.send_text", "git status", true)).isFalse();
        assertThat(McpWriteConsent.offersSession("pane.send_text", "a\rb", false)).isFalse();
        assertThat(McpWriteConsent.offersSession("pane.send_text", "a\u0003", false)).isFalse();
        assertThat(McpWriteConsent.offersSession("pane.send_text", "a‮", false)).isFalse();
        assertThat(McpWriteConsent.offersSession("pane.run", "ls", false)).isFalse();
        assertThat(McpWriteConsent.offersSession("pane.send_keys", "up", false)).isFalse();
    }

    @Test
    void theClientNameCannotForgeALogLineOrAPromptLayout() {
        assertThat(McpWriteConsent.displayClient("evil\ncontrol-api pane.run ok")).isEqualTo("evil_control-api pane.run ok");
        assertThat(McpWriteConsent.displayClient(null)).isEqualTo("unknown");
        assertThat(McpWriteConsent.displayClient("x".repeat(200))).hasLength(McpWriteConsent.MAX_CLIENT_CHARS);
    }

    // --- harness -----------------------------------------------------------------------------------

    private static ControlSession mcp(String connectionId, String mcpSession) {
        return new ControlSession(connectionId, EndpointDescriptor.TRANSPORT_UNIX, true, "Some Host",
            ControlSession.ClientKind.MCP, mcpSession, frame -> { });
    }

    private static McpPaneWriteState state(boolean pacing, boolean alternate, boolean foreign,
                                           boolean broadcast, boolean multiExec, boolean agent) {
        return new McpPaneWriteState("tab", true, pacing, alternate, foreign, broadcast, multiExec, agent);
    }

    private String written(String paneId) {
        return new String(surface.written(paneId), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingWrite {
        void run() throws Throwable;
    }

    /** Answers from a queue, or with a custom function, and records every question. */
    private static final class ScriptedPrompter implements McpWriteConsent.Prompter {

        final List<McpWriteConsent.Request> asked = new ArrayList<>();

        private final Deque<McpWriteConsent.Decision> answers = new ArrayDeque<>();

        private McpWriteConsent.Prompter custom;

        void answer(McpWriteConsent.Decision... decisions) {
            custom = null;
            answers.addAll(List.of(decisions));
        }

        void answerWith(McpWriteConsent.Prompter prompter) {
            custom = prompter;
        }

        @Override
        public McpWriteConsent.Decision ask(McpWriteConsent.Request request, long timeoutMillis)
                throws TimeoutException, InterruptedException {
            asked.add(request);
            if (custom != null) {
                return custom.ask(request, timeoutMillis);
            }
            if (answers.isEmpty()) {
                throw new AssertionError("an unexpected consent question: " + request);
            }
            return answers.removeFirst();
        }
    }
}
