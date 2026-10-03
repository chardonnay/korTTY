package de.kortty.ui;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The title a program sets with OSC 0/2 is remote-controlled text: it is cleaned and capped before
 * the tab keeps it, reaches the tab only through the FX executor and at most one queued hand-over
 * at a time, and only the focused pane's title is shown. Runs without a JavaFX toolkit: the FX
 * executor is a queue the test drains.
 */
class ShellTitleTrackerTest {

    private final Deque<Runnable> fxQueue = new ArrayDeque<>();
    private final AtomicReference<String> focused = new AtomicReference<>("A");
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final List<String> shown = new ArrayList<>();
    private ShellTitleTracker<String> tracker;

    @BeforeMethod
    void setUp() {
        fxQueue.clear();
        focused.set("A");
        enabled.set(true);
        shown.clear();
        tracker = new ShellTitleTracker<>(fxQueue::add, focused::get, enabled::get);
        tracker.setListener(shown::add);
    }

    @Test
    void aTitleIsCleanedOfControlAndBidiCharactersTrimmedAndCapped() {
        assertThat(ShellTitleTracker.normalize("  daniel@web01: ~/logs  ")).isEqualTo("daniel@web01: ~/logs");
        assertWithMessage("an escape sequence must not reach the tab bar")
                .that(ShellTitleTracker.normalize("\u001B[31mprod\u0007")).isEqualTo("[31mprod");
        assertWithMessage("an override must not make the title read differently from its characters")
                .that(ShellTitleTracker.normalize("prod‮bd-gnitset")).isEqualTo("prodbd-gnitset");
        assertThat(ShellTitleTracker.normalize("two\nlines")).isEqualTo("two lines");

        String longTitle = "x".repeat(ShellTitleTracker.MAX_LENGTH + 40);
        assertThat(ShellTitleTracker.normalize(longTitle)).hasLength(ShellTitleTracker.MAX_LENGTH);
        assertThat(ShellTitleTracker.MAX_LENGTH).isEqualTo(80);
    }

    @Test
    void nothingVisibleAndThePanesDefaultTitleMeanNoTitle() {
        assertThat(ShellTitleTracker.normalize(null)).isNull();
        assertThat(ShellTitleTracker.normalize("")).isNull();
        assertThat(ShellTitleTracker.normalize(" \t\u001B‮ ")).isNull();
        assertWithMessage("vim restores the pane's start title on exit when it never had another one")
                .that(ShellTitleTracker.normalize(ShellTitleTracker.PANE_DEFAULT_TITLE)).isNull();
        assertThat(ShellTitleTracker.PANE_DEFAULT_TITLE).isEqualTo("Terminal");
        assertThat(ShellTitleTracker.normalize("Terminal 2")).isEqualTo("Terminal 2");
    }

    @Test
    void aHugeTitleIsCutBeforeItIsCleanedWithoutSplittingACharacter() {
        String huge = "😀".repeat(2_000_000);
        String title = ShellTitleTracker.normalize(huge);
        assertThat(title.codePointCount(0, title.length())).isEqualTo(ShellTitleTracker.MAX_LENGTH);
        assertThat(title).isEqualTo("😀".repeat(ShellTitleTracker.MAX_LENGTH));
        assertThat(ShellTitleTracker.normalize("a" + "😀".repeat(ShellTitleTracker.MAX_LENGTH * 8)))
                .isEqualTo("a" + "😀".repeat(ShellTitleTracker.MAX_LENGTH - 1));
    }

    @Test
    void aBurstOfTitlesQueuesOneHandOverThatShowsTheLastOne() {
        for (int i = 0; i < 1_000; i++) {
            tracker.titleChanged("A", "spinner " + i);
        }
        assertWithMessage("a program rewriting its title in a loop must not flood the FX thread")
                .that(fxQueue).hasSize(1);
        assertWithMessage("nothing reaches the tab before the FX thread runs the hand-over")
                .that(shown).isEmpty();

        drainFx();
        assertThat(shown).containsExactly("spinner 999");
        assertThat(tracker.shownTitle()).isEqualTo("spinner 999");
    }

    @Test
    void aTitleThatArrivesDuringTheHandOverQueuesTheNextOne() {
        tracker.setListener(title -> {
            shown.add(title);
            if (shown.size() == 1) {
                tracker.titleChanged("A", "second");
            }
        });
        tracker.titleChanged("A", "first");
        drainFx();

        assertThat(shown).containsExactly("first", "second").inOrder();
    }

    @Test
    void theListenerHearsOnlyChanges() {
        tracker.titleChanged("A", "same");
        drainFx();
        tracker.titleChanged("A", "  same ");
        drainFx();

        assertThat(shown).containsExactly("same");
    }

    @Test
    void theTabShowsTheFocusedPanesTitle() {
        tracker.titleChanged("B", "htop");
        drainFx();
        assertWithMessage("a title from a pane without the focus does not rename the tab")
                .that(shown).isEmpty();

        tracker.titleChanged("A", "daniel@web01: ~");
        drainFx();
        focused.set("B");
        tracker.publish();
        focused.set("C");
        tracker.publish();

        assertThat(shown).containsExactly("daniel@web01: ~", "htop", null).inOrder();
    }

    @Test
    void aClosedPaneTakesItsTitleAlong() {
        tracker.titleChanged("A", "vim notes.txt");
        drainFx();
        tracker.paneClosed("A");

        assertThat(shown).containsExactly("vim notes.txt", null).inOrder();
        focused.set("A");
        tracker.publish();
        assertThat(tracker.shownTitle()).isNull();
    }

    @Test
    void aReconnectDropsTheOldSessionsTitle() {
        tracker.titleChanged("A", "old shell");
        drainFx();
        tracker.paneReset("A");
        tracker.paneReset("A");

        assertThat(shown).containsExactly("old shell", null).inOrder();
    }

    @Test
    void anEmptyOrDefaultTitleGoesBackToNoTitle() {
        tracker.titleChanged("A", "make test");
        drainFx();
        tracker.titleChanged("A", "Terminal");
        drainFx();

        assertThat(shown).containsExactly("make test", null).inOrder();
    }

    @Test
    void whileSwitchedOffNothingIsQueuedOrShownButTheTitleIsKeptForLater() {
        enabled.set(false);
        tracker.titleChanged("A", "daniel@web01: ~");
        assertThat(fxQueue).isEmpty();
        tracker.publish();
        assertThat(shown).isEmpty();

        enabled.set(true);
        tracker.publish();
        assertThat(shown).containsExactly("daniel@web01: ~");

        enabled.set(false);
        tracker.publish();
        assertThat(shown).containsExactly("daniel@web01: ~", null).inOrder();
    }

    @Test
    void titlesFromAnotherThreadReachTheTabOnlyThroughTheFxExecutor() throws Exception {
        List<Runnable> handOvers = java.util.Collections.synchronizedList(new ArrayList<>());
        ShellTitleTracker<String> threaded = new ShellTitleTracker<>(handOvers::add, focused::get, enabled::get);
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        threaded.setListener(seen::add);

        Thread emulator = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                threaded.titleChanged("A", "build " + i);
            }
        });
        emulator.start();
        emulator.join();

        assertThat(seen).isEmpty();
        assertThat(handOvers).hasSize(1);
        handOvers.get(0).run();
        assertThat(seen).containsExactly("build 499");
    }

    @Test
    void afterDisposeLateTitlesAreIgnored() {
        tracker.titleChanged("A", "before");
        tracker.dispose();
        drainFx();
        tracker.titleChanged("A", "after");

        assertThat(shown).isEmpty();
        assertThat(fxQueue).isEmpty();
    }

    private void drainFx() {
        while (!fxQueue.isEmpty()) {
            fxQueue.poll().run();
        }
    }
}
