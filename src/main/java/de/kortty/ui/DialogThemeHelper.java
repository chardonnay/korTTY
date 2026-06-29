package de.kortty.ui;

import de.kortty.KorTTYApplication;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the active KorTTY theme to JavaFX dialogs.
 */
public final class DialogThemeHelper {

    private static final Logger logger = LoggerFactory.getLogger(DialogThemeHelper.class);

    private DialogThemeHelper() {
    }

    public static void applyTheme(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        applyTheme(dialog.getDialogPane());
        WindowCloseShortcutSupport.installForDialog(dialog);
    }

    public static void applyTheme(DialogPane dialogPane) {
        if (dialogPane == null) {
            return;
        }

        var baseCssResource = DialogThemeHelper.class.getResource("/styles/terminal.css");
        if (baseCssResource != null) {
            String baseCss = baseCssResource.toExternalForm();
            if (!dialogPane.getStylesheets().contains(baseCss)) {
                dialogPane.getStylesheets().add(baseCss);
            }
        } else {
            logger.debug("Could not resolve terminal.css for dialog theming");
        }

        // A custom app design fully owns the chrome. Layering the terminal-theme-coloured dynamic
        // stylesheet on top would override the design's menu/button/label colours (e.g. dark text on
        // the blue DOS chrome), so skip (and strip) it whenever a custom design is active.
        ThemeCssSupport.ThemeColors themeColors = ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        String dynamicStylesheet = ThemeCssSupport.getDynamicStylesheetUrl(themeColors);
        if (dynamicStylesheet != null) {
            if (AppDesignStyleSupport.isCustomAppDesignActive()) {
                dialogPane.getStylesheets().remove(dynamicStylesheet);
            } else if (!dialogPane.getStylesheets().contains(dynamicStylesheet)) {
                dialogPane.getStylesheets().add(dynamicStylesheet);
            }
        }

        AppDesignStyleSupport.applyToDialogPane(dialogPane);
    }
}
