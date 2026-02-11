package de.kortty.core;

import de.kortty.model.Theme;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages terminal themes (color profiles).
 * Themes are persisted in themes.xml.
 */
public class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    private static final String THEMES_FILE = "themes.xml";
    private static final String DEFAULT_THEME_ID = "default";
    private static final String DARK_THEME_ID = "dark";

    private final Path configDir;
    private ThemeList themeList;

    public ThemeManager(Path configDir) {
        this.configDir = configDir;
        this.themeList = new ThemeList();
    }

    /**
     * Loads themes from XML file.
     * Ensures default and dark themes exist.
     */
    public void load() {
        Path themesFile = configDir.resolve(THEMES_FILE);

        if (!Files.exists(themesFile)) {
            logger.info("Themes file not found, creating defaults");
            createDefaultThemes();
            return;
        }

        try {
            JAXBContext context = JAXBContext.newInstance(ThemeList.class, Theme.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            themeList = (ThemeList) unmarshaller.unmarshal(themesFile.toFile());
            ensureBuiltInThemes();
            logger.info("Loaded {} themes from {}", themeList.getThemes().size(), themesFile);
        } catch (Exception e) {
            logger.error("Failed to load themes, using defaults", e);
            createDefaultThemes();
        }
    }

    /**
     * Saves themes to XML file.
     */
    public void save() {
        Path themesFile = configDir.resolve(THEMES_FILE);
        try {
            JAXBContext context = JAXBContext.newInstance(ThemeList.class, Theme.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.marshal(themeList, themesFile.toFile());
            logger.info("Saved themes to {}", themesFile);
        } catch (Exception e) {
            logger.error("Failed to save themes", e);
        }
    }

    public List<Theme> getThemes() {
        return themeList.getThemes();
    }

    public Optional<Theme> getTheme(String id) {
        return themeList.getThemes().stream()
                .filter(t -> id != null && id.equals(t.getId()))
                .findFirst();
    }

    public Theme getDefaultTheme() {
        return getTheme(DEFAULT_THEME_ID)
                .orElseGet(() -> getThemes().isEmpty() ? createDefaultTheme() : getThemes().get(0));
    }

    public Theme getDarkTheme() {
        return getTheme(DARK_THEME_ID).orElseGet(this::createDarkTheme);
    }

    public Theme addTheme(Theme theme) {
        if (theme.getId() == null || theme.getId().isEmpty()) {
            theme.setId(UUID.randomUUID().toString());
        }
        themeList.getThemes().add(theme);
        save();
        return theme;
    }

    public void updateTheme(Theme theme) {
        for (int i = 0; i < themeList.getThemes().size(); i++) {
            if (theme.getId().equals(themeList.getThemes().get(i).getId())) {
                themeList.getThemes().set(i, theme);
                save();
                return;
            }
        }
        addTheme(theme);
    }

    public void removeTheme(String themeId) {
        if (DEFAULT_THEME_ID.equals(themeId) || DARK_THEME_ID.equals(themeId)) {
            logger.warn("Cannot remove built-in theme: {}", themeId);
            return;
        }
        themeList.getThemes().removeIf(t -> themeId.equals(t.getId()));
        save();
    }

    /**
     * Resolves effective settings: if themeId is set, apply theme to a copy of base settings.
     */
    public de.kortty.model.ConnectionSettings resolveSettings(
            de.kortty.model.ConnectionSettings base,
            String themeId) {
        if (themeId == null || themeId.isEmpty()) {
            return base;
        }
        return getTheme(themeId)
                .map(theme -> {
                    de.kortty.model.ConnectionSettings effective = new de.kortty.model.ConnectionSettings(base);
                    theme.applyTo(effective);
                    return effective;
                })
                .orElse(base);
    }

    private void createDefaultThemes() {
        themeList = new ThemeList();
        themeList.getThemes().add(createDefaultTheme());
        themeList.getThemes().add(createDarkTheme());
        save();
    }

    private void ensureBuiltInThemes() {
        boolean hasDefault = themeList.getThemes().stream().anyMatch(t -> DEFAULT_THEME_ID.equals(t.getId()));
        boolean hasDark = themeList.getThemes().stream().anyMatch(t -> DARK_THEME_ID.equals(t.getId()));
        if (!hasDefault) {
            themeList.getThemes().add(0, createDefaultTheme());
        }
        if (!hasDark) {
            themeList.getThemes().add(hasDefault ? 1 : 0, createDarkTheme());
        }
    }

    private Theme createDefaultTheme() {
        Theme t = new Theme(DEFAULT_THEME_ID, "Default", true);
        t.setFontFamily("Monaco");
        t.setFontSize(15);
        t.setForegroundColor("#FFFFFF");
        t.setBackgroundColor("#000000");
        t.setCursorColor("#FFFFFF");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createDarkTheme() {
        Theme t = new Theme(DARK_THEME_ID, "Dark Mode", true);
        t.setFontFamily("Monaco");
        t.setFontSize(15);
        t.setForegroundColor("#FFFFFF");
        t.setBackgroundColor("#1E1E1E");
        t.setCursorColor("#FFFFFF");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    @XmlRootElement(name = "themes")
    private static class ThemeList {
        @XmlElement(name = "theme")
        private List<Theme> themes = new ArrayList<>();

        public List<Theme> getThemes() {
            if (themes == null) {
                themes = new ArrayList<>();
            }
            return themes;
        }
    }
}
