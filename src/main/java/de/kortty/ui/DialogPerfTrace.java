package de.kortty.ui;

import de.kortty.perf.PerfTrace;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;

/**
 * Times the life cycle of a {@link Dialog} when {@link PerfTrace#ENABLED perf tracing} is on:
 *
 * <pre>perf SettingsDialog: construct=812.4ms show=44.9ms firstPulse=131.0ms</pre>
 * <pre>perf SettingsDialog: close=57.2ms</pre>
 *
 * <ul>
 *   <li>{@code construct} — from {@link #attach} (the {@code ThemeAwareDialog} base constructor)
 *       to {@code DIALOG_SHOWING}, i.e. the subclass constructor plus whatever the caller does
 *       before {@code show()}; reported for the first show only.</li>
 *   <li>{@code show} — {@code DIALOG_SHOWING} to {@code DIALOG_SHOWN}: the stage mapping plus every
 *       {@code Window.getWindows()} listener (global design styler, close shortcut, geometry).</li>
 *   <li>{@code firstPulse} — {@code DIALOG_SHOWN} to the first post-layout pulse of the dialog's
 *       scene: skin creation, CSS and layout of the whole tree, the first rendered frame.</li>
 *   <li>{@code close} — {@code DIALOG_HIDING} to a {@code runLater} queued at {@code DIALOG_HIDDEN},
 *       so it includes every synchronous hidden handler (notably the geometry save).</li>
 * </ul>
 *
 * <p>Nothing is installed when tracing is off.</p>
 */
final class DialogPerfTrace {

    private DialogPerfTrace() {
    }

    static void attach(Dialog<?> dialog) {
        if (!PerfTrace.ENABLED || dialog == null) {
            return;
        }
        final long created = System.nanoTime();
        final long[] showing = {0L};
        final long[] shown = {0L};
        final boolean[] firstShow = {true};
        final long[] hiding = {0L};
        final String name = label(dialog);

        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event -> showing[0] = System.nanoTime());
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            shown[0] = System.nanoTime();
            Scene scene = dialog.getDialogPane() != null ? dialog.getDialogPane().getScene() : null;
            String construct = firstShow[0] ? " construct=" + PerfTrace.millis(showing[0] - created) : "";
            firstShow[0] = false;
            if (scene == null) {
                PerfTrace.log(name + ":" + construct + " show=" + PerfTrace.millis(shown[0] - showing[0]));
                return;
            }
            Runnable[] once = new Runnable[1];
            once[0] = () -> {
                scene.removePostLayoutPulseListener(once[0]);
                long pulse = System.nanoTime();
                PerfTrace.log(name + ":" + construct
                    + " show=" + PerfTrace.millis(shown[0] - showing[0])
                    + " firstPulse=" + PerfTrace.millis(pulse - shown[0]));
            };
            scene.addPostLayoutPulseListener(once[0]);
        });
        dialog.addEventHandler(DialogEvent.DIALOG_HIDING, event -> hiding[0] = System.nanoTime());
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> Platform.runLater(() ->
            PerfTrace.log(name + ": close=" + PerfTrace.millis(System.nanoTime() - hiding[0]))));
    }

    private static String label(Dialog<?> dialog) {
        String simple = dialog.getClass().getSimpleName();
        if (simple.isEmpty() || dialog.getClass().getName().startsWith("javafx.")) {
            String title = dialog.getTitle();
            return dialog.getClass().getSimpleName() + (title != null && !title.isBlank() ? "[" + title + "]" : "");
        }
        return simple;
    }
}
