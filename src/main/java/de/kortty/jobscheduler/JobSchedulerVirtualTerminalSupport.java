package de.kortty.jobscheduler;

import de.kortty.core.AutomationJournalRecorder;
import de.kortty.core.AutomationScreenshotScheduler;
import de.kortty.core.TerminalScreenRenderer;
import de.kortty.core.headless.HeadlessTerminal;
import de.kortty.ui.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a COMMAND / SNIPPET_SCRIPT job step in the invisible virtual terminal: a pseudo terminal on
 * the server, a {@link HeadlessTerminal} that interprets its output locally, and screenshots of
 * that screen into the run's session journal — periodically, when the screen changes, and at the
 * end. No window opens and no terminal the user works in is touched.
 */
final class JobSchedulerVirtualTerminalSupport {

    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerVirtualTerminalSupport.class);
    private static final long TICK_MILLIS = 500;
    private static final Duration MIN_CHANGE_GAP = Duration.ofSeconds(2);
    private static final DateTimeFormatter CAPTION_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private JobSchedulerVirtualTerminalSupport() {
    }

    static JobExecutionOutcome run(
            JobAction action,
            JobSchedulerRemoteSession remote,
            String shellCommand,
            String label,
            String successSummary,
            String failureSummary,
            String detail,
            AutomationJournalRecorder recorder) throws Exception {
        int columns = action.effectivePtyColumns();
        int rows = action.effectivePtyRows();
        int limitSeconds = action.effectiveRuntimeLimitSeconds();
        recorder.appendCommand(label);
        try (HeadlessTerminal terminal = new HeadlessTerminal(columns, rows)) {
            AutomationScreenshotScheduler screenshots = new AutomationScreenshotScheduler(
                Clock.systemUTC(),
                Duration.ofSeconds(action.effectiveScreenshotIntervalSeconds()),
                action.isScreenshotOnChange(),
                MIN_CHANGE_GAP,
                action.effectiveMaxScreenshots(),
                () -> new AutomationScreenshotScheduler.Screen() {
                    @Override
                    public long changeCount() {
                        return terminal.changeCount();
                    }

                    @Override
                    public String contentKey() {
                        return terminal.screenText();
                    }
                },
                finalShot -> takeScreenshot(terminal, recorder, label, finalShot));
            ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "JobScheduler-VirtualTerminal");
                thread.setDaemon(true);
                return thread;
            });
            JobSchedulerRemoteSession.PtyResult result;
            try {
                ticker.scheduleAtFixedRate(() -> {
                    try {
                        screenshots.tick();
                    } catch (RuntimeException e) {
                        logger.debug("Virtual terminal screenshot tick failed: {}", e.getMessage());
                    }
                }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
                result = remote.executeInPty(
                    shellCommand,
                    null,
                    columns,
                    rows,
                    "xterm-256color",
                    limitSeconds > 0 ? Duration.ofSeconds(limitSeconds) : null,
                    chunk -> {
                        terminal.feed(chunk);
                        recorder.appendTerminalOutput(chunk);
                    },
                    null);
            } finally {
                ticker.shutdownNow();
                ticker.awaitTermination(2, TimeUnit.SECONDS);
            }
            if (!terminal.awaitProcessed(2_000)) {
                logger.debug("Virtual terminal still interpreting output after 2 s; the final screen may lag behind it");
            }
            screenshots.finish();
            String screen = stripTrailingBlankLines(terminal.screenText());
            recorder.hashScreen(screen);
            String fullDetail = detail + "\n" + I18n.get("jobscheduler.dialog.pty.detail",
                columns, rows, screenshots.shotsTaken());
            if (result.limitReached()) {
                fullDetail += "\n" + I18n.get("jobscheduler.dialog.pty.limitReached", limitSeconds);
                return action.isRuntimeLimitSuccess()
                    ? JobExecutionOutcome.success(successSummary, screen, null, fullDetail)
                    : JobExecutionOutcome.cancelled(
                        I18n.get("jobscheduler.dialog.pty.limitReached", limitSeconds), fullDetail);
            }
            return result.exitCode() == 0
                ? JobExecutionOutcome.success(successSummary, screen, null, fullDetail)
                : JobExecutionOutcome.failed(failureSummary, result.exitCode(), screen, null, fullDetail);
        }
    }

    private static void takeScreenshot(HeadlessTerminal terminal, AutomationJournalRecorder recorder, String label,
                                       boolean finalShot) {
        try {
            if (!terminal.awaitProcessed(500)) {
                logger.debug("Virtual terminal still interpreting output; the screenshot may lag behind it");
            }
            byte[] png = TerminalScreenRenderer.renderPng(terminal.snapshot(), true);
            String time = LocalTime.now().format(CAPTION_TIME);
            String caption = shorten(label) + " · " + time
                + (finalShot ? " · " + I18n.get("jobscheduler.dialog.pty.finalScreen") : "");
            recorder.attachScreenshot(png, caption);
        } catch (Exception e) {
            logger.debug("Virtual terminal screenshot failed: {}", e.getMessage());
        }
    }

    private static String shorten(String label) {
        String firstLine = label != null ? label.strip().lines().findFirst().orElse("") : "";
        return firstLine.length() > 80 ? firstLine.substring(0, 79) + "…" : firstLine;
    }

    private static String stripTrailingBlankLines(String screen) {
        if (screen == null) {
            return "";
        }
        String[] lines = screen.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) {
            end--;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < end; i++) {
            text.append(lines[i].stripTrailing()).append('\n');
        }
        return text.toString();
    }
}
