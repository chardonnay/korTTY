package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class ExternalCommandRunnerTest {

    private static final String SLEEP_MARKER = "sleep 5";

    @BeforeMethod
    void skipOnWindows() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("POSIX-only process tests");
        }
    }

    @Test(timeOut = 30_000)
    void successfulCommandReturnsExitCodeAndOutput() {
        requireBinary("/bin/echo");

        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(List.of("/bin/echo", "x"), 5_000);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).isEqualTo("x");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.ok()).isTrue();
    }

    @Test(timeOut = 30_000)
    void failingCommandIsNotOk() {
        requireBinary("/bin/false");

        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(List.of("/bin/false"), 5_000);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.ok()).isFalse();
    }

    @Test(timeOut = 30_000)
    void timedOutCommandIsDestroyed() throws Exception {
        requireBinary("/bin/sleep");
        long started = System.nanoTime();

        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(List.of("/bin/sleep", "5"), 200);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.ok()).isFalse();
        assertThat(elapsedMillis).isLessThan(2_000L);
        assertThat(sleepChildGone(2_000L)).isTrue();
    }

    @Test(timeOut = 30_000)
    void missingBinaryYieldsFailedResultWithoutException() {
        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(
            List.of("/nonexistent/kortty-no-such-binary"), 1_000);

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.ok()).isFalse();
    }

    @Test(timeOut = 30_000)
    void emptyCommandFailsCleanly() {
        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(List.of(), 1_000);

        assertThat(result.ok()).isFalse();
        assertThat(result.timedOut()).isFalse();
    }

    @Test(timeOut = 30_000)
    void runSubmitsToTheNamedDaemonThreadAndCloseRejectsLaterWork() throws Exception {
        requireBinary("/bin/true");
        ExternalCommandRunner runner = new ExternalCommandRunner("kortty-test-runner", 5_000);
        try {
            ExternalCommandRunner.Result result = runner.run(List.of("/bin/true")).get(10, TimeUnit.SECONDS);
            assertThat(result.ok()).isTrue();

            Thread thread = runner.run(List.of("/bin/true"))
                .thenApply(ignored -> Thread.currentThread()).get(10, TimeUnit.SECONDS);
            assertThat(thread.getName()).isEqualTo("kortty-test-runner");
            assertThat(thread.isDaemon()).isTrue();
        } finally {
            runner.close();
        }

        ExternalCommandRunner.Result afterClose = runner.run(List.of("/bin/true")).get(1, TimeUnit.SECONDS);
        assertThat(afterClose.ok()).isFalse();
    }

    @Test
    void hostAwareWrapsOnlyInsideFlatpak() {
        List<String> argv = List.of("notify-send", "hello");

        assertThat(ExternalCommandRunner.hostAware(argv, Map.of())).containsExactly("notify-send", "hello").inOrder();
        assertThat(ExternalCommandRunner.hostAware(argv, Map.of("FLATPAK_ID", "io.github.chardonnay.korTTY")))
            .containsExactly("flatpak-spawn", "--host", "--watch-bus", "notify-send", "hello").inOrder();
        assertThat(ExternalCommandRunner.hostAware(argv, null)).containsExactly("notify-send", "hello").inOrder();
    }

    @Test
    void resultOkRequiresZeroExitWithoutTimeout() {
        assertThat(new ExternalCommandRunner.Result(0, null, false).ok()).isTrue();
        assertThat(new ExternalCommandRunner.Result(0, null, false).output()).isEmpty();
        assertThat(new ExternalCommandRunner.Result(0, "", true).ok()).isFalse();
        assertThat(new ExternalCommandRunner.Result(1, "", false).ok()).isFalse();
    }

    private static void requireBinary(String path) {
        if (!Files.isExecutable(Path.of(path))) {
            throw new SkipException(path + " is not available on this machine");
        }
    }

    private static boolean sleepChildGone(long waitMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (true) {
            boolean present = ProcessHandle.current().children()
                .anyMatch(child -> child.info().commandLine().orElse("").contains(SLEEP_MARKER));
            if (!present) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(50);
        }
    }
}
