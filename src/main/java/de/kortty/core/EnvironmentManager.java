package de.kortty.core;

import de.kortty.model.EnvironmentDefinition;
import de.kortty.model.StoredCredential;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages user-defined environments for credentials.
 * Built-in environments (PRODUCTION, DEVELOPMENT, TEST, STAGING) are always present;
 * custom environments are persisted in environments.xml.
 */
public class EnvironmentManager {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentManager.class);
    public static final String ENVIRONMENTS_FILE = "environments.xml";

    /** Shared, thread-safe JAXBContext; building one per load and save is the expensive part. */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(EnvironmentsWrapper.class, EnvironmentDefinition.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Path configDir;
    private final List<EnvironmentDefinition> customEnvironments = new ArrayList<>();
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
        if (!Files.exists(file)) {
            customEnvironments.clear();
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
            logger.info("Loaded {} custom environments from {}", customEnvironments.size(), file);
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

    /** Removes a custom environment by id. Returns false if built-in or not found. */
    public boolean removeCustomEnvironment(String id) {
        if (isBuiltIn(id)) return false;
        return customEnvironments.removeIf(e -> id.equals(e.getId()));
    }

    @XmlRootElement(name = "environments")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class EnvironmentsWrapper {
        @XmlElement(name = "environment")
        private List<EnvironmentDefinition> environments;

        public List<EnvironmentDefinition> getEnvironments() {
            return environments;
        }

        public void setEnvironments(List<EnvironmentDefinition> environments) {
            this.environments = environments;
        }
    }
}
