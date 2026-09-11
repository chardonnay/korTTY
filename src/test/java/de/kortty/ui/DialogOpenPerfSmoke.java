package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SSHKey;
import de.kortty.model.ServerConnection;
import de.kortty.model.Snippet;
import de.kortty.security.MasterPasswordManager;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Headed benchmark for the "dialogs open slowly" investigation. Opens the heavy korTTY dialogs
 * N times each against an isolated, empty home (like {@link MainWindowScreenshotStage}) and prints,
 * per iteration and as a warm median:
 *
 * <ul>
 *   <li>{@code construct} — the dialog constructor,</li>
 *   <li>{@code show} — {@code DIALOG_SHOWING} → {@code DIALOG_SHOWN} (stage mapping + every
 *       global window listener),</li>
 *   <li>{@code firstPulse} — {@code DIALOG_SHOWN} → first post-layout pulse (skins, CSS, layout,
 *       first frame),</li>
 *   <li>{@code maxStall} — the longest gap between two animation frames while the dialog was open,
 *       i.e. the longest freeze of the FX thread the user would perceive (this is where a WebView
 *       boot lands, because JavaFX WebKit runs its JavaScript on the FX thread),</li>
 *   <li>{@code close} — {@code DIALOG_HIDING} → a {@code runLater} after {@code DIALOG_HIDDEN}
 *       (includes the synchronous geometry save).</li>
 * </ul>
 *
 * <p>System properties: {@code kortty.perf.iterations} (default 5), {@code kortty.perf.settleMs}
 * (time a dialog stays open before closing, default 3000 — long enough for a Monaco boot),
 * {@code kortty.perf.dialogs} (comma list of {@code alert,settings,connection,ai,snippet}),
 * {@code kortty.perf.design} (an {@link AppDesign} name), {@code kortty.perf.fontScale} (percent),
 * {@code kortty.perf.sample} (sample the FX thread's stack every 2 ms from construction to the first
 * pulse and print the hottest frames for the first {@code kortty.perf.sampleRuns} runs, default 1 —
 * JFR cannot do this on macOS, where the FX thread is the native AppKit main thread).
 * {@code kortty.ui.perf} is forced on, so the per-dialog {@code perf …} log lines appear too.</p>
 */
public final class DialogOpenPerfSmoke {

    private record Sample(double construct, double show, double firstPulse, double maxStall, double close) {
    }

    private static final Map<String, List<Sample>> RESULTS = new LinkedHashMap<>();

    private DialogOpenPerfSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-dialog-perf");
        System.setProperty("user.home", home.toString());
        System.setProperty("kortty.ui.perf", "true");
        Locale.setDefault(Locale.ENGLISH);

        int iterations = Integer.getInteger("kortty.perf.iterations", 5);
        int settleMs = Integer.getInteger("kortty.perf.settleMs", 3000);
        List<String> dialogs = Arrays.asList(
            System.getProperty("kortty.perf.dialogs", "alert,settings,connection,ai,snippet").split("\\s*,\\s*"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                run(iterations, settleMs, dialogs, done);
            } catch (Throwable t) {
                failure.set(t);
                done.countDown();
            }
        });
        boolean finished = done.await(20, TimeUnit.MINUTES);
        if (failure.get() != null) {
            failure.get().printStackTrace();
            System.exit(1);
        }
        if (!finished) {
            System.err.println("DIALOG PERF TIMEOUT");
            System.exit(2);
        }
        printSummary(iterations);
        System.exit(0);
    }

    private static void run(int iterations, int settleMs, List<String> dialogs, CountDownLatch done) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        String design = System.getProperty("kortty.perf.design");
        if (design != null && !design.isBlank()) {
            settings.setAppDesign(AppDesign.valueOf(design.trim()));
        }
        String fontScale = System.getProperty("kortty.perf.fontScale");
        if (fontScale != null && !fontScale.isBlank()) {
            settings.setUiFontScalePercent(Integer.parseInt(fontScale.trim()));
        }
        LanguageManager.getInstance().initialize(settings);
        AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());

        Stage stage = new Stage();
        MainWindow window = new MainWindow(stage);
        window.show();
        stage.setWidth(1200);
        stage.setHeight(800);

        // Crypto fixture: an SSH key with a stored passphrase makes ConnectionEditDialog run the
        // PBKDF2 decrypt in its constructor, exactly as for a real key-backed connection.
        MasterPasswordManager passwords = app.getMasterPasswordManager();
        passwords.setupPassword("perf-smoke".toCharArray());
        SSHKey key = new SSHKey("perf-key", home().resolve("id_perf").toString());
        app.getSSHKeyManager().addKey(key);
        app.getSSHKeyManager().setPassphrase(key, "secret", passwords.getMasterPassword());
        ServerConnection connection = new ServerConnection();
        connection.setName("perf");
        connection.setHost("localhost");
        connection.setUsername("perf");
        connection.setSshKeyId(key.getId());
        Snippet snippet = new Snippet("perf", "echo hello\n", "bash");

        Map<String, Supplier<Dialog<?>>> factories = new LinkedHashMap<>();
        factories.put("alert", () -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "perf");
            alert.initOwner(stage);
            DialogThemeHelper.applyTheme(alert);
            return alert;
        });
        factories.put("settings", () -> new SettingsDialog(stage, app, app.getConfigManager(), settings,
            app.getCredentialManager(), app.getGpgKeyManager()));
        factories.put("connection", () -> new ConnectionEditDialog(stage, connection,
            app.getCredentialManager(), app.getSSHKeyManager(), passwords.getMasterPassword()));
        factories.put("ai", () -> new AiManagerDialog(window));
        factories.put("snippet", () -> {
            SnippetEditDialog dialog = new SnippetEditDialog(snippet, List.of("General"));
            dialog.initOwner(stage);
            return dialog;
        });

        List<Runnable> steps = new ArrayList<>();
        for (String name : dialogs) {
            Supplier<Dialog<?>> factory = factories.get(name);
            if (factory == null) {
                throw new IllegalArgumentException("Unknown dialog: " + name);
            }
            for (int i = 0; i < iterations; i++) {
                steps.add(() -> measure(name, factory, settleMs, DialogOpenPerfSmoke::next));
            }
        }
        steps.add(done::countDown);
        QUEUE.addAll(steps);
        // Let the main window settle (its own first pulses) before the first measurement.
        PauseTransition settle = new PauseTransition(Duration.millis(1500));
        settle.setOnFinished(e -> next());
        settle.play();
    }

    private static final List<Runnable> QUEUE = new ArrayList<>();

    private static void next() {
        if (QUEUE.isEmpty()) {
            return;
        }
        QUEUE.remove(0).run();
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static void measure(String name, Supplier<Dialog<?>> factory, int settleMs, Runnable next) {
        int run = RESULTS.getOrDefault(name, List.of()).size() + 1;
        FxStackSampler sampler = Boolean.getBoolean("kortty.perf.sample")
            && run <= Integer.getInteger("kortty.perf.sampleRuns", 1)
            ? new FxStackSampler(name + " #" + run) : null;
        if (sampler != null) {
            sampler.start();
        }
        long t0 = System.nanoTime();
        Dialog<?> dialog = factory.get();
        long constructed = System.nanoTime();
        long[] showing = {0L};
        long[] shown = {0L};
        long[] firstPulse = {0L};
        long[] hiding = {0L};
        StallMonitor stalls = new StallMonitor();

        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, e -> showing[0] = System.nanoTime());
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> {
            shown[0] = System.nanoTime();
            Scene scene = dialog.getDialogPane().getScene();
            Runnable[] once = new Runnable[1];
            once[0] = () -> {
                scene.removePostLayoutPulseListener(once[0]);
                firstPulse[0] = System.nanoTime();
                if (sampler != null) {
                    sampler.stopAndPrint();
                }
                PauseTransition wait = new PauseTransition(Duration.millis(settleMs));
                wait.setOnFinished(ev -> dialog.close());
                wait.play();
            };
            scene.addPostLayoutPulseListener(once[0]);
        });
        dialog.addEventHandler(DialogEvent.DIALOG_HIDING, e -> hiding[0] = System.nanoTime());
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> Platform.runLater(() -> {
            long closed = System.nanoTime();
            stalls.stop();
            Sample sample = new Sample(
                ms(constructed - t0), ms(shown[0] - showing[0]), ms(firstPulse[0] - shown[0]),
                ms(stalls.maxGapNanos), ms(closed - hiding[0]));
            RESULTS.computeIfAbsent(name, k -> new ArrayList<>()).add(sample);
            System.out.printf(Locale.ROOT, "PERF %-10s #%d construct=%.0f show=%.0f firstPulse=%.0f maxStall=%.0f close=%.0f%n",
                name, RESULTS.get(name).size(), sample.construct, sample.show, sample.firstPulse,
                sample.maxStall, sample.close);
            // Give the closed dialog's deferred work (geometry save log, WebKit teardown) a moment.
            PauseTransition gap = new PauseTransition(Duration.millis(400));
            gap.setOnFinished(ev -> next.run());
            gap.play();
        }));
        stalls.start();
        dialog.show();
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * Polls the FX thread's stack from a daemon thread and aggregates the hottest frames: the top
     * frame (self time), the nearest {@code de.kortty} frame (which korTTY code is responsible) and
     * frames anywhere on the stack (inclusive). Coarse (safepoint-biased, 2 ms), but enough to
     * attribute a cold dialog open to CSS, layout, WebKit, class loading or a korTTY constructor.
     */
    private static final class FxStackSampler {
        private final String label;
        private final Thread fxThread = Thread.currentThread();
        private final Map<String, Integer> self = new LinkedHashMap<>();
        private final Map<String, Integer> kortty = new LinkedHashMap<>();
        private final Map<String, Integer> inclusive = new LinkedHashMap<>();
        private volatile boolean running = true;
        private int samples;
        private Thread worker;

        FxStackSampler(String label) {
            this.label = label;
        }

        void start() {
            worker = new Thread(() -> {
                while (running) {
                    StackTraceElement[] stack = fxThread.getStackTrace();
                    synchronized (this) {
                        record(stack);
                    }
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "fx-stack-sampler");
            worker.setDaemon(true);
            worker.start();
        }

        private void record(StackTraceElement[] stack) {
            if (stack.length == 0) {
                return;
            }
            samples++;
            self.merge(frame(stack[0]), 1, Integer::sum);
            java.util.Set<String> seen = new java.util.HashSet<>();
            boolean korttyFound = false;
            for (StackTraceElement element : stack) {
                String frame = frame(element);
                if (!korttyFound && element.getClassName().startsWith("de.kortty.")
                    && !element.getClassName().contains("DialogOpenPerfSmoke")) {
                    kortty.merge(frame, 1, Integer::sum);
                    korttyFound = true;
                }
                if (seen.add(frame)) {
                    inclusive.merge(frame, 1, Integer::sum);
                }
            }
            if (!korttyFound) {
                kortty.merge("(no de.kortty frame: JavaFX/JDK internal)", 1, Integer::sum);
            }
        }

        private static String frame(StackTraceElement element) {
            return element.getClassName() + "." + element.getMethodName();
        }

        void stopAndPrint() {
            running = false;
            if (worker != null) {
                worker.interrupt();
            }
            synchronized (this) {
                System.out.println("SAMPLES " + label + ": " + samples + " stack samples (~2 ms each)");
                print("  self (top frame)", self, 12);
                print("  nearest de.kortty frame", kortty, 15);
                print("  inclusive", inclusive, 40);
            }
        }

        private void print(String title, Map<String, Integer> counts, int limit) {
            System.out.println(title + ":");
            counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(e -> System.out.printf(Locale.ROOT, "  %5d %5.1f%%  %s%n",
                    e.getValue(), 100.0 * e.getValue() / Math.max(1, samples), e.getKey()));
        }
    }

    /** Longest gap between two consecutive animation frames = longest FX-thread freeze. */
    private static final class StallMonitor extends AnimationTimer {
        private long last;
        private long maxGapNanos;

        @Override
        public void handle(long now) {
            if (last != 0L) {
                maxGapNanos = Math.max(maxGapNanos, now - last);
            }
            last = now;
        }
    }

    private static void printSummary(int iterations) {
        System.out.println();
        System.out.printf(Locale.ROOT, "%-10s %-8s %10s %8s %10s %9s %8s%n",
            "dialog", "run", "construct", "show", "firstPulse", "maxStall", "close");
        for (Map.Entry<String, List<Sample>> entry : RESULTS.entrySet()) {
            List<Sample> samples = entry.getValue();
            if (samples.isEmpty()) {
                continue;
            }
            print(entry.getKey(), "cold", samples.get(0));
            if (samples.size() > 1) {
                List<Sample> warm = samples.subList(1, samples.size());
                print(entry.getKey(), "warm-med", new Sample(
                    median(warm, Sample::construct), median(warm, Sample::show), median(warm, Sample::firstPulse),
                    median(warm, Sample::maxStall), median(warm, Sample::close)));
            }
        }
        System.out.println("(iterations=" + iterations + ", times in ms)");
    }

    private static void print(String name, String run, Sample s) {
        System.out.printf(Locale.ROOT, "%-10s %-8s %10.0f %8.0f %10.0f %9.0f %8.0f%n",
            name, run, s.construct, s.show, s.firstPulse, s.maxStall, s.close);
    }

    private static double median(List<Sample> samples, java.util.function.ToDoubleFunction<Sample> getter) {
        double[] values = samples.stream().mapToDouble(getter).sorted().toArray();
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }
}
