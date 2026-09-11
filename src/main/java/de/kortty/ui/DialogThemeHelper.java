package de.kortty.ui;

import de.kortty.KorTTYApplication;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

/**
 * Applies the active KorTTY theme to JavaFX dialogs.
 */
public final class DialogThemeHelper {

    private DialogThemeHelper() {
    }

    public static void applyTheme(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        DialogPerfTrace.attach(dialog);
        applyTheme(dialog.getDialogPane());
        WindowCloseShortcutSupport.installForDialog(dialog);
        DialogGeometrySupport.installAutomatic(dialog);
    }

    public static void applyTheme(DialogPane dialogPane) {
        if (dialogPane == null) {
            return;
        }

        AppDesignStyleSupport.registerApplicationBaseStyles(dialogPane);

        // A custom app design fully owns the chrome, so the terminal-theme dynamic stylesheet (which
        // would override the design's menu/button/label colours) is suppressed at the source:
        // getDynamicStylesheetUrl() returns null while a custom design is active, so the dialog never
        // receives the overlay. Under the default design it is added as usual.
        ThemeCssSupport.ThemeColors themeColors = ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        String dynamicStylesheet = ThemeCssSupport.getDynamicStylesheetUrl(themeColors);
        if (dynamicStylesheet != null && !dialogPane.getStylesheets().contains(dynamicStylesheet)) {
            dialogPane.getStylesheets().add(dynamicStylesheet);
        }

        AppDesignStyleSupport.applyToDialogPane(dialogPane);
        // A Dialog owns its Scene from construction. Styling it here (stylesheets for popups,
        // font scale) stamps it as done, so the global window listener has nothing left to do at
        // show() — previously it re-applied everything to scene and pane and forced two extra
        // full-tree applyCss() passes on every dialog open under a custom design or font scale.
        // Note that a later initOwner() binds this scene's stylesheet list to the owner scene's
        // (HeavyweightDialog.updateStageBindings), so for owned dialogs the list ends up mirroring
        // the main window's, which is styled the same way; the pane keeps its own copies either way.
        Scene scene = dialogPane.getScene();
        if (scene != null && scene.getRoot() == dialogPane) {
            AppDesignStyleSupport.applyToScene(scene);
        }
    }
}
