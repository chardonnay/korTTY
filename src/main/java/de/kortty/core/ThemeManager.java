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
            boolean updatedThemes = ensureThemeDefaults();
            updatedThemes |= ensureBuiltInThemes();
            if (updatedThemes) {
                save();
            }
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
        theme.initializeAgentPanelColorsIfMissing();
        themeList.getThemes().add(theme);
        save();
        return theme;
    }

    public void updateTheme(Theme theme) {
        if (theme == null) {
            return;
        }
        theme.initializeAgentPanelColorsIfMissing();
        for (int i = 0; i < themeList.getThemes().size(); i++) {
            Theme existing = themeList.getThemes().get(i);
            if (theme.getId() != null && theme.getId().equals(existing.getId())) {
                if (existing.isBuiltIn()) {
                    theme.setBuiltIn(true);
                }
                themeList.getThemes().set(i, theme);
                save();
                return;
            }
        }
        addTheme(theme);
    }

    public void removeTheme(String themeId) {
        Optional<Theme> theme = getTheme(themeId);
        if (theme.isPresent() && theme.get().isBuiltIn()) {
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
        return resolveSettings(base, themeId, false);
    }

    public de.kortty.model.ConnectionSettings resolveSettings(
            de.kortty.model.ConnectionSettings base,
            String themeId,
            boolean includeFont) {
        if (themeId == null || themeId.isEmpty()) {
            return base;
        }
        return getTheme(themeId)
                .map(theme -> {
                    de.kortty.model.ConnectionSettings effective = new de.kortty.model.ConnectionSettings(base);
                    theme.applyTo(effective, includeFont);
                    return effective;
                })
                .orElse(base);
    }

    private void createDefaultThemes() {
        themeList = new ThemeList();
        themeList.getThemes().addAll(createBuiltInThemes());
        ensureThemeDefaults();
        save();
    }

    private boolean ensureBuiltInThemes() {
        boolean changed = false;
        int insertPos = 0;
        for (Theme builtIn : createBuiltInThemes()) {
            Optional<Theme> existing = getTheme(builtIn.getId());
            if (existing.isPresent()) {
                Theme existingTheme = existing.get();
                if (!existingTheme.isBuiltIn()) {
                    existingTheme.setBuiltIn(true);
                    changed = true;
                }
                changed |= existingTheme.initializeAgentPanelColorsIfMissing();
                insertPos = Math.max(insertPos, themeList.getThemes().indexOf(existingTheme) + 1);
                continue;
            }
            themeList.getThemes().add(insertPos, builtIn);
            insertPos++;
            changed = true;
        }
        return changed;
    }

    private boolean ensureThemeDefaults() {
        boolean changed = false;
        for (Theme theme : themeList.getThemes()) {
            if (theme != null) {
                changed |= theme.initializeAgentPanelColorsIfMissing();
            }
        }
        return changed;
    }

    private List<Theme> createBuiltInThemes() {
        List<Theme> themes = new ArrayList<>();
        themes.add(createDefaultTheme());
        themes.add(createDarkTheme());
        themes.add(createDarculaTheme());
        themes.add(createIntelliJLightTheme());
        themes.add(createFleetDarkTheme());
        themes.add(createFleetLightTheme());
        themes.add(createOneDarkTheme());
        themes.add(createMonokaiTheme());
        themes.add(createSolarizedDarkTheme());
        themes.add(createSolarizedLightTheme());
        themes.add(createNordTheme());
        themes.add(createGitHubDarkTheme());
        themes.add(createGitHubLightTheme());
        themes.add(createHighContrastDarkTheme());
        themes.forEach(Theme::initializeAgentPanelColorsIfMissing);
        return themes;
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

    private Theme createDarculaTheme() {
        Theme t = new Theme("darcula", "IntelliJ Darcula", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#A9B7C6");
        t.setBackgroundColor("#2B2B2B");
        t.setCursorColor("#A9B7C6");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createIntelliJLightTheme() {
        Theme t = new Theme("intellij-light", "IntelliJ Light", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#1E1E1E");
        t.setBackgroundColor("#FFFFFF");
        t.setCursorColor("#1E1E1E");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createFleetDarkTheme() {
        Theme t = new Theme("fleet-dark", "Fleet Dark", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#CAD3F5");
        t.setBackgroundColor("#1E1F22");
        t.setCursorColor("#A6DA95");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createFleetLightTheme() {
        Theme t = new Theme("fleet-light", "Fleet Light", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#2C2D30");
        t.setBackgroundColor("#F7F8FA");
        t.setCursorColor("#005FCC");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createOneDarkTheme() {
        Theme t = new Theme("one-dark", "One Dark", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#ABB2BF");
        t.setBackgroundColor("#282C34");
        t.setCursorColor("#61AFEF");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createMonokaiTheme() {
        Theme t = new Theme("monokai", "Monokai", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#F8F8F2");
        t.setBackgroundColor("#272822");
        t.setCursorColor("#A6E22E");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createSolarizedDarkTheme() {
        Theme t = new Theme("solarized-dark", "Solarized Dark", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#93A1A1");
        t.setBackgroundColor("#002B36");
        t.setCursorColor("#93A1A1");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createSolarizedLightTheme() {
        Theme t = new Theme("solarized-light", "Solarized Light", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#586E75");
        t.setBackgroundColor("#FDF6E3");
        t.setCursorColor("#586E75");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createNordTheme() {
        Theme t = new Theme("nord", "Nord", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#D8DEE9");
        t.setBackgroundColor("#2E3440");
        t.setCursorColor("#88C0D0");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createGitHubDarkTheme() {
        Theme t = new Theme("github-dark", "GitHub Dark", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#C9D1D9");
        t.setBackgroundColor("#0D1117");
        t.setCursorColor("#79C0FF");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createGitHubLightTheme() {
        Theme t = new Theme("github-light", "GitHub Light", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#24292F");
        t.setBackgroundColor("#FFFFFF");
        t.setCursorColor("#0969DA");
        t.setCursorStyle("BLINK_BLOCK");
        return t;
    }

    private Theme createHighContrastDarkTheme() {
        Theme t = new Theme("high-contrast-dark", "High Contrast Dark", true);
        t.setFontFamily("JetBrains Mono");
        t.setFontSize(14);
        t.setForegroundColor("#FFFFFF");
        t.setBackgroundColor("#000000");
        t.setCursorColor("#00FFFF");
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
