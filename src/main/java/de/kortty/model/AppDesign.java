package de.kortty.model;

/**
 * Global app-level visual designs. Persist stable IDs instead of enum names so
 * XML values can remain compatible if enum constants are renamed later.
 */
public enum AppDesign {
    NORMAL("normal"),
    MATRIX_TERMINAL("matrix-terminal"),
    HOLOGRAPHIC_INTERFACE("holographic-interface"),
    KLINGON_TACTICAL("klingon-tactical"),
    ELEGANT_DARK("elegant-dark"),
    AMBER_CRT("amber-crt"),
    SYNTHWAVE_84("synthwave-84"),
    GRUVBOX_RETRO("gruvbox-retro"),
    NORD_ARCTIC("nord-arctic"),
    DRACULA("dracula"),
    ATLANTAFX_PRIMER_DARK("atlantafx-primer-dark");

    private final String id;

    AppDesign(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static AppDesign fromId(String id) {
        if (id == null || id.isBlank()) {
            return NORMAL;
        }

        String normalized = id.trim();
        for (AppDesign design : values()) {
            if (design.id.equalsIgnoreCase(normalized)
                    || design.name().equalsIgnoreCase(normalized)) {
                return design;
            }
        }
        return NORMAL;
    }
}
