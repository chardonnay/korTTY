package de.kortty.model;

/**
 * Listener for settings changes.
 */
public interface SettingsChangeListener {
    
    /**
     * Called when settings have changed.
     * @param settings The updated settings
     */
    void onSettingsChanged(ConnectionSettings settings);
}
