package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

import de.kortty.shellintegration.ShellIntegrationEvent.RemoteNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.testng.annotations.Test;

/**
 * A program's request for a desktop notification ({@code OSC 9}, {@code OSC 777;notify}): how its
 * payload is read, and how its text is cleaned before anything shows it. The program, often on a
 * server, chooses every character, so the text must not be able to break a line, reorder itself
 * with bidi controls or grow without bound. {@link RemoteNotificationSlot} hands one notification at
 * a time on to the UI thread.
 */
class RemoteNotificationTextTest {

    private static final String ESC = "\u001B";

    @Test
    void osc9TakesTheWholeTextSemicolonsIncluded() {
        assertThat(RemoteNotificationText.parseOsc9("Build done; 3 warnings; 0 errors"))
            .isEqualTo(new RemoteNotification(OwnedOsc.NOTIFICATION, null, "Build done; 3 warnings; 0 errors"));
        assertThat(RemoteNotificationText.parseOsc9("3 warnings"))
            .isEqualTo(new RemoteNotification(OwnedOsc.NOTIFICATION, null, "3 warnings"));
    }

    @Test
    void conEmuSubcommandsOfOsc9AreNoNotifications() {
        // ConEmu numbers its OSC 9 commands: 9;4 is the progress report, 9;9 the working directory.
        for (String payload : List.of("4;1;50", "4", "4;", "12", "9;\"C:\\\\\"", "")) {
            assertWithMessage(payload).that(RemoteNotificationText.parseOsc9(payload)).isNull();
        }
    }

    @Test
    void osc777NotifyJoinsEveryArgumentAfterTheTitleIntoTheBody() {
        assertThat(RemoteNotificationText.parseOsc777Notify("Claude Code;Waiting for input; step 2;3"))
            .isEqualTo(new RemoteNotification(OwnedOsc.NOTIFY, "Claude Code", "Waiting for input; step 2;3"));
        assertWithMessage("a title alone becomes the body")
            .that(RemoteNotificationText.parseOsc777Notify("Backup finished"))
            .isEqualTo(new RemoteNotification(OwnedOsc.NOTIFY, null, "Backup finished"));
        assertThat(RemoteNotificationText.parseOsc777Notify(";Body only"))
            .isEqualTo(new RemoteNotification(OwnedOsc.NOTIFY, null, "Body only"));
        assertThat(RemoteNotificationText.parseOsc777Notify("Title;"))
            .isEqualTo(new RemoteNotification(OwnedOsc.NOTIFY, null, "Title"));
        assertThat(RemoteNotificationText.parseOsc777Notify(";")).isNull();
        assertThat(RemoteNotificationText.parseOsc777Notify("")).isNull();
    }

    @Test
    void theSplitterReadsNotificationsTheSameWay() {
        assertThat(OscEventSplitter.parse(OwnedOsc.NOTIFICATION, "4;1;50")).isNull();
        assertThat(OscEventSplitter.parse(OwnedOsc.NOTIFICATION, "Done"))
            .isEqualTo(RemoteNotificationText.parseOsc9("Done"));
        assertThat(OscEventSplitter.parse(OwnedOsc.NOTIFY, "T;a;b"))
            .isEqualTo(RemoteNotificationText.parseOsc777Notify("T;a;b"));
    }

    @Test
    void controlCharactersAndDelAreRemovedAndLineBreaksBecomeSpaces() {
        RemoteNotificationText text = RemoteNotificationText.of(
            "Agent\u0007" + ESC + "[31m", "line one\r\nline two\u007F\u0085\u009B2Jend\u2028tail");
        assertThat(text.title()).isEqualTo("Agent[31m");
        assertThat(text.body()).isEqualTo("line one line two 2Jend tail");
        for (char c : (text.title() + text.body()).toCharArray()) {
            assertWithMessage("U+" + Integer.toHexString(c)).that(Character.isISOControl(c)).isFalse();
        }
    }

    @Test
    void bidiControlsCannotReorderTheText() {
        // RLO would show "exe.txt" as "txt.exe"; the isolates and marks would shift words around.
        RemoteNotificationText text = RemoteNotificationText.of("\u202Ereview\u202C", "open inv\u202Etxt.exe\u2066x\u2069\u200E\u200F\u061C");
        assertThat(text.title()).isEqualTo("review");
        assertThat(text.body()).isEqualTo("open invtxt.exex");
    }

    @Test
    void theTitleIsCutTo80AndTheBodyTo200Characters() {
        RemoteNotificationText text = RemoteNotificationText.of("t".repeat(500), "b".repeat(5_000));
        assertThat(text.title()).hasLength(RemoteNotificationText.MAX_TITLE_CHARS);
        assertThat(text.body()).hasLength(RemoteNotificationText.MAX_BODY_CHARS);
        assertThat(RemoteNotificationText.MAX_TITLE_CHARS).isEqualTo(80);
        assertThat(RemoteNotificationText.MAX_BODY_CHARS).isEqualTo(200);

        String emoji = "😀".repeat(300);
        RemoteNotificationText wide = RemoteNotificationText.of(null, emoji);
        assertWithMessage("a character outside the BMP counts once and is never split")
            .that(wide.body()).isEqualTo("😀".repeat(200));
    }

    @Test
    void theConstructorCleansToo() {
        RemoteNotificationText text = new RemoteNotificationText(" \u202E ", " body\u0007 ");
        assertWithMessage("a title with nothing visible is none").that(text.title()).isNull();
        assertThat(text.body()).isEqualTo("body");
        assertWithMessage("a body with nothing visible takes the title's place")
            .that(new RemoteNotificationText("Title", "\u0007\u200F")).isEqualTo(new RemoteNotificationText(null, "Title"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteNotificationText("\u0007", ESC));
    }

    @Test
    void nothingVisibleMeansNoNotification() {
        assertThat(RemoteNotificationText.of(null, null)).isNull();
        assertThat(RemoteNotificationText.of("  ", "\u0007" + ESC + "\u202E\u2069")).isNull();
        assertThat(RemoteNotificationText.of(new RemoteNotification(OwnedOsc.NOTIFICATION, null, "\r\n\t"))).isNull();
    }

    @Test
    void theTextIsTheTitleAndTheBody() {
        RemoteNotificationText withTitle = RemoteNotificationText.of(
            new RemoteNotification(OwnedOsc.NOTIFY, "Claude Code", "Claude needs your permission"));
        assertThat(withTitle.text()).isEqualTo("Claude Code: Claude needs your permission");
        RemoteNotificationText bodyOnly = RemoteNotificationText.of(
            new RemoteNotification(OwnedOsc.NOTIFICATION, null, "Build done; 3 warnings"));
        assertThat(bodyOnly.title()).isNull();
        assertThat(bodyOnly.text()).isEqualTo("Build done; 3 warnings");
    }

    @Test
    void aSummaryIsCutWithAnEllipsis() {
        RemoteNotificationText text = RemoteNotificationText.of("Title", "a long body of text");
        assertThat(text.summary(100)).isEqualTo("Title: a long body of text");
        assertThat(text.summary(26)).isEqualTo("Title: a long body of text");
        assertThat(text.summary(14)).isEqualTo("Title: a long…");
        assertWithMessage("no space before the ellipsis").that(text.summary(15)).isEqualTo("Title: a long…");
        assertThat(text.summary(1)).isEqualTo("…");
        assertThat(text.summary(0)).isEmpty();
        assertThat(RemoteNotificationText.of(null, "😀😀😀").summary(2)).isEqualTo("😀…");
    }

    @Test
    void theSlotHandsOnTheFirstNotificationOfABurstAndDropsTheRest() {
        RemoteNotificationSlot slot = new RemoteNotificationSlot();
        RemoteNotificationText first = RemoteNotificationText.of(null, "first");
        assertThat(slot.offer(first)).isTrue();
        assertWithMessage("one is on its way: the next is dropped, and nothing new is scheduled")
            .that(slot.offer(RemoteNotificationText.of(null, "second"))).isFalse();
        assertThat(slot.take()).isEqualTo(first);
        assertThat(slot.take()).isNull();
        assertWithMessage("once taken, the next one gets through")
            .that(slot.offer(RemoteNotificationText.of(null, "third"))).isTrue();
        assertThrows(NullPointerException.class, () -> slot.offer(null));
    }

    @Test
    void aFloodFromAnotherThreadSchedulesOneUiTaskAtATime() throws Exception {
        RemoteNotificationSlot slot = new RemoteNotificationSlot();
        List<Boolean> scheduled = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread emulator = new Thread(() -> {
            for (int i = 0; i < 10_000; i++) {
                if (slot.offer(RemoteNotificationText.of(null, "n" + i))) {
                    scheduled.add(true);
                }
            }
            done.countDown();
        }, "emulator");
        emulator.start();
        done.await();
        emulator.join();
        assertThat(scheduled).hasSize(1);
        assertThat(slot.take().body()).isEqualTo("n0");
    }
}
