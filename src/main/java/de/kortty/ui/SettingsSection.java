package de.kortty.ui;

/** Stable, untranslated destinations for settings shortcuts. Order matches the dialog. */
public enum SettingsSection {
    FONT("font"), COLORS("colors"), THEMES("themes"), APPEARANCE("appearance"), TERMINAL("terminal"), MESSAGING("messaging"),
    VIDEO("video"), BACKUP("backup"), LOGGING("logging"), EXPORT("export"), UPDATES("updates"),
    WINDOW("window"), KEYBOARD("keyboard"), RESOURCES("resources"), SECURITY("security"),
    PRIVACY("privacy"), SFTP("sftp"), EDITOR("editor"), SNIPPET_EDITOR("snippetEditor"),
    LANGUAGE("language"), TRANSLATION("translation");

    private final String key;
    SettingsSection(String key) { this.key = key; }
    public String key() { return key; }
    public String labelKey() { return "settings.tab." + key; }
    public String actionId() { return "toolbar.settings." + key; }
}
