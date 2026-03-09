package de.kortty.core;

import de.kortty.model.EnvironmentDefinition;
import de.kortty.model.StoredCredential;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
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
 * Manages user-defined environments for credentials.
 * Built-in environments (PRODUCTION, DEVELOPMENT, TEST, STAGING) are always present;
 * custom environments are persisted in environments.xml.
 */
public class EnvironmentManager {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentManager.class);
    private static final String ENVIRONMENTS_FILE = "environments.xml";

    private final Path configDir;
    private final List<EnvironmentDefinition> customEnvironments = new ArrayList<>();

    public EnvironmentManager(Path configDir) {
        this.configDir = configDir;
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

    public void load() {
        customEnvironments.clear();
        Path file = configDir.resolve(ENVIRONMENTS_FILE);
        if (!Files.exists(file)) {
            logger.debug("No environments file found, using built-in only");
            return;
        }
        try {
            JAXBContext context = JAXBContext.newInstance(EnvironmentsWrapper.class, EnvironmentDefinition.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            EnvironmentsWrapper wrapper = (EnvironmentsWrapper) unmarshaller.unmarshal(file.toFile());
            if (wrapper.getEnvironments() != null) {
                customEnvironments.addAll(wrapper.getEnvironments());
            }
            logger.info("Loaded {} custom environments from {}", customEnvironments.size(), file);
        } catch (Exception e) {
            logger.warn("Failed to load environments, using built-in only: {}", e.getMessage());
        }
    }

    public void save() throws Exception {
        Path file = configDir.resolve(ENVIRONMENTS_FILE);
        try {
            JAXBContext context = JAXBContext.newInstance(EnvironmentsWrapper.class, EnvironmentDefinition.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            EnvironmentsWrapper wrapper = new EnvironmentsWrapper();
            wrapper.setEnvironments(new ArrayList<>(customEnvironments));
            Files.createDirectories(configDir);
            marshaller.marshal(wrapper, file.toFile());
            logger.info("Saved {} custom environments to {}", customEnvironments.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save environments", e);
            throw e;
        }
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
