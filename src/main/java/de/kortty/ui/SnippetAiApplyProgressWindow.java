package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * Narrow companion window for the staged Full-code-analysis apply workflow. A
 * {@link WindowDockGroup} keeps it beside the analysis window; the content is a
 * {@link SnippetAiApplyProgressPane}, which does the reporting — this class only adds the window,
 * the "arrange windows" action and the remembered docked width.
 *
 * <p>When the run ends the window does not close itself. It turns into a summary of what was done,
 * how long it took and what it cost, which is only useful if it is still on screen while the
 * reviewer reads the diff next to it.</p>
 */
final class SnippetAiApplyProgressWindow {

    private static final double DEFAULT_WIDTH = 360;
    private static final double MIN_HEIGHT = 420;
    /** Below this a stored width is junk rather than a deliberately tiny window. */
    private static final double MIN_USABLE_WIDTH = 200;

    private final Stage stage = new Stage();
    private final SnippetAiApplyProgressPane pane;
    private final Button tileButton = new Button(I18n.get("snippets.ai.analysis.progress.dock.tile"));
    private boolean disposed;

    SnippetAiApplyProgressWindow(
            Window anchor,
            List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan,
            String profileName) {
        pane = new SnippetAiApplyProgressPane(plan, profileName);

        Scene scene = new Scene(pane, DEFAULT_WIDTH, MIN_HEIGHT);
        applyTheme(scene);
        stage.setScene(scene);
        stage.setTitle(I18n.get("snippets.ai.analysis.progress.title"));
        stage.setMinWidth(320);
        stage.setMinHeight(MIN_HEIGHT);
        stage.initModality(Modality.NONE);
        if (anchor != null) {
            stage.initOwner(anchor);
        }
        stage.setOnHidden(event -> dispose());
    }

    void show() {
        if (disposed || stage.isShowing()) {
            return;
        }
        pane.start();
        stage.show();
    }

    /** The window itself, so the host can hand it to a {@link WindowDockGroup}. */
    Stage stage() {
        return stage;
    }

    /** The progress view shown in this window. */
    SnippetAiApplyProgressPane pane() {
        return pane;
    }

    void accept(SnippetAiWorkflowSupport.ImprovementApplyProgress progress) {
        pane.accept(progress);
    }

    void markSucceeded() {
        pane.markSucceeded();
    }

    void markFailed() {
        pane.markFailed();
    }

    void markCancelled() {
        pane.markCancelled();
    }

    void close() {
        runOnFx(() -> {
            if (stage.isShowing()) {
                stage.close();
            } else {
                dispose();
            }
        });
    }

    /**
     * Re-opens the change preview after it was closed. The button only appears when the host offers
     * one — closing the preview by accident should not mean re-running the whole analysis.
     */
    void setReopenPreviewHandler(Runnable handler) {
        pane.setOnReviewChanges(handler);
    }

    /** Enables the "arrange windows" action, which re-tiles the docked trio. */
    void setTileHandler(Runnable handler) {
        runOnFx(() -> {
            tileButton.setOnAction(handler == null ? null : event -> handler.run());
            pane.setLeadingAction(handler == null ? null : tileButton);
        });
    }

    private void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        pane.dispose();
        persistDockedWidth();
    }

    /**
     * Remembers how wide the user made this window, so the next apply run opens it at that width
     * instead of the designed default. Position and height belong to the dock, not to the user.
     */
    private void persistDockedWidth() {
        double width = stage.getWidth();
        if (Double.isNaN(width) || width < MIN_USABLE_WIDTH) {
            return;
        }
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiApplyProgressDockedWidth(width);
                manager.save();
            }
        } catch (Exception ignored) {
            // No application instance (isolated JavaFX tests) or an unwritable profile: the width is
            // a convenience, never worth failing a window close over.
        }
    }

    private static void applyTheme(Scene scene) {
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
        try {
            String dynamic = ThemeCssSupport.getDynamicStylesheetUrl(
                ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance()));
            if (dynamic != null) {
                scene.getStylesheets().add(dynamic);
            }
        } catch (RuntimeException ignored) {
            // The base stylesheet remains available in isolated JavaFX tests without an application instance.
        }
        AppDesignStyleSupport.applyToScene(scene);
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
