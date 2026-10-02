package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;

/** The remembered "Propose modularization" choice of the Full code analysis (off by default). */
final class SnippetModularizationPreference {

    private SnippetModularizationPreference() {
    }

    static boolean load() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        return settings != null && Boolean.TRUE.equals(settings.getCodeAnalysisProposeModularization());
    }

    static void save(boolean enabled) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisProposeModularization(enabled);
                manager.save();
            }
        } catch (Exception ignored) {
            // a preference that cannot be stored only resets on the next start
        }
    }

    /** Whether the folder analysis tab shows its flow diagram (shown by default). */
    static boolean loadProjectDiagramVisible() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        return settings == null || !Boolean.FALSE.equals(settings.getCodeAnalysisProjectDiagramVisible());
    }

    static void saveProjectDiagramVisible(boolean visible) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisProjectDiagramVisible(visible);
                manager.save();
            }
        } catch (Exception ignored) {
            // a preference that cannot be stored only resets on the next start
        }
    }
}
