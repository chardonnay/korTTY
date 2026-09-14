package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentActionsTest {

    private static final PaneRef PANE = new PaneRef("tab-1", "terminal-a");
    private static final PaneRef BLOCKED_PANE = new PaneRef("tab-1", "terminal-b");
    private static final PaneRef UNKNOWN_PANE = new PaneRef("tab-9", "terminal-z");
    private static final AgentProcess PROCESS = new AgentProcess(4242L, CodingAgentKind.CLAUDE_CODE,
        "claude --resume", Instant.parse("2026-01-02T03:04:05Z"));

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> auditLines = new ArrayList<>();
    private CodingAgentRegistry registry;
    private FakePaneAccess panes;
    private RecordingTtyConnector connector;
    private RecordingTtyConnector blockedConnector;
    private CodingAgentActions actions;

    @BeforeMethod
    void setUp() {
        clock.set(1_000_000L);
        auditLines.clear();
        registry = CodingAgentRegistry.forTests(new FakeFocusOracle(), clock::get);
        panes = new FakePaneAccess();
        connector = panes.connect(PANE);
        blockedConnector = panes.connect(BLOCKED_PANE);
        actions = new CodingAgentActions(registry, panes, (verb, pane, detail) ->
            auditLines.add(verb + " " + pane.paneId() + " " + detail));
        detect(PANE, CodingAgentState.WORKING, "permission-prompt", "Do you want to proceed?");
        detect(BLOCKED_PANE, CodingAgentState.BLOCKED, "permission-prompt", "Do you want to proceed?");
    }

    private void detect(PaneRef pane, CodingAgentState state, String rule, String evidence) {
        DetectionResult result = DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, rule, evidence);
        registry.onEvent(new CodingAgentEvent(pane, DetectionResult.NONE, result, PROCESS,
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(0)));
    }

    private static String text(RecordingTtyConnector connector) {
        return new String(connector.written(), StandardCharsets.UTF_8);
    }

    @Test
    void sendTextWritesTheExactBytes() throws Exception {
        actions.sendText(PANE, "héllo");

        assertThat(connector.written()).isEqualTo("héllo".getBytes(StandardCharsets.UTF_8));
        assertThat(connector.writeCount()).isEqualTo(1);
        assertThat(auditLines).containsExactly("sendText terminal-a bytes=6");
    }

    @Test
    void unknownPaneIsPaneNotFound() {
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.sendText(UNKNOWN_PANE, "x"));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.PANE_NOT_FOUND);
        assertThat(e.wireCode()).isEqualTo("pane_not_found");
        assertThat(auditLines).isEmpty();

        CodingAgentActionException nullPane = expectThrows(CodingAgentActionException.class,
            () -> actions.sendText(null, "x"));
        assertThat(nullPane.code()).isEqualTo(CodingAgentActionException.Code.PANE_NOT_FOUND);
    }

    @Test
    void registeredPaneWithoutConnectorIsPaneNotFound() {
        panes.remove(PANE);
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.sendKey(PANE, KeyChord.ENTER));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.PANE_NOT_FOUND);
        assertThat(actions.isConnected(PANE)).isFalse();
    }

    @Test
    void disconnectedConnectorIsNotConnected() {
        connector.setConnected(false);
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.sendText(PANE, "x"));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.NOT_CONNECTED);
        assertThat(connector.written()).isEmpty();
        assertThat(actions.isConnected(PANE)).isFalse();
        assertThat(auditLines).isEmpty();
    }

    @Test
    void ioExceptionBecomesWriteFailedWithCause() {
        connector.failNextWrite("pty closed");
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.sendText(PANE, "x"));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.WRITE_FAILED);
        assertThat(e.getCause()).isInstanceOf(IOException.class);
        assertThat(e.getCause()).hasMessageThat().isEqualTo("pty closed");
        assertThat(e.wireCode()).isEqualTo("write_failed");
        assertThat(auditLines).isEmpty();
    }

    @Test
    void sendKeysWritesTheChordBytes() throws Exception {
        actions.sendKeys(PANE, List.of(KeyChord.Y, KeyChord.ENTER));
        assertThat(text(connector)).isEqualTo("y\r");
        assertThat(auditLines).containsExactly("sendKeys terminal-a bytes=2");

        connector.clear();
        auditLines.clear();
        actions.sendKey(PANE, KeyChord.CTRL_C);
        assertThat(connector.written()).isEqualTo(new byte[] {0x03});
        assertThat(auditLines).containsExactly("sendKeys terminal-a bytes=1");
    }

    @Test
    void emptyChordListIsEmptyInput() {
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.sendKeys(PANE, List.of()));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.EMPTY_INPUT);
        CodingAgentActionException nullChord = expectThrows(CodingAgentActionException.class,
            () -> actions.sendKey(PANE, null));
        assertThat(nullChord.code()).isEqualTo(CodingAgentActionException.Code.EMPTY_INPUT);
        CodingAgentActionException emptyText = expectThrows(CodingAgentActionException.class,
            () -> actions.sendText(PANE, ""));
        assertThat(emptyText.code()).isEqualTo(CodingAgentActionException.Code.EMPTY_INPUT);
        assertThat(connector.written()).isEmpty();
    }

    @Test
    void promptOnBlockedAgentIsRefused() {
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.prompt(BLOCKED_PANE, "continue"));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.AGENT_BLOCKED);
        assertThat(e.wireCode()).isEqualTo("agent_blocked");
        assertThat(blockedConnector.written()).isEmpty();
        assertThat(auditLines).isEmpty();
    }

    @Test
    void singleLinePromptIsTextPlusEnter() throws Exception {
        actions.prompt(PANE, "fix the failing test\n");
        assertThat(text(connector)).isEqualTo("fix the failing test\r");
        assertThat(auditLines).containsExactly("prompt terminal-a bytes=21 lines=1 bracketed=false");
    }

    @Test
    void multiLinePromptIsBracketedWhenThePaneEnabledIt() throws Exception {
        panes.setBracketedPaste(PANE, true);
        actions.prompt(PANE, "first\r\nsecond");
        assertThat(text(connector)).isEqualTo("\u001b[200~first\rsecond\u001b[201~\r");
        assertThat(auditLines).containsExactly("prompt terminal-a bytes=25 lines=2 bracketed=true");
    }

    @Test
    void multiLinePromptWithoutBracketedPasteJoinsWithEnter() throws Exception {
        actions.prompt(PANE, "first\nsecond");
        assertThat(text(connector)).isEqualTo("first\rsecond\r");
        assertThat(auditLines).containsExactly("prompt terminal-a bytes=13 lines=2 bracketed=false");
    }

    @Test
    void blankPromptIsEmptyInput() {
        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.prompt(PANE, "  \n\r\n"));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.EMPTY_INPUT);
        assertThat(connector.written()).isEmpty();
        assertThrows(CodingAgentActionException.class, () -> actions.prompt(PANE, null));
    }

    @Test
    void hostShortcutConflictIsRefusedUnlessBracketed() throws Exception {
        panes.setIntercept(line -> line.startsWith("agent "));
        panes.setHostShortcutCommandName("agent");

        CodingAgentActionException single = expectThrows(CodingAgentActionException.class,
            () -> actions.prompt(PANE, "agent explain this"));
        assertThat(single.code()).isEqualTo(CodingAgentActionException.Code.HOST_SHORTCUT_CONFLICT);
        assertThat(single.wireCode()).isEqualTo("host_shortcut_conflict");
        assertThat(single.getMessage()).contains("agent");
        assertThat(connector.written()).isEmpty();

        CodingAgentActionException multi = expectThrows(CodingAgentActionException.class,
            () -> actions.prompt(PANE, "agent explain this\nplease"));
        assertThat(multi.code()).isEqualTo(CodingAgentActionException.Code.HOST_SHORTCUT_CONFLICT);
        assertThat(connector.written()).isEmpty();
        assertThat(auditLines).isEmpty();

        panes.setBracketedPaste(PANE, true);
        actions.prompt(PANE, "agent explain this\nplease");
        assertThat(text(connector)).isEqualTo("\u001b[200~agent explain this\rplease\u001b[201~\r");

        connector.clear();
        actions.prompt(PANE, "not the shortcut");
        assertThat(text(connector)).isEqualTo("not the shortcut\r");
    }

    @Test
    void explainDescribesDetectionRuleProcessAndTime() throws Exception {
        clock.set(1_000_000L + 134_000L);
        String explanation = actions.explain(BLOCKED_PANE);

        DetectionResult detection = registry.entry(BLOCKED_PANE).orElseThrow().detection();
        assertThat(explanation).contains(detection.explain());
        assertThat(explanation).contains("permission-prompt");
        assertThat(explanation).contains("pid 4242");
        assertThat(explanation).contains("claude --resume");
        assertThat(explanation).contains("since");
        assertThat(explanation).contains("2:14");
        assertThat(explanation).contains("BLOCKED");
        assertThat(explanation).contains("tab-1/terminal-b");
        assertThat(auditLines).hasSize(1);
        assertThat(auditLines.get(0)).startsWith("explain terminal-b chars=");
        assertThat(auditLines.get(0)).doesNotContain("proceed");
    }

    @Test
    void explainWithoutProcessOrRule() throws Exception {
        PaneRef pane = new PaneRef("tab-2", "terminal-c");
        DetectionResult fallback = DetectionResult.of(CodingAgentKind.CODEX, CodingAgentState.IDLE, null, null);
        registry.onEvent(new CodingAgentEvent(pane, DetectionResult.NONE, fallback, null,
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(0)));

        String explanation = actions.explain(pane);

        assertThat(explanation).contains("Process: unknown");
        assertThat(explanation).contains("Rule: none");
        assertThat(explanation).contains("IDLE");
    }

    @Test
    void renameSetsAndClearsTheRegistryAlias() throws Exception {
        actions.rename(PANE, "api");
        assertThat(registry.entry(PANE).orElseThrow().alias()).isEqualTo("api");
        assertThat(auditLines).containsExactly("rename terminal-a aliasChars=3");

        auditLines.clear();
        actions.rename(PANE, "   ");
        assertThat(registry.entry(PANE).orElseThrow().alias()).isNull();
        assertThat(auditLines).containsExactly("rename terminal-a alias=cleared");

        CodingAgentActionException e = expectThrows(CodingAgentActionException.class,
            () -> actions.rename(UNKNOWN_PANE, "x"));
        assertThat(e.code()).isEqualTo(CodingAgentActionException.Code.PANE_NOT_FOUND);
    }

    @Test
    void auditNeverContainsTheText() throws Exception {
        actions.sendText(PANE, "secret token");
        actions.prompt(PANE, "another secret");
        actions.sendKeys(PANE, List.of(KeyChord.N, KeyChord.ENTER));
        actions.rename(PANE, "secret alias");
        actions.explain(PANE);

        assertThat(auditLines).hasSize(5);
        for (String line : auditLines) {
            assertThat(line).doesNotContain("secret");
        }
    }

    @Test
    void isConnectedReflectsTheConnector() {
        assertThat(actions.isConnected(PANE)).isTrue();
        connector.setConnected(false);
        assertThat(actions.isConnected(PANE)).isFalse();
        assertThat(actions.isConnected(UNKNOWN_PANE)).isFalse();
        assertThat(actions.isConnected(null)).isFalse();
    }

    @Test
    void throwingAuditSinkDoesNotFailTheVerb() throws Exception {
        CodingAgentActions noisy = new CodingAgentActions(registry, panes, (verb, pane, detail) -> {
            throw new IllegalStateException("audit down");
        });
        noisy.sendText(PANE, "x");
        assertThat(text(connector)).isEqualTo("x");
    }

    @Test
    void defaultAuditSinkIsUsedWhenNullIsGiven() throws Exception {
        CodingAgentActions logging = new CodingAgentActions(registry, panes, null);
        logging.sendKey(PANE, KeyChord.ESC);
        assertThat(connector.written()).isEqualTo(new byte[] {0x1b});
    }
}
