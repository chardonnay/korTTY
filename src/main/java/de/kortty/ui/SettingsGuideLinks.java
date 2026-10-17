package de.kortty.ui;

import java.util.EnumMap;
import java.util.Map;

/**
 * Where the built-in manual documents each settings page, as a guide location for
 * {@link GuideViewer#show(de.kortty.KorTTYApplication, javafx.stage.Window, String)}. Anchors are
 * explicit heading ids ({@code { #font-tab }}), so they survive the German translation.
 */
final class SettingsGuideLinks {

    private static final String SETTINGS = "reference/settings/";
    private static final Map<SettingsSection, String> LOCATIONS = new EnumMap<>(SettingsSection.class);

    static {
        LOCATIONS.put(SettingsSection.FONT, SETTINGS + "appearance.html#font-tab");
        LOCATIONS.put(SettingsSection.COLORS, SETTINGS + "colors.html");
        LOCATIONS.put(SettingsSection.THEMES, SETTINGS + "appearance.html#themes-tab");
        LOCATIONS.put(SettingsSection.APPEARANCE, SETTINGS + "appearance.html#appearance-tab");
        LOCATIONS.put(SettingsSection.TERMINAL, SETTINGS + "terminal.html");
        LOCATIONS.put(SettingsSection.MESSAGING, SETTINGS + "messaging.html");
        LOCATIONS.put(SettingsSection.VIDEO, SETTINGS + "video.html");
        LOCATIONS.put(SettingsSection.BACKUP, SETTINGS + "backup.html");
        LOCATIONS.put(SettingsSection.LOGGING, SETTINGS + "logging.html");
        LOCATIONS.put(SettingsSection.EXPORT, SETTINGS + "export.html");
        LOCATIONS.put(SettingsSection.UPDATES, SETTINGS + "updates.html");
        LOCATIONS.put(SettingsSection.WINDOW, SETTINGS + "window.html");
        LOCATIONS.put(SettingsSection.KEYBOARD, SETTINGS + "keyboard.html");
        LOCATIONS.put(SettingsSection.RESOURCES, SETTINGS + "resources.html");
        LOCATIONS.put(SettingsSection.SECURITY, SETTINGS + "security.html");
        LOCATIONS.put(SettingsSection.PRIVACY, de.kortty.telemetry.TelemetryEvents.GUIDE_LOCATION);
        LOCATIONS.put(SettingsSection.SFTP, SETTINGS + "sftp.html");
        LOCATIONS.put(SettingsSection.EDITOR, SETTINGS + "editor.html");
        LOCATIONS.put(SettingsSection.SNIPPET_EDITOR, SETTINGS + "snippet-editor/index.html");
        LOCATIONS.put(SettingsSection.LANGUAGE, SETTINGS + "language.html");
        LOCATIONS.put(SettingsSection.TRANSLATION, SETTINGS + "translation.html");
        LOCATIONS.put(SettingsSection.AI, SETTINGS + "ai.html");
    }

    private SettingsGuideLinks() {
    }

    /** The guide location for {@code section}; the settings overview for one without its own page. */
    static String location(SettingsSection section) {
        String location = section != null ? LOCATIONS.get(section) : null;
        return location != null ? location : SETTINGS + "index.html";
    }
}
