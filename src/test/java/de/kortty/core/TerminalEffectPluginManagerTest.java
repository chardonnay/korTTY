package de.kortty.core;

import de.kortty.plugin.terminaleffects.mother.MotherTerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.pack.AmberCrt90Plugin;
import de.kortty.plugin.terminaleffects.pack.CommodoreHeritagePlugin;
import de.kortty.plugin.terminaleffects.pack.DeepSpaceRadarPlugin;
import de.kortty.plugin.terminaleffects.pack.DigitalRainPlugin;
import de.kortty.plugin.terminaleffects.pack.HologramHudPlugin;
import de.kortty.plugin.terminaleffects.pack.NeonCityPlugin;
import de.kortty.plugin.terminaleffects.pack.PoltergeistPlugin;
import de.kortty.plugin.terminaleffects.pack.SynthwaveHorizonPlugin;
import de.kortty.plugin.terminaleffects.pack.TypewriterNoirPlugin;
import de.kortty.plugin.terminaleffects.pack.Vhs1987Plugin;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static com.google.common.truth.Truth.assertThat;

public class TerminalEffectPluginManagerTest {

    private static final String SERVICE_PATH =
            "META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin";

    private static final List<String> PACK_PLUGIN_IDS = List.of(
            AmberCrt90Plugin.PLUGIN_ID,
            CommodoreHeritagePlugin.PLUGIN_ID,
            NeonCityPlugin.PLUGIN_ID,
            DigitalRainPlugin.PLUGIN_ID,
            HologramHudPlugin.PLUGIN_ID,
            PoltergeistPlugin.PLUGIN_ID,
            Vhs1987Plugin.PLUGIN_ID,
            SynthwaveHorizonPlugin.PLUGIN_ID,
            DeepSpaceRadarPlugin.PLUGIN_ID,
            TypewriterNoirPlugin.PLUGIN_ID);

    @Test
    void loadRegistersBundledMotherPluginJar() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);

        manager.load();

        assertThat(manager.findPlugin(MotherTerminalEffectPlugin.PLUGIN_ID)).isPresent();
        TerminalEffectPluginManager.PluginEntry entry =
                manager.findPluginEntry(MotherTerminalEffectPlugin.PLUGIN_ID).orElseThrow();
        assertThat(entry.source()).isEqualTo(TerminalEffectPluginManager.PluginSource.BUNDLED_JAR);
        assertThat(entry.exportable()).isTrue();
        assertThat(entry.sourcePath().getFileName().toString()).isEqualTo("kortty-terminal-effect-mother.jar");
        assertThat(manager.findPlugin(MotherTerminalEffectPlugin.PLUGIN_ID).orElseThrow().displayName())
                .isEqualTo("MU/TH/UR 6000");
        assertThat(manager.findPlugin(MotherTerminalEffectPlugin.PLUGIN_ID).orElseThrow().description())
                .contains("CRT");
    }

    @Test
    void loadRegistersBundledEffectPackPluginJar() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);

        manager.load();

        for (String pluginId : PACK_PLUGIN_IDS) {
            assertThat(manager.findPlugin(pluginId)).isPresent();
            TerminalEffectPluginManager.PluginEntry entry =
                    manager.findPluginEntry(pluginId).orElseThrow();
            assertThat(entry.source()).isEqualTo(TerminalEffectPluginManager.PluginSource.BUNDLED_JAR);
            assertThat(entry.exportable()).isTrue();
            assertThat(entry.sourcePath().getFileName().toString())
                    .isEqualTo("kortty-terminal-effect-pack.jar");
        }
    }

    @Test
    void builtInPluginIdsAreUniqueValidAndProvidePreviews() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);

        manager.load();

        List<TerminalEffectPluginManager.PluginEntry> entries = manager.getPluginEntries();
        assertThat(entries).hasSize(PACK_PLUGIN_IDS.size() + 1);
        Set<String> seenIds = new HashSet<>();
        for (TerminalEffectPluginManager.PluginEntry entry : entries) {
            assertThat(entry.id()).matches("[a-z0-9][a-z0-9._-]{0,63}");
            assertThat(seenIds.add(entry.id())).isTrue();
            assertThat(entry.displayName()).isNotEmpty();
            assertThat(entry.plugin().createPreview()).isNotNull();
        }
    }

    @Test
    void loadIgnoresBrokenExternalPluginJar() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        Path pluginsDir = configDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        writeServiceJar(pluginsDir.resolve("broken.jar"), "missing.Provider\n");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);

        manager.load();

        assertThat(manager.findPlugin(MotherTerminalEffectPlugin.PLUGIN_ID)).isPresent();
    }

    @Test
    void loadRejectsDuplicatePluginIdFromExternalJar() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        Path pluginsDir = configDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        writeServiceJar(
                pluginsDir.resolve("duplicate.jar"),
                MotherTerminalEffectPlugin.class.getName() + "\n");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);

        manager.load();

        long motherCount = manager.getPlugins().stream()
                .filter(plugin -> MotherTerminalEffectPlugin.PLUGIN_ID.equals(plugin.id()))
                .count();
        assertThat(motherCount).isEqualTo(1);
    }

    @Test
    void disabledPluginStaysListedButIsNotAvailableForActivation() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);
        manager.load();

        manager.setPluginEnabled(MotherTerminalEffectPlugin.PLUGIN_ID, false);

        assertThat(manager.findPlugin(MotherTerminalEffectPlugin.PLUGIN_ID)).isEmpty();
        assertThat(manager.findPluginEntry(MotherTerminalEffectPlugin.PLUGIN_ID)).isPresent();
        assertThat(manager.findPluginEntry(MotherTerminalEffectPlugin.PLUGIN_ID).orElseThrow().enabled())
                .isFalse();

        TerminalEffectPluginManager reloaded = new TerminalEffectPluginManager(configDir);
        reloaded.load();

        assertThat(reloaded.findPlugin(MotherTerminalEffectPlugin.PLUGIN_ID)).isEmpty();
        assertThat(reloaded.findPluginEntry(MotherTerminalEffectPlugin.PLUGIN_ID).orElseThrow().enabled())
                .isFalse();
    }

    @Test
    void importsAndExportsExternalPluginJar() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        Path sourceJar = Files.createTempFile("external-terminal-effect", ".jar");
        writeServiceJar(sourceJar, ExternalTestPlugin.class.getName() + "\n");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);

        Path importedJar = manager.importPluginJar(sourceJar);

        assertThat(importedJar.getParent()).isEqualTo(configDir.resolve("plugins"));
        assertThat(manager.findPlugin(ExternalTestPlugin.PLUGIN_ID)).isPresent();
        TerminalEffectPluginManager.PluginEntry entry =
                manager.findPluginEntry(ExternalTestPlugin.PLUGIN_ID).orElseThrow();
        assertThat(entry.description()).isEqualTo("External test effect");
        assertThat(entry.exportable()).isTrue();

        Path exportedJar = configDir.resolve("exported.jar");
        manager.exportPlugin(ExternalTestPlugin.PLUGIN_ID, exportedJar);

        assertThat(Files.readAllBytes(exportedJar)).isEqualTo(Files.readAllBytes(importedJar));
    }

    @Test
    void exportsBundledMotherPluginJar() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-terminal-effects-test");
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(configDir);
        manager.load();

        Path exportedJar = configDir.resolve("mother-export.jar");
        manager.exportPlugin(MotherTerminalEffectPlugin.PLUGIN_ID, exportedJar);

        TerminalEffectPluginManager.PluginEntry entry =
                manager.findPluginEntry(MotherTerminalEffectPlugin.PLUGIN_ID).orElseThrow();
        assertThat(Files.readAllBytes(exportedJar)).isEqualTo(Files.readAllBytes(entry.sourcePath()));
    }

    private static void writeServiceJar(Path jar, String serviceContent) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(SERVICE_PATH));
            output.write(serviceContent.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    public static final class ExternalTestPlugin implements TerminalEffectPlugin {

        static final String PLUGIN_ID = "external-test";

        @Override
        public String id() {
            return PLUGIN_ID;
        }

        @Override
        public String displayName() {
            return "External Test";
        }

        @Override
        public String description() {
            return "External test effect";
        }

        @Override
        public TerminalEffectSession createSession(TerminalEffectContext context) {
            return new TerminalEffectSession() {
            };
        }
    }
}
