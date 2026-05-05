package de.kortty.core;

import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads trusted terminal effect plugins from the application classpath, bundled plugin JARs, and ~/.kortty/plugins.
 */
public class TerminalEffectPluginManager {

    private static final Logger logger = LoggerFactory.getLogger(TerminalEffectPluginManager.class);
    private static final Pattern VALID_PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VALID_BUNDLED_PLUGIN_JAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");
    private static final String BUNDLED_PLUGIN_INDEX_RESOURCE = "bundled-plugins/terminal-effects.index";
    private static final String BUNDLED_PLUGIN_RESOURCE_DIR = "bundled-plugins/terminal-effects/";

    private final Path pluginsDir;
    private final Path bundledPluginsDir;
    private final Path disabledPluginsFile;
    private final Map<String, TerminalEffectPlugin> pluginsById = new LinkedHashMap<>();
    private final Map<String, PluginEntry> pluginEntriesById = new LinkedHashMap<>();
    private final Set<String> disabledPluginIds = new HashSet<>();
    private final List<URLClassLoader> externalClassLoaders = new ArrayList<>();

    public TerminalEffectPluginManager(Path configDir) {
        this.pluginsDir = configDir.resolve("plugins");
        this.bundledPluginsDir = configDir.resolve("bundled-plugins").resolve("terminal-effects");
        this.disabledPluginsFile = configDir.resolve("terminal-effect-plugins.disabled");
    }

    public void load() {
        pluginsById.clear();
        pluginEntriesById.clear();
        closeExternalClassLoaders();
        loadDisabledPluginIds();
        loadFromServiceLoader(
                ServiceLoader.load(TerminalEffectPlugin.class, TerminalEffectPlugin.class.getClassLoader()),
                PluginSource.APPLICATION,
                "application",
                null);
        loadBundledPlugins();
        loadExternalPlugins();
        rebuildEnabledPlugins();
        logger.info("Loaded {} terminal effect plugin(s), {} enabled",
                pluginEntriesById.size(), pluginsById.size());
    }

    public List<TerminalEffectPlugin> getPlugins() {
        return List.copyOf(pluginsById.values());
    }

    public List<PluginEntry> getPluginEntries() {
        return List.copyOf(pluginEntriesById.values());
    }

    public Optional<TerminalEffectPlugin> findPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pluginsById.get(pluginId));
    }

    public Optional<PluginEntry> findPluginEntry(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pluginEntriesById.get(pluginId));
    }

    public Path getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginEnabled(String pluginId, boolean enabled) throws IOException {
        String id = safeTrim(pluginId);
        if (id == null || !VALID_PLUGIN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid terminal effect plugin id: " + pluginId);
        }
        if (!pluginEntriesById.containsKey(id)) {
            throw new IllegalArgumentException("Unknown terminal effect plugin id: " + id);
        }
        if (enabled) {
            disabledPluginIds.remove(id);
        } else {
            disabledPluginIds.add(id);
        }
        saveDisabledPluginIds();
        rebuildPluginEntries();
        rebuildEnabledPlugins();
    }

    public Path importPluginJar(Path sourceJar) throws IOException {
        if (sourceJar == null || !Files.isRegularFile(sourceJar)) {
            throw new IOException("Plugin jar does not exist: " + sourceJar);
        }
        if (!sourceJar.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Terminal effect plugins must be .jar files");
        }
        Files.createDirectories(pluginsDir);
        Path target = uniquePluginPath(sourceJar.getFileName().toString());
        Files.copy(sourceJar, target);
        load();
        return target;
    }

    public void exportPlugin(String pluginId, Path targetJar) throws IOException {
        PluginEntry entry = findPluginEntry(pluginId)
                .orElseThrow(() -> new IOException("Unknown terminal effect plugin id: " + pluginId));
        if (!entry.exportable()) {
            throw new IOException("Plugin is built into KorTTY and has no exportable jar: " + entry.displayName());
        }
        if (targetJar == null) {
            throw new IOException("No export target selected");
        }
        Path parent = targetJar.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(entry.sourcePath(), targetJar, StandardCopyOption.REPLACE_EXISTING);
    }

    private void loadExternalPlugins() {
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            logger.warn("Could not create terminal effect plugin directory {}: {}", pluginsDir, e.getMessage());
            return;
        }

        List<Path> jars;
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            jars = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            logger.warn("Could not list terminal effect plugin directory {}: {}", pluginsDir, e.getMessage());
            return;
        }

        for (Path jar : jars) {
            loadPluginJar(jar, PluginSource.EXTERNAL_JAR);
        }
    }

    private void loadBundledPlugins() {
        ClassLoader classLoader = TerminalEffectPlugin.class.getClassLoader();
        try (InputStream indexStream = classLoader.getResourceAsStream(BUNDLED_PLUGIN_INDEX_RESOURCE)) {
            if (indexStream == null) {
                return;
            }
            Files.createDirectories(bundledPluginsDir);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    copyAndLoadBundledPlugin(line, classLoader);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not load bundled terminal effect plugins: {}", e.getMessage());
        }
    }

    private void copyAndLoadBundledPlugin(String indexLine, ClassLoader classLoader) throws IOException {
        String jarName = safeTrim(indexLine);
        if (jarName == null || jarName.startsWith("#")) {
            return;
        }
        if (!VALID_BUNDLED_PLUGIN_JAR.matcher(jarName).matches()) {
            logger.warn("Ignoring bundled terminal effect plugin with unsafe file name '{}'", jarName);
            return;
        }

        String resourceName = BUNDLED_PLUGIN_RESOURCE_DIR + jarName;
        Path target = bundledPluginsDir.resolve(jarName);
        try (InputStream pluginStream = classLoader.getResourceAsStream(resourceName)) {
            if (pluginStream == null) {
                logger.warn("Bundled terminal effect plugin resource missing: {}", resourceName);
                return;
            }
            Files.copy(pluginStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        loadPluginJar(target, PluginSource.BUNDLED_JAR);
    }

    private void loadPluginJar(Path jar, PluginSource source) {
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()},
                    TerminalEffectPlugin.class.getClassLoader());
            externalClassLoaders.add(loader);
            loadFromServiceLoader(ServiceLoader.load(TerminalEffectPlugin.class, loader),
                    source,
                    jar.toString(),
                    jar);
        } catch (IOException e) {
            logger.warn("Could not load terminal effect plugin jar {}: {}", jar, e.getMessage());
        }
    }

    private void loadFromServiceLoader(
            ServiceLoader<TerminalEffectPlugin> loader,
            PluginSource pluginSource,
            String source,
            Path sourcePath) {
        var iterator = loader.iterator();
        while (true) {
            TerminalEffectPlugin plugin;
            try {
                if (!iterator.hasNext()) {
                    return;
                }
                plugin = iterator.next();
            } catch (ServiceConfigurationError e) {
                logger.warn("Ignoring invalid terminal effect plugin from {}: {}", source, e.getMessage());
                continue;
            }
            registerPlugin(plugin, pluginSource, source, sourcePath);
        }
    }

    private void registerPlugin(TerminalEffectPlugin plugin, PluginSource pluginSource, String source, Path sourcePath) {
        if (plugin == null) {
            return;
        }
        String id = safeTrim(plugin.id());
        String displayName = safeTrim(plugin.displayName());
        String description = safeTrim(plugin.description());
        if (id == null || !VALID_PLUGIN_ID.matcher(id).matches()) {
            logger.warn("Ignoring terminal effect plugin with invalid id '{}' from {}", id, source);
            return;
        }
        if (displayName == null) {
            logger.warn("Ignoring terminal effect plugin '{}' from {} because displayName is blank", id, source);
            return;
        }
        if (pluginEntriesById.containsKey(id)) {
            logger.warn("Ignoring duplicate terminal effect plugin id '{}' from {}", id, source);
            return;
        }
        pluginEntriesById.put(id, new PluginEntry(
                id,
                displayName,
                description != null ? description : "",
                plugin,
                pluginSource,
                sourcePath,
                !disabledPluginIds.contains(id)));
        logger.info("Registered terminal effect plugin '{}' ({}) from {}", displayName, id, source);
    }

    private void loadDisabledPluginIds() {
        disabledPluginIds.clear();
        if (!Files.isRegularFile(disabledPluginsFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(disabledPluginsFile, StandardCharsets.UTF_8)) {
                String id = safeTrim(line);
                if (id != null && VALID_PLUGIN_ID.matcher(id).matches()) {
                    disabledPluginIds.add(id);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read disabled terminal effect plugin list {}: {}",
                    disabledPluginsFile, e.getMessage());
        }
    }

    private void saveDisabledPluginIds() throws IOException {
        Path parent = disabledPluginsFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(disabledPluginsFile, new TreeSet<>(disabledPluginIds), StandardCharsets.UTF_8);
    }

    private void rebuildPluginEntries() {
        replacePluginEntries(pluginEntriesById.values().stream()
                .map(entry -> entry.withEnabled(!disabledPluginIds.contains(entry.id())))
                .toList());
    }

    private void rebuildEnabledPlugins() {
        pluginsById.clear();
        for (PluginEntry entry : pluginEntriesById.values()) {
            if (entry.enabled()) {
                pluginsById.put(entry.id(), entry.plugin());
            }
        }
    }

    private void replacePluginEntries(Collection<PluginEntry> entries) {
        pluginEntriesById.clear();
        for (PluginEntry entry : entries) {
            pluginEntriesById.put(entry.id(), entry);
        }
    }

    private Path uniquePluginPath(String fileName) {
        Path candidate = pluginsDir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String name = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            name = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        int counter = 1;
        while (true) {
            candidate = pluginsDir.resolve(name + "-" + counter + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private static String safeTrim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void closeExternalClassLoaders() {
        for (URLClassLoader loader : externalClassLoaders) {
            try {
                loader.close();
            } catch (IOException e) {
                logger.debug("Could not close terminal effect plugin class loader: {}", e.getMessage());
            }
        }
        externalClassLoaders.clear();
    }

    public enum PluginSource {
        APPLICATION,
        BUNDLED_JAR,
        EXTERNAL_JAR
    }

    public record PluginEntry(
            String id,
            String displayName,
            String description,
            TerminalEffectPlugin plugin,
            PluginSource source,
            Path sourcePath,
            boolean enabled) {

        public boolean exportable() {
            return sourcePath != null && Files.isRegularFile(sourcePath);
        }

        private PluginEntry withEnabled(boolean enabled) {
            return new PluginEntry(id, displayName, description, plugin, source, sourcePath, enabled);
        }
    }
}
