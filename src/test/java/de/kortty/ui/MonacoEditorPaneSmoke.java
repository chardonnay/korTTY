package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MonacoEditorPaneSmoke {

    private static final Set<String> EXPECTED_WORKERS = Set.of(
        "editorWorkerService",
        "json",
        "css",
        "html",
        "typescript"
    );
    private static final int RULER_SMOKE_COLUMN = 12;
    private static final double RULER_SMOKE_MARKER_X = 152.0;
    private static final String INITIAL_TEXT = "function smoke() { return 1; }";
    private static final int EDITOR_SMOKE_COLUMN = 19;

    private MonacoEditorPaneSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean finishing = new AtomicBoolean(false);
        Set<String> editorWorkers = ConcurrentHashMap.newKeySet();
        Set<String> diffWorkers = ConcurrentHashMap.newKeySet();

        Platform.startup(() -> {
            LanguageManager.getInstance().initialize(new GlobalSettings());
            Stage stage = new Stage();
            SnippetColumnRuler ruler = new SnippetColumnRuler();
            ruler.setEditorMetrics(40.0, 10.0, 0.0);
            ruler.setCaretColumn(RULER_SMOKE_COLUMN, RULER_SMOKE_MARKER_X);
            MonacoEditorPane editorPane = new MonacoEditorPane();
            editorPane.setLanguage("javascript");
            editorPane.setText(INITIAL_TEXT);
            MonacoDiffPane diffPane = new MonacoDiffPane();
            diffPane.setComparison(
                "function smoke() { return 1; }",
                "function smoke() { return 2; }",
                "javascript",
                "javascript"
            );
            editorPane.setWorkerReadyHandler(label -> {
                editorWorkers.add(label);
                finishWhenReady(editorPane, diffPane, ruler, stage, editorWorkers, diffWorkers,
                    failure, finishing, complete);
            });
            editorPane.setWorkerFailureHandler(detail -> {
                failure.compareAndSet(null, "editor: " + detail);
                complete.countDown();
            });
            diffPane.setWorkerReadyHandler(label -> {
                diffWorkers.add(label);
                finishWhenReady(editorPane, diffPane, ruler, stage, editorWorkers, diffWorkers,
                    failure, finishing, complete);
            });
            diffPane.setWorkerFailureHandler(detail -> {
                failure.compareAndSet(null, "diff: " + detail);
                complete.countDown();
            });
            editorPane.readyProperty().addListener((obs, oldValue, ready) -> {
                if (Boolean.TRUE.equals(ready)) {
                    if (!INITIAL_TEXT.equals(editorPane.getText())) {
                        failure.compareAndSet(null, "Initial Monaco text changed during boot");
                        complete.countDown();
                        return;
                    }
                    editorPane.moveTo(EDITOR_SMOKE_COLUMN - 1);
                    finishWhenReady(editorPane, diffPane, ruler, stage, editorWorkers, diffWorkers,
                        failure, finishing, complete);
                }
            });
            diffPane.readyProperty().addListener((obs, oldValue, ready) -> {
                if (Boolean.TRUE.equals(ready)) {
                    finishWhenReady(editorPane, diffPane, ruler, stage, editorWorkers, diffWorkers,
                        failure, finishing, complete);
                }
            });
            VBox content = new VBox(ruler, editorPane, diffPane);
            VBox.setVgrow(editorPane, Priority.ALWAYS);
            VBox.setVgrow(diffPane, Priority.ALWAYS);
            stage.setScene(new Scene(content, 900, 900));
            stage.setTitle("Monaco WebView Smoke");
            stage.show();
        });

        boolean completed = complete.await(45, TimeUnit.SECONDS);
        CountDownLatch stopped = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.exit();
            stopped.countDown();
        });
        stopped.await(5, TimeUnit.SECONDS);

        if (!completed) {
            throw new IllegalStateException("Timed out waiting for Monaco workers. Editor: "
                + new TreeSet<>(editorWorkers) + ", diff: " + new TreeSet<>(diffWorkers));
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Monaco worker failed in JavaFX WebView: " + failure.get());
        }
        if (!editorWorkers.containsAll(EXPECTED_WORKERS)) {
            Set<String> missing = new TreeSet<>(EXPECTED_WORKERS);
            missing.removeAll(editorWorkers);
            throw new IllegalStateException("Missing Monaco editor workers: " + missing
                + ". Ready: " + new TreeSet<>(editorWorkers));
        }
        if (!diffWorkers.containsAll(EXPECTED_WORKERS)) {
            Set<String> missing = new TreeSet<>(EXPECTED_WORKERS);
            missing.removeAll(diffWorkers);
            throw new IllegalStateException("Missing Monaco diff workers: " + missing
                + ". Ready: " + new TreeSet<>(diffWorkers));
        }

        System.out.println("Monaco WebView smoke passed. Editor workers: " + new TreeSet<>(editorWorkers)
            + "; diff workers: " + new TreeSet<>(diffWorkers));
    }

    private static void finishWhenReady(
        MonacoEditorPane editorPane,
        MonacoDiffPane diffPane,
        SnippetColumnRuler ruler,
        Stage stage,
        Set<String> editorWorkers,
        Set<String> diffWorkers,
        AtomicReference<String> failure,
        AtomicBoolean finishing,
        CountDownLatch complete
    ) {
        if (!editorPane.isReady()
            || !diffPane.isReady()
            || failure.get() != null
            || !editorWorkers.containsAll(EXPECTED_WORKERS)
            || !diffWorkers.containsAll(EXPECTED_WORKERS)
            || !finishing.compareAndSet(false, true)) {
            return;
        }
        PauseTransition delay = new PauseTransition(Duration.seconds(1));
        delay.setOnFinished(event -> {
            String visualFailure = verifyRenderedEditor(editorPane);
            if (visualFailure != null) {
                failure.compareAndSet(null, visualFailure);
            }
            String diffVisualFailure = verifyRenderedDiff(diffPane);
            if (diffVisualFailure != null) {
                failure.compareAndSet(null, diffVisualFailure);
            }
            String caretFailure = verifyEditorCaret(editorPane);
            if (caretFailure != null) {
                failure.compareAndSet(null, caretFailure);
            }
            String rulerFailure = verifyRulerCaretMarker(ruler, RULER_SMOKE_MARKER_X);
            if (rulerFailure != null) {
                failure.compareAndSet(null, rulerFailure);
            }
            editorPane.dispose();
            diffPane.dispose();
            stage.close();
            complete.countDown();
        });
        delay.play();
    }

    private static String verifyRenderedDiff(MonacoDiffPane diffPane) {
        WritableImage image = diffPane.snapshot(null, null);
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return "Could not snapshot Monaco diff editor";
        }

        int sampled = 0;
        int darkPixels = 0;
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        for (int y = 0; y < height; y += 10) {
            for (int x = 0; x < width; x += 10) {
                Color color = reader.getColor(x, y);
                sampled++;
                double brightness = (color.getRed() + color.getGreen() + color.getBlue()) / 3.0;
                if (brightness < 0.25) {
                    darkPixels++;
                }
            }
        }
        if (sampled == 0) {
            return "Monaco diff editor snapshot was empty";
        }
        if (darkPixels < sampled / 10) {
            return "Monaco diff editor did not render the expected dark editor surface";
        }
        return null;
    }

    private static String verifyRenderedEditor(MonacoEditorPane editorPane) {
        WritableImage image = editorPane.snapshot(null, null);
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return "Could not snapshot Monaco editor";
        }

        int sampled = 0;
        int darkPixels = 0;
        int whitePixels = 0;
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        for (int y = 0; y < height; y += 10) {
            for (int x = 0; x < width; x += 10) {
                Color color = reader.getColor(x, y);
                sampled++;
                double brightness = (color.getRed() + color.getGreen() + color.getBlue()) / 3.0;
                if (brightness < 0.25) {
                    darkPixels++;
                }
                if (brightness > 0.95) {
                    whitePixels++;
                }
            }
        }

        if (sampled == 0) {
            return "Monaco editor snapshot was empty";
        }
        if (darkPixels < sampled / 10) {
            return "Monaco editor did not render the expected dark editor surface";
        }
        if (whitePixels > sampled / 2) {
            return "Monaco editor rendered as a mostly white surface";
        }
        return null;
    }

    private static String verifyEditorCaret(MonacoEditorPane editorPane) {
        if (editorPane.getCaretColumn() != EDITOR_SMOKE_COLUMN) {
            return "Monaco caret column was " + editorPane.getCaretColumn() + ", expected " + EDITOR_SMOKE_COLUMN;
        }
        if (!Double.isFinite(editorPane.getCaretVisualX())) {
            return "Monaco caret visual X was not reported";
        }
        return null;
    }

    private static String verifyRulerCaretMarker(SnippetColumnRuler ruler, double expectedMarkerX) {
        WritableImage image = ruler.snapshot(null, null);
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return "Could not snapshot snippet ruler";
        }

        int markerPixels = 0;
        int markerPixelsAtExpectedX = 0;
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color color = reader.getColor(x, y);
                if (color.getBlue() > 0.75 && color.getGreen() > 0.55 && color.getRed() < 0.40) {
                    markerPixels++;
                    if (Math.abs(x - expectedMarkerX) <= 3.0) {
                        markerPixelsAtExpectedX++;
                    }
                }
            }
        }

        if (markerPixels < height) {
            return "Snippet ruler did not render the caret column marker";
        }
        if (markerPixelsAtExpectedX < height) {
            return "Snippet ruler did not render the caret marker at the supplied visual X";
        }
        return null;
    }
}
