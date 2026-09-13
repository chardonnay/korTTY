package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class ControlSplitServiceTest {

    private static final String SOURCE = "p1a2b";

    private static final String CREATED = "p7f31";

    private FakeControlSurface surface;

    private RecordingDispatcher ui;

    private List<String> audit;

    private ControlSplitService splits;

    /** Marks which calls happen inside a UI hop, which is the whole point of the split ordering. */
    private static final class RecordingDispatcher implements UiDispatcher {

        private boolean inHop;

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            inHop = true;
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            } finally {
                inHop = false;
            }
        }

        @Override
        public boolean isUiThread() {
            return inHop;
        }
    }

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        ui = new RecordingDispatcher();
        surface.setInUiHop(() -> ui.inHop);
        audit = new ArrayList<>();
        splits = new ControlSplitService(surface, ui,
            (verb, pane, detail) -> audit.add(verb + " " + pane + " " + detail));
    }

    @Test
    void theHappyPathConnectsOffTheHopAndAttachesInsideOne() throws Exception {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, true, true, 4711L));
        PaneInfo created = FakeControlSurface.pane(CREATED, "t1", "w1", 1, true, true, 4712L);
        surface.setAttachResult(created);

        PaneInfo result = splits.split(SOURCE, "vertical", false);

        assertThat(result).isEqualTo(created);
        List<String> relevant = new ArrayList<>();
        for (String call : surface.calls()) {
            if (call.startsWith("prepareLocalShellSplitConnector") || call.startsWith("attachSplitPane")) {
                relevant.add(call);
            }
        }
        assertThat(relevant).containsExactly(
            "prepareLocalShellSplitConnector(inHop=false)",
            "attachSplitPane(inHop=true)").inOrder();
        assertThat(audit).containsExactly(
            "pane.split " + SOURCE + " orientation=vertical focus=false new_pane=" + CREATED);
    }

    @Test
    void aNonLocalShellPaneIsUnsupportedAndTheConnectorIsNeverPrepared() {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, false, true, -1L));

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> splits.split(SOURCE, "horizontal", true));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.UNSUPPORTED);
        assertThat(failure.data()).containsEntry("reason", "split is local-shell only");
        assertThat(failure.data()).containsEntry("protocol", "SSH");
        for (String call : surface.calls()) {
            assertThat(call).doesNotContain("prepareLocalShellSplitConnector");
        }
    }

    @Test
    void aDisconnectedSourcePaneIsRefusedBeforeAnythingIsSpawned() {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, true, false, 4711L));

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> splits.split(SOURCE, "horizontal", true));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.NOT_CONNECTED);
    }

    @Test
    void anAttachThatReturnsNullIsSplitFailedRatherThanASilentAbort() {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, true, true, 4711L));
        surface.setAttachResult(null);

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> splits.split(SOURCE, "horizontal", true));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.SPLIT_FAILED);
        assertThat(audit).isEmpty();
    }

    @Test
    void anUnknownOrientationIsInvalidParams() {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, true, true, 4711L));

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> splits.split(SOURCE, "diagonal", true));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void closingTheLastPaneOfATabIsRefusedWithAHint() {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, true, true, 4711L));
        surface.failClose(new ControlApiException(ControlErrorCode.LAST_PANE,
            "This is the tab's last pane", Map.of()));

        ControlApiException failure = expectThrows(ControlApiException.class, () -> splits.close(SOURCE));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.LAST_PANE);
        assertThat(failure.data()).containsEntry("hint", "close the tab yourself");
        assertThat(audit).isEmpty();
    }

    @Test
    void closingASplitPaneAuditsExactlyOneLine() throws Exception {
        surface.addPane(FakeControlSurface.pane(SOURCE, "t1", "w1", 0, true, true, 4711L));

        splits.close(SOURCE);

        assertThat(audit).containsExactly("pane.close " + SOURCE + " closed=1");
        assertThat(surface.calls()).contains("closePane(inHop=true)");
    }
}
