package de.kortty.core;

import de.kortty.model.EnvironmentColor;
import de.kortty.model.EnvironmentDefinition;
import de.kortty.model.StoredCredential;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages user-defined environments for credentials.
 * Built-in environments (PRODUCTION, DEVELOPMENT, TEST, STAGING) are always present;
 * custom environments are persisted in environments.xml, together with the optional tab color of
 * any environment, built-in or custom. No environment has a color until the user picks one: every
 * credential starts out in PRODUCTION, so a default color would mark most tabs at once.
 */
public class EnvironmentManager {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentManager.class);
    public static final String ENVIRONMENTS_FILE = "environments.xml";

    /** Shared, thread-safe JAXBContext; building one per load and save is the expensive part. */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(EnvironmentsWrapper.class, EnvironmentDefinition.class,
                    EnvironmentColor.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Path configDir;
    private final List<EnvironmentDefinition> customEnvironments = new ArrayList<>();
    /** Tab color ({@code #RRGGBB}) by environment id, for built-in and custom environments alike. */
    private final Map<String, String> colors = new LinkedHashMap<>();
    private final StoreFileGuard guard;

    public EnvironmentManager(Path configDir) {
        this.configDir = configDir;
        this.guard = new StoreFileGuard(configDir.resolve(ENVIRONMENTS_FILE));
    }

    /**
     * Returns built-in environments (from StoredCredential.Environment) plus custom ones, in order.
     */
    public List<EnvironmentDefinition> getEnvironments() {
        List<EnvironmentDefinition> result = new ArrayList<>();
        for (StoredCredential.Environment e : StoredCredential.Environment.values()) {
            result.add(new EnvironmentDefinition(e.name(), e.getDisplayName()));
        }
        result.addAll(customEnvironments);
        return result;
    }

    /**
     * Returns the display name for an environment id (built-in or custom).
     */
    public String getDisplayName(String environmentId) {
        if (environmentId == null || environmentId.isEmpty()) {
            return StoredCredential.Environment.PRODUCTION.getDisplayName();
        }
        try {
            StoredCredential.Environment e = StoredCredential.Environment.valueOf(environmentId);
            return e.getDisplayName();
        } catch (IllegalArgumentException ignored) {
        }
        return customEnvironments.stream()
                .filter(env -> environmentId.equals(env.getId()))
                .map(EnvironmentDefinition::getDisplayName)
                .findFirst()
                .orElse(environmentId);
    }

    /**
     * Returns true if the given id is a built-in environment (cannot be deleted/renamed via manager).
     */
    public boolean isBuiltIn(String environmentId) {
        if (environmentId == null) return true;
        try {
            StoredCredential.Environment.valueOf(environmentId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Loads the custom environments. Never throws: a corrupt file is moved aside (see
     * {@link #getLoadFailureBackup()}) and a file that cannot be read stays in place and blocks
     * saving; in both cases the environments in memory are kept.
     */
    public void load() {
        Path file = guard.file();
        guard.beginLoad();
        if (guard.isMissing()) {
            customEnvironments.clear();
            colors.clear();
            logger.debug("No environments file found, using built-in only");
            return;
        }
        try {
            Optional<EnvironmentsWrapper> loaded = guard.read(content ->
                (EnvironmentsWrapper) JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(content)));
            if (loaded.isEmpty()) {
                logger.warn("Kept {} custom environments in memory; the unreadable environments file was moved aside",
                    customEnvironments.size());
                return;
            }
            customEnvironments.clear();
            if (loaded.get().getEnvironments() != null) {
                customEnvironments.addAll(loaded.get().getEnvironments());
            }
            colors.clear();
            if (loaded.get().getColors() != null) {
                for (EnvironmentColor color : loaded.get().getColors()) {
                    if (color != null) {
                        setColor(color.getId(), color.getColor());
                    }
                }
            }
            logger.info("Loaded {} custom environments and {} environment colors from {}",
                customEnvironments.size(), colors.size(), file);
        } catch (Exception e) {
            logger.warn("Failed to load environments, keeping the {} in memory: {}",
                customEnvironments.size(), e.toString());
        }
    }

    /**
     * Saves the custom environments atomically.
     *
     * @throws IllegalStateException when the last load could not read the file and left it in place
     */
    public void save() throws Exception {
        Path file = guard.file();
        try {
            guard.ensureWritable();
            Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            EnvironmentsWrapper wrapper = new EnvironmentsWrapper();
            wrapper.setEnvironments(new ArrayList<>(customEnvironments));
            wrapper.setColors(colorList());
            StringWriter xml = new StringWriter();
            marshaller.marshal(wrapper, xml);
            Files.createDirectories(configDir);
            AtomicFileWriter.writeStoreAtomically(file, xml.toString(), AtomicFileWriter.FileMode.PRESERVE);
            logger.info("Saved {} custom environments to {}", customEnvironments.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save environments", e);
            throw e;
        }
    }

    /** Where the last load moved an unreadable {@code environments.xml}, if it did. */
    public Optional<Path> getLoadFailureBackup() {
        return guard.getLoadFailureBackup();
    }

    /** Whether saving is refused because {@code environments.xml} could not be read. */
    public boolean isSaveBlocked() {
        return guard.isSaveBlocked();
    }

    /** Adds a custom environment; id is generated. Returns the new definition. */
    public EnvironmentDefinition addCustomEnvironment(String displayName) {
        String id = "custom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        EnvironmentDefinition env = new EnvironmentDefinition(id, displayName != null ? displayName.trim() : "");
        customEnvironments.add(env);
        return env;
    }

    /** Updates display name of a custom environment by id. */
    public boolean updateCustomEnvironment(String id, String newDisplayName) {
        if (isBuiltIn(id)) return false;
        Optional<EnvironmentDefinition> opt = customEnvironments.stream()
                .filter(e -> id.equals(e.getId()))
                .findFirst();
        if (opt.isPresent()) {
            opt.get().setDisplayName(newDisplayName != null ? newDisplayName.trim() : "");
            return true;
        }
        return false;
    }

    /** Removes a custom environment by id, together with its color. Returns false if built-in or not found. */
    public boolean removeCustomEnvironment(String id) {
        if (isBuiltIn(id)) return false;
        boolean removed = customEnvironments.removeIf(e -> id.equals(e.getId()));
        if (removed) {
            colors.remove(id);
        }
        return removed;
    }

    /**
     * The tab color of an environment as {@code #RRGGBB}, or {@code null} when it has none: the
     * terminal tabs of connections whose stored credential belongs to the environment show it,
     * unless the connection has a tab color of its own.
     */
    public String getColor(String environmentId) {
        return environmentId != null ? colors.get(environmentId) : null;
    }

    /**
     * Sets or, with {@code null} or a value that is not a hex color, removes the tab color of an
     * existing environment. An id that names no environment is ignored.
     *
     * @return whether the environment now has a color
     */
    public boolean setColor(String environmentId, String color) {
        if (!exists(environmentId)) {
            return false;
        }
        String normalized = ConnectionColorSupport.normalizeHex(color);
        if (normalized == null) {
            colors.remove(environmentId);
            return false;
        }
        colors.put(environmentId, normalized);
        return true;
    }

    /** The tab colors by environment id, as a copy; environments without a color are absent. */
    public Map<String, String> getColors() {
        return new LinkedHashMap<>(colors);
    }

    /**
     * Gives every environment the color {@code newColors} names for it and removes the color of
     * the others, as the Environments dialog does on OK. Ids that name no environment and values
     * that are not hex colors are ignored.
     *
     * @return the colors before the change, so a failed save can put them back
     */
    public Map<String, String> replaceColors(Map<String, String> newColors) {
        Map<String, String> previous = getColors();
        colors.clear();
        if (newColors != null) {
            newColors.forEach(this::setColor);
        }
        return previous;
    }

    private boolean exists(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            return false;
        }
        return isBuiltIn(environmentId)
            || customEnvironments.stream().anyMatch(e -> environmentId.equals(e.getId()));
    }

    /** The colors as they are written, or {@code null} so a file without colors keeps its old form. */
    private List<EnvironmentColor> colorList() {
        if (colors.isEmpty()) {
            return null;
        }
        List<EnvironmentColor> list = new ArrayList<>();
        colors.forEach((id, color) -> list.add(new EnvironmentColor(id, color)));
        return list;
    }

    @XmlRootElement(name = "environments")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class EnvironmentsWrapper {
        @XmlElement(name = "environment")
        private List<EnvironmentDefinition> environments;

        /** Tab colors of built-in and custom environments; absent in files written before colors existed. */
        @XmlElementWrapper(name = "colors")
        @XmlElement(name = "color")
        private List<EnvironmentColor> colors;

        public List<EnvironmentDefinition> getEnvironments() {
            return environments;
        }

        public void setEnvironments(List<EnvironmentDefinition> environments) {
            this.environments = environments;
        }

        public List<EnvironmentColor> getColors() {
            return colors;
        }

        public void setColors(List<EnvironmentColor> colors) {
            this.colors = colors;
        }
    }
}
