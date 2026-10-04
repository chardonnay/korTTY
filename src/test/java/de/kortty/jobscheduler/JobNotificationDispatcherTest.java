package de.kortty.jobscheduler;

import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.DesktopNotifierBackend;
import de.kortty.policy.EffectivePolicy;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static com.google.common.truth.Truth.assertThat;

class JobNotificationDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");
    private static final String SECRET_OUTPUT = "token=ghp_abcdefghijklmnopqrstuvwxyz0123456789 /etc/shadow";

    /** English test texts; the real bundles are pinned by JobSchedulerI18nCoverageTest. */
    private static final Map<String, String> TEXTS = Map.of(
        "jobscheduler.dialog.notification.title", "Job {0}",
        "jobscheduler.dialog.notification.exitCode", "Exit code {0}",
        "jobscheduler.dialog.notification.status.recovered", "Recovered",
        "jobscheduler.dialog.notification.trigger.manual", "Started manually",
        "jobscheduler.dialog.notification.trigger.scheduled", "Scheduled run",
        "jobscheduler.dialog.status.failed", "Failed",
        "jobscheduler.dialog.status.blocked", "Blocked",
        "jobscheduler.dialog.status.success", "Successful");

    static final BiFunction<String, Object[], String> I18N = (key, args) -> {
        String template = TEXTS.getOrDefault(key, key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    };

    /** DesktopNotifier is final; this backend records what it would show. */
    static final class FakeBackend implements DesktopNotifierBackend {
        final List<String[]> shown = new ArrayList<>();
        boolean supported = true;

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public void notify(String title, String body) {
            shown.add(new String[] {title, body});
        }
    }

    static final class DirectExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private FakeBackend backend;
    private DesktopNotifier notifier;
    private MutableClock clock;
    private Map<String, JobNotificationConfig> configs;

    @BeforeMethod
    void setUp() {
        backend = new FakeBackend();
        notifier = new DesktopNotifier(backend, new DirectExecutor());
        clock = new MutableClock();
        configs = new HashMap<>();
    }

    private JobNotificationDispatcher dispatcher() {
        return new JobNotificationDispatcher(() -> notifier, EffectivePolicy::unrestricted, clock, configs::get, I18N);
    }

    private static JobRunEvent event(String jobId, String name, JobRunStatus status, JobRunStatus previous,
            Integer exitCode, String trigger) {
        return new JobRunEvent(jobId, name, status, previous, exitCode, trigger, SECRET_OUTPUT, T0);
    }

    @Test
    void failedRunWithDefaultsShowsFixedTitleAndBody() {
        configs.put("j1", JobNotificationConfig.defaults());

        dispatcher().onJobRunFinished(event("j1", "Nightly backup", JobRunStatus.FAILED, null, 2, "scheduled"));

        assertThat(backend.shown).hasSize(1);
        assertThat(backend.shown.get(0)[0]).isEqualTo("korTTY · Job Nightly backup");
        assertThat(backend.shown.get(0)[1]).isEqualTo("Failed · Exit code 2 · Scheduled run");
    }

    @Test
    void bodyWithoutExitCodeNamesStatusAndTrigger() {
        configs.put("j1", JobNotificationConfig.defaults());

        dispatcher().onJobRunFinished(event("j1", "Deploy", JobRunStatus.BLOCKED, null, null, "manual"));

        assertThat(backend.shown.get(0)[1]).isEqualTo("Blocked · Started manually");
    }

    @Test
    void triggerFilteringFollowsTheJobConfiguration() {
        configs.put("j1", JobNotificationConfig.defaults());
        JobNotificationDispatcher dispatcher = dispatcher();

        // SUCCESS and RECOVERED are opt-in; CANCELLED never notifies.
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.SUCCESS, null, 0, "manual"));
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.SUCCESS, JobRunStatus.FAILED, 0, "manual"));
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.CANCELLED, null, null, "manual"));
        assertThat(backend.shown).isEmpty();

        JobNotificationConfig recoveredOnly = JobNotificationConfig.defaults();
        recoveredOnly.setTriggers(EnumSet.of(JobNotificationTrigger.RECOVERED));
        configs.put("j2", recoveredOnly);
        dispatcher.onJobRunFinished(event("j2", "B", JobRunStatus.FAILED, null, 1, "manual"));
        dispatcher.onJobRunFinished(event("j2", "B", JobRunStatus.SUCCESS, null, 0, "manual"));
        assertThat(backend.shown).isEmpty();
        dispatcher.onJobRunFinished(event("j2", "B", JobRunStatus.SUCCESS, JobRunStatus.BLOCKED, 0, "scheduled"));
        assertThat(backend.shown).hasSize(1);
        assertThat(backend.shown.get(0)[1]).isEqualTo("Recovered · Exit code 0 · Scheduled run");
    }

    @Test
    void desktopSwitchOffOrUnknownJobShowsNothing() {
        JobNotificationConfig off = JobNotificationConfig.defaults();
        off.setDesktop(false);
        configs.put("j1", off);
        JobNotificationDispatcher dispatcher = dispatcher();

        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, null, 1, "manual"));
        dispatcher.onJobRunFinished(event("gone", "B", JobRunStatus.FAILED, null, 1, "manual"));
        dispatcher.onJobRunFinished(null);

        assertThat(backend.shown).isEmpty();
    }

    @Test
    void missingOrUnsupportedNotifierShowsNothingAndKeepsTheSlot() {
        configs.put("j1", JobNotificationConfig.defaults());
        new JobNotificationDispatcher(() -> null, EffectivePolicy::unrestricted, clock, configs::get, I18N)
            .onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, null, 1, "manual"));

        JobNotificationDispatcher dispatcher = dispatcher();
        backend.supported = false;
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, null, 1, "manual"));
        assertThat(backend.shown).isEmpty();

        backend.supported = true;
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, null, 1, "manual"));
        assertThat(backend.shown).hasSize(1);
    }

    @Test
    void throttlesToOneNotificationPerJobPerMinute() {
        configs.put("j1", JobNotificationConfig.defaults());
        configs.put("j2", JobNotificationConfig.defaults());
        JobNotificationDispatcher dispatcher = dispatcher();

        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, null, 1, "scheduled"));
        clock.now = T0.plusSeconds(30);
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, JobRunStatus.FAILED, 1, "scheduled"));
        // Another job has its own slot.
        dispatcher.onJobRunFinished(event("j2", "B", JobRunStatus.FAILED, null, 1, "scheduled"));
        assertThat(backend.shown).hasSize(2);

        clock.now = T0.plus(JobNotificationDispatcher.THROTTLE).minusMillis(1);
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, JobRunStatus.FAILED, 1, "scheduled"));
        assertThat(backend.shown).hasSize(2);

        clock.now = T0.plus(JobNotificationDispatcher.THROTTLE);
        dispatcher.onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, JobRunStatus.FAILED, 1, "scheduled"));
        assertThat(backend.shown).hasSize(3);
        assertThat(JobNotificationDispatcher.THROTTLE).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void jobNameIsSanitizedAndCapped() {
        String esc = String.valueOf((char) 0x1B);
        String rightToLeftOverride = String.valueOf((char) 0x202E);
        assertThat(JobNotificationDispatcher.title("evil" + esc + "[31m\nname" + rightToLeftOverride + "gnp.exe", I18N))
            .isEqualTo("korTTY · Job evil[31m namegnp.exe");
        String invisible = "  " + (char) 0x200F + (char) 0x07 + " ";
        assertThat(JobNotificationDispatcher.title(invisible, I18N)).isEqualTo("korTTY");
        assertThat(JobNotificationDispatcher.title(null, I18N)).isEqualTo("korTTY");

        String title = JobNotificationDispatcher.title("x".repeat(500), I18N);
        assertThat(title).startsWith(JobNotificationDispatcher.TITLE_PREFIX + "Job ");
        assertThat(title.length()).isAtMost(JobNotificationDispatcher.TITLE_PREFIX.length() + "Job ".length()
            + JobNotificationDispatcher.MAX_JOB_NAME_CHARS);
    }

    @Test
    void bodyNeverCarriesSummaryOrOutput() {
        configs.put("j1", JobNotificationConfig.defaults());

        dispatcher().onJobRunFinished(event("j1", "A", JobRunStatus.FAILED, null, 127, "manual"));

        String body = backend.shown.get(0)[1];
        assertThat(body).doesNotContain("ghp_");
        assertThat(body).doesNotContain("shadow");
        assertThat(body).doesNotContain(SECRET_OUTPUT);
        assertThat(body).isEqualTo("Failed · Exit code 127 · Started manually");
    }
}
