package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetPlaceholderResolver;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.GlobalSettings;

/**
 * Where the snippet editor's structure panel keeps its state: whether it is shown and how wide it
 * is (global settings, the same for every editor), and which placeholder names the Variable Manager
 * declares. Every access tolerates a missing application (tests, screenshot stages) and falls back
 * to the defaults: hidden, {@link SnippetStructurePanel#DEFAULT_WIDTH}, nothing declared.
 */
final class SnippetStructureSettings {

    private SnippetStructureSettings() {
    }

    static boolean loadVisible() {
        GlobalSettings settings = settings();
        return settings != null && settings.isSnippetStructurePanelVisible();
    }

    static void saveVisible(boolean visible) {
        update(settings -> settings.setSnippetStructurePanelVisible(visible));
    }

    static double loadWidth() {
        GlobalSettings settings = settings();
        Double width = settings != null ? settings.getSnippetStructurePanelWidth() : null;
        return width != null ? width : SnippetStructurePanel.DEFAULT_WIDTH;
    }

    static void saveWidth(double width) {
        update(settings -> settings.setSnippetStructurePanelWidth(width));
    }

    /** Whether the Variable Manager declares {@code name} (ignoring case); nothing without an application. */
    static SnippetPlaceholderResolver.Declarations declarations() {
        return name -> {
            SnippetVariableManager variables = variables();
            return variables != null && variables.isDeclared(name);
        };
    }

    /** The stored value of a declared name, or {@code null} when it has none (it is then asked for). */
    static String storedValue(String name) {
        SnippetVariableManager variables = variables();
        return variables != null ? variables.getValue(name) : null;
    }

    private static SnippetVariableManager variables() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getSnippetVariableManager() : null;
    }

    private static GlobalSettings settings() {
        GlobalSettingsManager manager = manager();
        return manager != null ? manager.getSettings() : null;
    }

    private static GlobalSettingsManager manager() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            return app != null ? app.getGlobalSettingsManager() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void update(java.util.function.Consumer<GlobalSettings> change) {
        GlobalSettingsManager manager = manager();
        if (manager == null || manager.getSettings() == null) {
            return;
        }
        try {
            change.accept(manager.getSettings());
            manager.save();
        } catch (Exception e) {
            // Non-critical: the panel keeps working with the state it has.
        }
    }
}
