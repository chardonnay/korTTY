package de.kortty.ui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the "stored script size per analysis" control of the Snippet Editor settings with real
 * JavaFX controls for the three policy situations (none, a cap, a ban) and checks what the user is
 * offered, what is locked and what would be stored.
 */
public final class SnippetAnalysisContentLimitControlSmoke {

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;

    private SnippetAnalysisContentLimitControlSmoke() {
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                run();
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out");
        }
        Platform.exit();
        if (failure.get() != null) {
            failure.get().printStackTrace();
            System.exit(1);
        }
        System.out.println("SnippetAnalysisContentLimitControlSmoke OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void run() {
        // No policy: every preset, default shown as 1 MB, nothing locked, no note.
        SnippetAnalysisContentLimitControl free = new SnippetAnalysisContentLimitControl(1 * MB, null);
        check(free.combo().getItems().equals(List.of(0L, 256 * KB, 512 * KB, 1 * MB, 2 * MB, 5 * MB)),
            "free items: " + free.combo().getItems());
        check(free.combo().getValue() == 1 * MB && !free.combo().isDisable(), "free value/lock");
        check(!free.policyNote().isVisible() && !free.loweredNote().isVisible(), "no notes without a policy");
        check(free.valueToStore() == null, "nothing to store before the user changes it");
        free.combo().setValue(5 * MB);
        check(free.valueToStore() != null && free.valueToStore() == 5 * MB, "raised value is stored");
        check(!free.loweredNote().isVisible(), "raising shows no 'lowered' note");
        free.combo().setValue(256 * KB);
        check(free.loweredNote().isVisible() && free.loweredNote().isManaged(), "lowering explains that nothing is deleted");

        // A 1 MB cap: the user's stored 5 MB shows as 1 MB, larger choices are gone, the note says why.
        SnippetAnalysisContentLimitControl capped = new SnippetAnalysisContentLimitControl(5 * MB, 1 * MB);
        check(capped.combo().getItems().equals(List.of(0L, 256 * KB, 512 * KB, 1 * MB)),
            "capped items: " + capped.combo().getItems());
        check(capped.combo().getValue() == 1 * MB && !capped.combo().isDisable(), "capped value/lock");
        check(capped.policyNote().isVisible() && !capped.policyNote().getText().isBlank(), "cap note");
        check(capped.valueToStore() == null, "an untouched capped control keeps the user's 5 MB preference");
        capped.combo().setValue(512 * KB);
        check(capped.valueToStore() != null && capped.valueToStore() == 512 * KB, "choosing below the cap stores it");

        // A ban (0): only Off, locked with the managed hint, nothing is ever stored.
        SnippetAnalysisContentLimitControl banned = new SnippetAnalysisContentLimitControl(2 * MB, 0L);
        check(banned.combo().getItems().equals(List.of(0L)), "banned items: " + banned.combo().getItems());
        check(banned.combo().getValue() == 0L && banned.combo().isDisable(), "banned value/lock");
        check(banned.combo().getTooltip() != null && !banned.combo().getTooltip().getText().isBlank(), "managed tooltip");
        check(banned.policyNote().isVisible(), "ban note");
        check(banned.valueToStore() == null, "a locked control stores nothing");

        // A custom value from the settings file stays selectable.
        SnippetAnalysisContentLimitControl custom = new SnippetAnalysisContentLimitControl(3 * MB, null);
        check(custom.combo().getItems().contains(3 * MB) && custom.combo().getValue() == 3 * MB, "custom value kept");

        // It lays out into the settings grid (caption, combo, hint, notes) and reports the next row.
        GridPane grid = new GridPane();
        int next = free.addTo(grid, new Label("Stored script size per analysis:"), 4);
        check(next == 8 && grid.getChildren().size() == 5, "grid rows: " + next + " / " + grid.getChildren().size());
    }
}
