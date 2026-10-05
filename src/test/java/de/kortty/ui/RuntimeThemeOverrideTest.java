package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.ConnectionSettingsSupport;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.Theme;
import de.kortty.security.EncryptionService;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A theme picked from the terminal context menu stays on the tab for the session: it must never
 * reach the stored connection, whose {@link ConnectionSettings} a tab's connection shares through
 * {@link ServerConnection#copyForAuth}. The tab itself needs a shown window and a session, so its
 * wiring is pinned in the sources at the end, line-ending agnostic.
 */
class RuntimeThemeOverrideTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");
    private static final Path TERMINAL_TAB = Path.of("src/main/java/de/kortty/ui/TerminalTab.java");

    private Path configDir;
    private SecretKey key;

    @BeforeMethod
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("kortty-runtime-theme");
        EncryptionService encryption = new EncryptionService();
        key = encryption.deriveKey("test-master-password".toCharArray(), encryption.generateSalt());
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(configDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aThemePickedForTheTabLeavesConnectionsXmlUnchanged() throws IOException {
        ConfigurationManager store = new ConfigurationManager(configDir);
        ServerConnection stored = storedConnection();
        store.addConnection(stored);
        store.save(key);
        String before = connectionsXml();

        // The tab runs a copyForAuth copy (shared settings) and its own resolved settings copy.
        ServerConnection tabConnection = ServerConnection.copyForAuth(stored);
        assertWithMessage("the copy shares the stored connection's settings — the reason for this test")
            .that(tabConnection.getSettings()).isSameInstanceAs(stored.getSettings());
        ConnectionSettings tabSettings =
            ConnectionSettingsSupport.effectiveTerminalSettings(tabConnection.getSettings(), null);

        RuntimeThemeOverride override = new RuntimeThemeOverride();
        override.select(pickedTheme(), tabSettings, true);

        assertThat(tabSettings.getForegroundColor()).isEqualTo("#f8f8f2");
        assertThat(tabSettings.getBackgroundColor()).isEqualTo("#282a36");
        assertThat(tabSettings.getFontFamily()).isEqualTo("Picked Mono");
        assertThat(tabSettings.getThemeId()).isEqualTo("picked");
        assertThat(override.theme().getId()).isEqualTo("picked");

        assertThat(stored.getSettings().getForegroundColor()).isEqualTo("#00ff00");
        assertThat(stored.getSettings().getBackgroundColor()).isEqualTo("#000000");
        assertThat(stored.getSettings().getFontFamily()).isEqualTo("Saved Mono");
        assertThat(stored.getSettings().getThemeId()).isEqualTo("saved");

        // E.g. the Connection Manager saving any connection.
        store.save(key);
        assertThat(connectionsXml()).isEqualTo(before);

        ConfigurationManager reloaded = new ConfigurationManager(configDir);
        reloaded.load(key);
        ConnectionSettings reloadedSettings = reloaded.getConnectionById(stored.getId()).getSettings();
        assertThat(reloadedSettings.getThemeId()).isEqualTo("saved");
        assertThat(reloadedSettings.getForegroundColor()).isEqualTo("#00ff00");
    }

    @Test
    void aRefreshKeepsThePickedThemeOnTheTabWithoutTouchingTheStoredSettings() throws IOException {
        ConfigurationManager store = new ConfigurationManager(configDir);
        ServerConnection stored = storedConnection();
        store.addConnection(stored);
        store.save(key);
        String before = connectionsXml();

        RuntimeThemeOverride override = new RuntimeThemeOverride();
        override.select(pickedTheme(), new ConnectionSettings(stored.getSettings()), false);

        // The refresh after a connection-manager save hands the tab the stored settings.
        ConnectionSettings resolved = ConnectionSettingsSupport.effectiveTerminalSettings(stored.getSettings(), null);
        ConnectionSettings applied = override.applyOver(resolved, false);

        assertThat(applied).isNotSameInstanceAs(resolved);
        assertThat(applied.getForegroundColor()).isEqualTo("#f8f8f2");
        assertThat(applied.getThemeId()).isEqualTo("picked");
        assertWithMessage("fonts follow the theme only when theme fonts are enabled")
            .that(applied.getFontFamily()).isEqualTo("Saved Mono");
        assertThat(resolved.getForegroundColor()).isEqualTo("#00ff00");
        assertThat(resolved.getThemeId()).isEqualTo("saved");

        store.save(key);
        assertThat(connectionsXml()).isEqualTo(before);
    }

    @Test
    void withoutAPickedThemeTheTabFollowsItsConnection() {
        RuntimeThemeOverride override = new RuntimeThemeOverride();
        ConnectionSettings resolved = storedConnection().getSettings();

        assertThat(override.theme()).isNull();
        assertThat(override.applyOver(resolved, true)).isSameInstanceAs(resolved);

        ConnectionSettings tabSettings = new ConnectionSettings(resolved);
        override.select(null, tabSettings, true);
        assertThat(override.theme()).isNull();
        assertThat(tabSettings.getForegroundColor()).isEqualTo("#00ff00");
    }

    @Test
    void theTabWiringNeverWritesTheRuntimeThemeIntoTheConnection() throws IOException {
        String view = read(TERMINAL_VIEW);

        String runtimeTheme = methodBody(view, "private void applyThemeAtRuntime(Theme theme) {");
        assertThat(runtimeTheme).contains("runtimeThemeOverride.select(theme, settings, isThemeFontApplyEnabled());");
        assertWithMessage("the connection's settings are shared with the stored connection")
            .that(runtimeTheme).doesNotContain("connection.getSettings()");
        assertThat(runtimeTheme).doesNotContain("setThemeId");

        assertThat(methodBody(view, "public void applyConnectionSettings(ConnectionSettings s) {"))
            .contains("runtimeThemeOverride.applyOver(resolveEffectiveSettings(s), isThemeFontApplyEnabled())");

        assertThat(methodBody(read(TERMINAL_TAB), "private void applyAiAgentActivityTheme(ConnectionSettings connectionSettings) {"))
            .contains("terminalView.getRuntimeThemeOverride()");
    }

    private static ServerConnection storedConnection() {
        ServerConnection connection = new ServerConnection("Demo", "demo.example.test", 22, "demo");
        ConnectionSettings settings = connection.getSettings();
        settings.setUseGlobalSettings(false);
        settings.setThemeId("saved");
        settings.setForegroundColor("#00ff00");
        settings.setBackgroundColor("#000000");
        settings.setCursorColor("#00ff00");
        settings.setFontFamily("Saved Mono");
        settings.setFontSize(13);
        return connection;
    }

    private static Theme pickedTheme() {
        Theme theme = new Theme("picked", "Picked", false);
        theme.setForegroundColor("#f8f8f2");
        theme.setBackgroundColor("#282a36");
        theme.setCursorColor("#ff79c6");
        theme.setFontFamily("Picked Mono");
        theme.setFontSize(15);
        return theme;
    }

    private String connectionsXml() throws IOException {
        return Files.readString(configDir.resolve("connections.xml"), StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the brace closing its body. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start + signature.length() - 1);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
