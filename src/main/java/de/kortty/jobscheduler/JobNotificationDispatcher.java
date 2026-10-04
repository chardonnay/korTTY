package de.kortty.jobscheduler;

import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.policy.EffectivePolicy;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns finished JobScheduler runs ({@link JobRunEvent}) into desktop notifications through the
 * application's {@link DesktopNotifier}.
 *
 * <p>{@link JobSchedulerService} already publishes only runs that match one of the job's triggers;
 * the dispatcher checks them again against the job's current configuration and honours its desktop
 * switch. The notification is titled {@code korTTY · Job <name>} with the job's name cleaned of
 * control and bidi characters, so a job name can never make it look like a message from another
 * application. Its body is fixed text only: the status, the exit code and how the run started. It
 * never carries the run's output, detail or summary, because those can name hosts, paths or secrets
 * and a notification can show on the lock screen. A job notifies at most once every
 * {@link #THROTTLE}, so a job that fails every minute does not flood the desktop.
 *
 * <p>{@link #onJobRunFinished} runs on the job's worker thread and only hands the notification to
 * the notifier, which delivers it on its own daemon executor.
 */
public final class JobNotificationDispatcher implements JobRunEventListener {

    private static final Logger logger = LoggerFactory.getLogger(JobNotificationDispatcher.class);

    /** The application name every job notification's title starts with. */
    static final String APP_NAME = "korTTY";

    /** The prefix of every job notification's title; it is not translated. */
    static final String TITLE_PREFIX = APP_NAME + " · ";

    /** At most this many characters of the job's name go into a notification's title. */
    static final int MAX_JOB_NAME_CHARS = 80;

    /** A job shows at most one desktop notification within this interval. */
    public static final Duration THROTTLE = Duration.ofSeconds(60);

    static final String KEY_PREFIX = "jobscheduler.dialog.notification.";

    private static final String SEPARATOR = " · ";

    private final Supplier<DesktopNotifier> notifier;
    /**
     * The enterprise policy. Desktop notifications have no policy switch of their own; webhook
     * deliveries consult it on every send.
     */
    private final Supplier<EffectivePolicy> policy;
    private final Clock clock;
    private final Function<String, JobNotificationConfig> configLookup;
    private final BiFunction<String, Object[], String> i18n;
    private final Map<String, Instant> lastDesktopNotification = new ConcurrentHashMap<>();

    /**
     * @param notifier     the application's desktop notifier; may supply {@code null} while none exists
     * @param policy       the current enterprise policy
     * @param clock        the clock the throttle uses
     * @param configLookup the job's current notification configuration by job id; {@code null} for a
     *                     job that no longer exists
     */
    public JobNotificationDispatcher(Supplier<DesktopNotifier> notifier, Supplier<EffectivePolicy> policy, Clock clock,
            Function<String, JobNotificationConfig> configLookup) {
        this(notifier, policy, clock, configLookup, JobNotificationDispatcher::translate);
    }

    JobNotificationDispatcher(Supplier<DesktopNotifier> notifier, Supplier<EffectivePolicy> policy, Clock clock,
            Function<String, JobNotificationConfig> configLookup, BiFunction<String, Object[], String> i18n) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configLookup = Objects.requireNonNull(configLookup, "configLookup");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    @Override
    public void onJobRunFinished(JobRunEvent event) {
        if (event == null) {
            return;
        }
        JobNotificationConfig config = configLookup.apply(event.jobId());
        if (config == null || !config.matches(event)) {
            return;
        }
        if (config.isDesktop()) {
            notifyDesktop(event);
        }
    }

    /** The current enterprise policy, for the deliveries that have a policy switch. */
    EffectivePolicy policy() {
        return policy.get();
    }

    private void notifyDesktop(JobRunEvent event) {
        DesktopNotifier desktop = notifier.get();
        if (desktop == null || !desktop.isSupported()) {
            return;
        }
        if (!claimThrottleSlot(event.jobId())) {
            logger.debug("Desktop notification for job {} throttled", event.jobId());
            return;
        }
        desktop.notify(title(event.jobName(), i18n), body(event, i18n));
    }

    /** Takes the job's notification slot; false while the job notified less than {@link #THROTTLE} ago. */
    private boolean claimThrottleSlot(String jobId) {
        Instant now = clock.instant();
        boolean[] claimed = {false};
        lastDesktopNotification.compute(jobId, (id, last) -> {
            if (last == null || !now.isBefore(last.plus(THROTTLE))) {
                claimed[0] = true;
                return now;
            }
            return last;
        });
        return claimed[0];
    }

    /**
     * {@code korTTY · Job <name>}: {@link #TITLE_PREFIX} and the translated job label with the job's
     * name without control or bidi characters, at most {@link #MAX_JOB_NAME_CHARS} of them;
     * {@link #APP_NAME} alone when the name has nothing visible.
     */
    static String title(@Nullable String jobName, BiFunction<String, Object[], String> i18n) {
        String name = DisplayTextSanitizer.sanitize(jobName, MAX_JOB_NAME_CHARS);
        if (name.isEmpty()) {
            return APP_NAME;
        }
        return TITLE_PREFIX + DisplayTextSanitizer.stripControlsAndBidi(
            i18n.apply(KEY_PREFIX + "title", new Object[] {name}));
    }

    /**
     * The fixed body: the status (a recovery says so), the exit code when the run had one and how it
     * started. Nothing of the run's output, detail or summary.
     */
    static String body(JobRunEvent event, BiFunction<String, Object[], String> i18n) {
        String status = event.recovered()
            ? i18n.apply(KEY_PREFIX + "status.recovered", new Object[0])
            : i18n.apply("jobscheduler.dialog.status." + event.status().name().toLowerCase(Locale.ROOT), new Object[0]);
        StringBuilder body = new StringBuilder(status);
        if (event.exitCode() != null) {
            body.append(SEPARATOR).append(i18n.apply(KEY_PREFIX + "exitCode",
                new Object[] {String.valueOf(event.exitCode())}));
        }
        return body.append(SEPARATOR).append(triggerText(event.trigger(), i18n)).toString();
    }

    private static String triggerText(@Nullable String trigger, BiFunction<String, Object[], String> i18n) {
        String key = "scheduled".equals(trigger) ? "trigger.scheduled" : "trigger.manual";
        return i18n.apply(KEY_PREFIX + key, new Object[0]);
    }

    private static String translate(String key, Object[] args) {
        return args.length == 0 ? de.kortty.ui.I18n.get(key) : de.kortty.ui.I18n.get(key, args);
    }
}
