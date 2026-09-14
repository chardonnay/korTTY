package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FocusOracle;
import de.kortty.control.ControlApiException;
import de.kortty.control.ControlErrorCode;
import de.kortty.control.ControlSurface;
import de.kortty.control.PaneAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The bridge's thread contract, which is the part of it a headless test can prove and the part that
 * matters most.
 *
 * <p>{@code CodingAgentRegistry}'s readers are plain unsynchronised maps and
 * {@code MainWindow.getOpenWindows()} hands out the live list, so an off-thread call would not fail —
 * it would return a wrong or half-built answer. Every {@link ControlSurface} method must therefore
 * refuse loudly off the JavaFX application thread, and the handful documented ANY THREAD must not.
 *
 * <p>The tests run on a plain TestNG thread with no toolkit started, which is exactly the wrong
 * thread; nothing here needs a display.
 */
class ControlApiUiBridgeTest {

    private ControlApiUiBridge bridge;

    @BeforeMethod
    void createBridge() {
        CodingAgentRegistry registry =
            CodingAgentRegistry.forTests(FocusOracle.NEVER, () -> 0L);
        bridge = new ControlApiUiBridge(List::of, registry, null, () -> 0L);
    }

    /** Runs one surface call and hands back whatever it threw. */
    private Throwable refusal(ThrowingCall call) {
        return expectThrows(IllegalStateException.class, call::run);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void everyEnumerationMethodRefusesOffTheJavaFxThread() {
        assertThat(refusal(() -> bridge.listWindows())).hasMessageThat().contains("listWindows");
        assertThat(refusal(() -> bridge.listTabs(null))).hasMessageThat().contains("listTabs");
        assertThat(refusal(() -> bridge.listPanes(null, null))).hasMessageThat().contains("listPanes");
        assertThat(refusal(() -> bridge.focusedPane())).hasMessageThat().contains("focusedPane");
        assertThat(refusal(() -> bridge.resolve(PaneAddress.focused()))).hasMessageThat()
            .contains("resolve");
        assertThat(refusal(() -> bridge.paneForShellPids(List.of(1L)))).hasMessageThat()
            .contains("paneForShellPids");
    }

    @Test
    void everyReadAndWriteMethodRefusesOffTheJavaFxThread() {
        assertThat(refusal(() -> bridge.readerFor("p1"))).hasMessageThat().contains("readerFor");
        assertThat(refusal(() -> bridge.write("p1", new byte[] {1}))).hasMessageThat().contains("write");
        assertThat(refusal(() -> bridge.isBracketedPasteEnabled("p1"))).hasMessageThat()
            .contains("isBracketedPasteEnabled");
        assertThat(refusal(() -> bridge.wouldHostShortcutIntercept("ai hello"))).hasMessageThat()
            .contains("wouldHostShortcutIntercept");
        assertThat(refusal(() -> bridge.hostShortcutCommandName())).hasMessageThat()
            .contains("hostShortcutCommandName");
    }

    @Test
    void everyFocusSplitAndCloseMethodRefusesOffTheJavaFxThread() {
        assertThat(refusal(() -> bridge.focusPane("p1"))).hasMessageThat().contains("focusPane");
        assertThat(refusal(() -> bridge.focusTab("t1"))).hasMessageThat().contains("focusTab");
        assertThat(refusal(() -> bridge.paneRefOf("p1"))).hasMessageThat().contains("paneRefOf");
        assertThat(refusal(() -> bridge.attachSplitPane("p1", "vertical", new Object(), false)))
            .hasMessageThat().contains("attachSplitPane");
        assertThat(refusal(() -> bridge.closePane("p1"))).hasMessageThat().contains("closePane");
    }

    @Test
    void aRefusalNamesTheOffendingThreadSoTheBugIsFindable() {
        Thread.currentThread().setName("kortty-control-rx-1");
        assertWithMessage("a refusal a developer cannot trace back to a caller is only half a refusal")
            .that(refusal(() -> bridge.listWindows()).getMessage())
            .contains("kortty-control-rx-1");
    }

    @Test
    void isPaneConnectedIsAnyThreadAndAnswersFalseWhenNoWindowIsOpen() {
        assertWithMessage("agent.start polls this from a connection thread; it must answer, not throw")
            .that(bridge.isPaneConnected("p1a2b3c4d"))
            .isFalse();
    }

    @Test
    void isPaneConnectedMarshalsRatherThanReadingTheLiveWindowListFromAConnectionThread() {
        java.util.concurrent.atomic.AtomicReference<String> readOn =
            new java.util.concurrent.atomic.AtomicReference<>();
        ControlApiUiBridge marshalling = new ControlApiUiBridge(() -> {
            readOn.set(Thread.currentThread().getName());
            return List.of();
        }, CodingAgentRegistry.forTests(FocusOracle.NEVER, () -> 0L), null, () -> 0L);
        Thread.currentThread().setName("kortty-control-rx-1");

        assertThat(marshalling.isPaneConnected("p1a2b3c4d")).isFalse();

        assertWithMessage("MainWindow.getOpenWindows() hands out the live list and the split tree is"
                + " rebuilt on FX, so agent.start's 50 ms poll must never read either from its"
                + " connection thread")
            .that(readOn.get())
            .isNull();
    }

    @Test
    void prepareLocalShellSplitConnectorRefusesTheJavaFxThreadAndNotThisOne() throws Exception {
        // Off the FX thread it is allowed to run, and without a toolkit it degrades to ui_unavailable
        // rather than to a thread refusal: the connector preparation is documented ANY THREAD.
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> bridge.prepareLocalShellSplitConnector("p1a2b3c4d"));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.UI_UNAVAILABLE);
    }

    @Test
    void theDispatcherReportsThisThreadIsNotTheUiThread() {
        assertThat(bridge.isUiThread()).isFalse();
    }

    @Test
    void submitWithoutAToolkitFailsTheFutureInsteadOfHanging() {
        // Platform.runLater throws IllegalStateException when the toolkit is not running. UiCalls
        // turns that into ui_unavailable; what matters here is that the caller is never left parked.
        CompletableFuture<String> future;
        try {
            future = bridge.submit(() -> "unreachable");
        } catch (IllegalStateException expected) {
            return;
        }
        ExecutionException thrown = expectThrows(ExecutionException.class, future::get);
        assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
    }
}
