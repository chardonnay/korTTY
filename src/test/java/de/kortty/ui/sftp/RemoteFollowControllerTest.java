package de.kortty.ui.sftp;

import de.kortty.core.RemoteDirectoryChange.Source;
import de.kortty.ui.sftp.RemoteFollowController.State;
import de.kortty.ui.sftp.RemoteFollowController.Verdict;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The follow rules of the remote files sidebar (D23), with a manual clock: OSC 7 bursts list once,
 * a typed {@code cd} waits for a native prompt, a foreign verdict pauses, a focus switch follows
 * the other pane and a reconnect lists again.
 */
class RemoteFollowControllerTest {

    private ManualScheduler clock;
    private List<String> requests;
    private List<State> states;
    private RemoteFollowController controller;

    @BeforeMethod
    void setUp() {
        clock = new ManualScheduler();
        requests = new ArrayList<>();
        states = new ArrayList<>();
        controller = new RemoteFollowController(clock, new RemoteFollowController.Listener() {
            @Override
            public void listRequested(String paneKey, String path) {
                requests.add(paneKey + ":" + path);
            }

            @Override
            public void stateChanged(State state, String path) {
                states.add(state);
            }
        });
        controller.activate("p1", "/home/tester", Verdict.NATIVE);
        requests.clear();
    }

    @Test
    void aBurstOfFiveOsc7ChangesListsOnce() {
        for (String path : List.of("/a", "/b", "/c", "/d", "/e")) {
            controller.directoryChanged("p1", path, Source.OSC7);
            clock.advance(100);
        }
        assertThat(requests).isEmpty();

        clock.advance(RemoteFollowController.DEBOUNCE_MILLIS);

        assertThat(requests).containsExactly("p1:/e");
        assertThat(controller.followedPath()).isEqualTo("/e");
    }

    @Test
    void theAgentHookIsFollowedLikeOsc7() {
        controller.directoryChanged("p1", "/srv", Source.AGENT_HOOK);
        clock.advance(RemoteFollowController.DEBOUNCE_MILLIS);

        assertThat(requests).containsExactly("p1:/srv");
    }

    @Test
    void aTypedCdWithoutANativePromptListsNothing() {
        controller.directoryChanged("p1", "/tmp", Source.TYPED_CD);
        controller.promptSeen("p1", Verdict.UNKNOWN);
        // The verdict of a directory change never confirms it: the screen still shows the cd line.
        controller.verdict("p1", Verdict.NATIVE);
        assertThat(controller.hasPending()).isTrue();

        clock.advance(RemoteFollowController.TYPED_CD_WINDOW_MILLIS);

        assertThat(controller.hasPending()).isFalse();
        controller.promptSeen("p1", Verdict.NATIVE);
        assertThat(requests).isEmpty();
    }

    @Test
    void aTypedCdFollowedByANativePromptListsOnce() {
        controller.directoryChanged("p1", "/tmp", Source.TYPED_CD);
        clock.advance(400);

        controller.promptSeen("p1", Verdict.NATIVE);
        controller.promptSeen("p1", Verdict.NATIVE);

        assertThat(requests).containsExactly("p1:/tmp");
        assertThat(controller.hasPending()).isFalse();
    }

    @Test
    void anOsc133PromptMarkConfirmsATypedCd() {
        controller.directoryChanged("p1", "/var/log", Source.TYPED_CD);
        controller.promptMark("p1");

        assertThat(requests).containsExactly("p1:/var/log");
    }

    @Test
    void aForeignVerdictPausesAndDropsPendingChanges() {
        controller.directoryChanged("p1", "/root", Source.TYPED_CD);
        controller.directoryChanged("p1", "/x", Source.OSC7);
        controller.directoryChanged("p1", "/root", Source.TYPED_CD);

        controller.verdict("p1", Verdict.FOREIGN);

        assertThat(controller.state()).isEqualTo(State.PAUSED_FOREIGN);
        assertThat(controller.hasPending()).isFalse();
        controller.promptMark("p1");
        controller.directoryChanged("p1", "/etc", Source.OSC7);
        clock.advance(RemoteFollowController.TYPED_CD_WINDOW_MILLIS);
        assertThat(requests).isEmpty();

        // Back as the session's own user: the last followed folder is listed again.
        controller.verdict("p1", Verdict.NATIVE);
        assertThat(controller.state()).isEqualTo(State.FOLLOWING);
        assertThat(requests).containsExactly("p1:/home/tester");
    }

    @Test
    void aFocusSwitchFollowsTheOtherSshPaneOnly() {
        controller.focusChanged("local", false, "/Users/me", Verdict.UNKNOWN);
        assertThat(controller.activePane()).isEqualTo("p1");

        controller.focusChanged("p2", true, "/srv/app", Verdict.NATIVE);

        assertThat(controller.activePane()).isEqualTo("p2");
        assertThat(requests).containsExactly("p2:/srv/app");
        // The first pane's shell is no longer followed.
        controller.directoryChanged("p1", "/elsewhere", Source.OSC7);
        clock.advance(RemoteFollowController.DEBOUNCE_MILLIS);
        assertThat(requests).containsExactly("p2:/srv/app");
    }

    @Test
    void aFocusSwitchToAForeignPaneListsTheLoginFolderPaused() {
        controller.focusChanged("p2", true, "/root", Verdict.FOREIGN);

        assertThat(controller.state()).isEqualTo(State.PAUSED_FOREIGN);
        assertThat(requests).containsExactly("p2:null");
    }

    @Test
    void disconnectThenReconnectListsAgain() {
        controller.directoryChanged("p1", "/data", Source.OSC7);
        controller.disconnected("p1");
        clock.advance(RemoteFollowController.DEBOUNCE_MILLIS);
        assertThat(controller.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(requests).isEmpty();
        controller.directoryChanged("p1", "/ignored", Source.OSC7);
        clock.advance(RemoteFollowController.DEBOUNCE_MILLIS);
        assertThat(requests).isEmpty();

        controller.reconnected("p1", "/data", Verdict.NATIVE);

        assertThat(controller.state()).isEqualTo(State.FOLLOWING);
        assertThat(requests).containsExactly("p1:/data");
    }

    @Test
    void thePinStopsFollowingAndUnpinningListsTheLatestFolder() {
        controller.setUserPaused(true);
        controller.directoryChanged("p1", "/a", Source.OSC7);
        controller.directoryChanged("p1", "/b", Source.TYPED_CD);
        controller.promptMark("p1");
        clock.advance(RemoteFollowController.TYPED_CD_WINDOW_MILLIS);
        assertThat(controller.state()).isEqualTo(State.PAUSED_USER);
        assertThat(requests).isEmpty();

        controller.setUserPaused(false);

        assertThat(controller.state()).isEqualTo(State.FOLLOWING);
        assertThat(requests).containsExactly("p1:/home/tester");
        assertThat(states).contains(State.PAUSED_USER);
    }

    /** Runs scheduled tasks when the test advances the clock. */
    private static final class ManualScheduler implements RemoteFollowController.Scheduler {
        private final List<Task> tasks = new ArrayList<>();
        private long now;

        @Override
        public RemoteFollowController.Cancellable schedule(Runnable task, long delayMillis) {
            Task scheduled = new Task(now + delayMillis, task);
            tasks.add(scheduled);
            return () -> tasks.remove(scheduled);
        }

        void advance(long millis) {
            long until = now + millis;
            while (true) {
                Task next = tasks.stream().filter(t -> t.due <= until)
                    .min((a, b) -> Long.compare(a.due, b.due)).orElse(null);
                if (next == null) {
                    break;
                }
                tasks.remove(next);
                now = next.due;
                next.task.run();
            }
            now = until;
        }

        private record Task(long due, Runnable task) {
        }
    }
}
