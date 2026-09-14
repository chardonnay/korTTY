package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class PaneOutputWaiterTest {

    private static final String PANE = "p1a2b";

    private FakeControlSurface surface;

    private FakePaneReader reader;

    private ScheduledExecutorService timer;

    private PaneOutputWaiter waiter;

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, "t1", "w1", 0, true, true, 4711L));
        reader = new FakePaneReader(PANE);
        surface.setReader(PANE, reader);
        timer = new ScheduledThreadPoolExecutor(1);
        waiter = new PaneOutputWaiter(surface, UiDispatcher.DIRECT, timer);
    }

    @AfterMethod
    void tearDown() {
        timer.shutdownNow();
    }

    @Test(timeOut = 30_000)
    void aPatternThatIsAlreadyOnScreenMatchesOnTheFirstPoll() throws Exception {
        reader.setLines(ReadMode.RECENT, List.of("building", "user@host:~$ "));

        MatchResult result = waiter.await(PANE, null, "user@host", ReadMode.RECENT, 200, 5_000L, 50L);

        assertThat(result.matched()).isTrue();
        assertThat(result.match()).isEqualTo("user@host");
        assertThat(result.line()).isEqualTo("user@host:~$ ");
        assertThat(result.lineIndex()).isEqualTo(1);
        assertThat(result.screen().lines()).hasSize(2);
    }

    @Test(timeOut = 30_000)
    void aRegexMatchReportsTheMatchedTextAndItsLine() throws Exception {
        reader.setLines(ReadMode.RECENT, List.of("noise", "exit code 7"));

        MatchResult result =
            waiter.await(PANE, "exit code (\\d+)", null, ReadMode.RECENT, 200, 5_000L, 50L);

        assertThat(result.match()).isEqualTo("exit code 7");
        assertThat(result.lineIndex()).isEqualTo(1);
    }

    @Test(timeOut = 30_000)
    void aWaitThatNeverMatchesTimesOutWithWhatItLastSaw() {
        reader.setLines(ReadMode.RECENT, List.of("still building", ""));

        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, null, "done", ReadMode.RECENT, 200, 300L, 50L));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(failure.data()).containsKey("waited_millis");
        assertThat(failure.data()).containsEntry("last_line", "still building");
        assertThat(failure.data()).doesNotContainKey("clamped_to");
    }

    @Test(timeOut = 30_000)
    void neitherOrBothPatternsIsInvalidParams() {
        assertThat(expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, null, null, ReadMode.RECENT, 200, 300L, 50L)).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, "a", "a", ReadMode.RECENT, 200, 300L, 50L)).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test(timeOut = 30_000)
    void anInvalidRegexCarriesThePatternSyntaxMessage() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, "(unclosed", null, ReadMode.RECENT, 200, 300L, 50L));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_REGEX);
        assertThat(String.valueOf(failure.data().get("detail"))).contains("Unclosed group");
    }

    @Test(timeOut = 30_000)
    void aPatternOverTheCharacterCapIsInvalidParams() {
        String pattern = "a".repeat(ControlApiProtocol.MAX_REGEX_CHARS + 1);

        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, pattern, null, ReadMode.RECENT, 200, 300L, 50L));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(failure.data()).containsEntry("max", ControlApiProtocol.MAX_REGEX_CHARS);
    }

    @Test(timeOut = 30_000)
    void aCatastrophicPatternStillEndsWithinTwiceItsOwnDeadline() {
        reader.setLines(ReadMode.RECENT, List.of("a".repeat(4_000) + "b"));
        long budget = 500L;

        long start = System.nanoTime();
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, "(a+)+$", null, ReadMode.RECENT, 200, budget, 50L));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(failure.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(elapsed).isLessThan(budget * 2);
    }

    @Test(timeOut = 30_000)
    void aTimeoutOverTheHardCapIsClampedAndTheClampIsWhatTheErrorWouldReport() {
        // Waiting the clamped ten minutes for real is not a unit test, so the clamp itself is
        // asserted on the pure helper the timeout data is built from.
        assertThat(PaneOutputWaiter.clampReport(ControlApiProtocol.WAIT_HARD_CAP_MILLIS + 1L))
            .isEqualTo(ControlApiProtocol.WAIT_HARD_CAP_MILLIS);
        assertThat(PaneOutputWaiter.clampReport(ControlApiProtocol.WAIT_HARD_CAP_MILLIS)).isNull();
        assertThat(PaneOutputWaiter.clampReport(1_000L)).isNull();
    }

    @Test(timeOut = 30_000)
    void aPaneWithoutAReadHandleIsPaneNotFound() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> waiter.await("p9999", null, "x", ReadMode.RECENT, 200, 300L, 50L));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.PANE_NOT_FOUND);
    }

    @Test(timeOut = 30_000)
    void outputThatAppearsLaterIsFoundByALaterPoll() throws Exception {
        reader.setLines(ReadMode.RECENT, List.of("working"));
        timer.schedule(() -> reader.setLines(ReadMode.RECENT, List.of("working", "ready")),
            150L, TimeUnit.MILLISECONDS);

        MatchResult result = waiter.await(PANE, null, "ready", ReadMode.RECENT, 200, 10_000L, 50L);

        assertThat(result.matched()).isTrue();
        assertThat(result.waitedMillis()).isAtLeast(100L);
    }
}
