package de.kortty.ui;

import de.kortty.core.swarm.SwarmRunControl;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Closing an AI chat or AI Swarm tab stops what it is running, whichever way the tab closes. The
 * tab's close button fires its close request, which always cancelled; Close Tab, Close All Tabs,
 * opening a project and closing the window remove the tab from its pane, which fires no close
 * event, so a chat request went on and a swarm went on running commands on its servers for a tab
 * that was gone. Both tabs now share one idempotent {@code cancelForClose()} between the close
 * button and {@code MainWindow.disposeTabContent}. The cancellation is pinned with fake request
 * and swarm handles, the wiring against the source, since neither tab can be built without a
 * JavaFX stage.
 */
class AiTabCloseCancellationTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    @Test
    void aRunningChatRequestIsStoppedOnceAndItsThreadInterruptedOnce() {
        FakeRequest request = new FakeRequest();
        CountingThread thread = new CountingThread();

        assertThat(AiResultTab.cancelRequest(request, thread, null)).isTrue();
        assertThat(request.cancelCalls).isEqualTo(1);
        assertWithMessage("the HTTP request is interrupted where the provider allows it")
            .that(request.mayInterruptIfRunning).isTrue();
        assertThat(thread.interrupts).isEqualTo(1);

        assertWithMessage("a second close of the same tab finds nothing left to stop")
            .that(AiResultTab.cancelRequest(request, thread, null)).isFalse();
        assertWithMessage("a thread winding down after the cancel is not interrupted again")
            .that(thread.interrupts).isEqualTo(1);
    }

    @Test
    void aFinishedChatRequestIsLeftAlone() {
        FakeRequest request = new FakeRequest();
        request.done = true;
        CountingThread thread = new CountingThread();

        assertWithMessage("a chat whose answer arrived does not turn into 'Cancelled'")
            .that(AiResultTab.cancelRequest(request, thread, null)).isFalse();
        assertThat(thread.interrupts).isEqualTo(0);
    }

    @Test
    void aChatWithNothingRunningIsANoOp() {
        assertThat(AiResultTab.cancelRequest(null, null, null)).isFalse();
    }

    @Test
    void anOpenTerminalBroadcastIsStoppedOnce() {
        AtomicBoolean broadcastCancelled = new AtomicBoolean(false);

        assertThat(AiResultTab.cancelRequest(null, null, broadcastCancelled)).isTrue();
        assertWithMessage("the broadcast swarm polls this flag through its callback")
            .that(broadcastCancelled.get()).isTrue();
        assertThat(AiResultTab.cancelRequest(null, null, broadcastCancelled)).isFalse();
        assertThat(broadcastCancelled.get()).isTrue();
    }

    @Test
    void aRunningSwarmIsCancelledOnce() {
        SwarmRunControl run = new SwarmRunControl();

        assertThat(SwarmAgentTab.cancelRun(run)).isTrue();
        assertThat(run.isSwarmCancelled()).isTrue();
        assertWithMessage("a second close of the same tab leaves the cancelled run alone")
            .that(SwarmAgentTab.cancelRun(run)).isFalse();
        assertThat(run.isSwarmCancelled()).isTrue();
    }

    @Test
    void aSwarmTabWithoutARunIsANoOp() {
        assertThat(SwarmAgentTab.cancelRun(null)).isFalse();
    }

    @Test
    void theCloseButtonAndTheCancelButtonsUseTheSameCancellation() throws IOException {
        String chat = source("AiResultTab.java");
        assertThat(chat).contains("setOnCloseRequest(event -> cancelForClose());");
        assertThat(methodBody(chat, "void cancelForClose() {")).contains("cancelActiveRequest();");
        assertThat(methodBody(chat, "private void cancelActiveRequest() {"))
            .contains("cancelRequest(activeTask, activeThread, broadcastCancelled)");

        String swarm = source("SwarmAgentTab.java");
        assertThat(swarm).contains("setOnCloseRequest(event -> cancelForClose());");
        assertThat(methodBody(swarm, "void cancelForClose() {")).contains("cancelSwarm();");
        assertThat(methodBody(swarm, "private void cancelSwarm() {")).contains("cancelRun(swarmControl);");
    }

    @Test
    void closingWithoutTheCloseButtonCancelsBeforeItReleases() throws IOException {
        String window = source("MainWindow.java");
        String dispose = methodBody(window, "private void disposeTabContent(Tab tab) {");

        String chatBranch = dispose.substring(dispose.indexOf("} else if (tab instanceof AiResultTab aiResultTab) {"),
            dispose.indexOf("} else if (tab instanceof SwarmAgentTab swarmTab) {"));
        assertThat(chatBranch).contains("aiResultTab.cancelForClose();");
        assertWithMessage("like the close button: the request is stopped before the chat is released")
            .that(chatBranch.indexOf("aiResultTab.cancelForClose();"))
            .isLessThan(chatBranch.indexOf("aiResultTab.disposeRenderedContent();"));

        String swarmBranch = dispose.substring(dispose.indexOf("} else if (tab instanceof SwarmAgentTab swarmTab) {"),
            dispose.indexOf("} else if (tab instanceof DialogHostTab hostTab) {"));
        assertThat(swarmBranch).contains("swarmTab.cancelForClose();");
        assertThat(swarmBranch.indexOf("swarmTab.cancelForClose();"))
            .isLessThan(swarmBranch.indexOf("swarmTab.handleTabClosed();"));

        assertWithMessage("Close Tab, the Dashboard's Close and Close Other Tabs/to the Right release through disposeTabContent")
            .that(methodBody(window, "private boolean closeTabsByUser(List<Tab> tabs, Tab keepSelected, CloseCause cause) {"))
            .contains("disposeTabContent(tab);");
        assertWithMessage("Close All Tabs, opening a project and closing the window release through disposeTabContent")
            .that(methodBody(window, "private void closeAllTabs() {"))
            .contains("disposeTabContent(tab);");
    }

    /** Stands in for the chat's JavaFX Task: cancel succeeds once, and never once the answer is in. */
    private static final class FakeRequest implements Future<Object> {
        int cancelCalls;
        boolean mayInterruptIfRunning;
        boolean cancelled;
        boolean done;

        @Override
        public boolean cancel(boolean mayInterrupt) {
            cancelCalls++;
            if (done || cancelled) {
                return false;
            }
            mayInterruptIfRunning = mayInterrupt;
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    /** The request's worker thread; never started, it only counts interrupts. */
    private static final class CountingThread extends Thread {
        int interrupts;

        @Override
        public void interrupt() {
            interrupts++;
        }
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces in " + signature);
    }
}
