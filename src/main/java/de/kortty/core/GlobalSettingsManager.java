package de.kortty.core;

import de.kortty.model.GlobalSettings;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages global application settings persistence.
 */
public class GlobalSettingsManager {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalSettingsManager.class);
    private static final String SETTINGS_FILE = "global-settings.xml";
    
    private final Path configDir;
    private GlobalSettings settings;
    
    public GlobalSettingsManager(Path configDir) {
        this.configDir = configDir;
        this.settings = new GlobalSettings();
    }
    
    /**
     * Loads settings from XML file.
     */
    public void load() throws Exception {
        Path settingsFile = configDir.resolve(SETTINGS_FILE);
        
        if (!Files.exists(settingsFile)) {
            logger.info("Settings file not found, using defaults");
            this.settings = new GlobalSettings();
            return;
        }
        
        try {
            JAXBContext context = JAXBContext.newInstance(GlobalSettings.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            this.settings = (GlobalSettings) unmarshaller.unmarshal(settingsFile.toFile());
            logger.info("Loaded global settings from {}", settingsFile);
        } catch (Exception e) {
            logger.error("Failed to load settings, using defaults", e);
            this.settings = new GlobalSettings();
        }
    }
    
    /**
     * Saves settings to XML file.
     */
    public void save() throws Exception {
        Path settingsFile = configDir.resolve(SETTINGS_FILE);
        
        JAXBContext context = JAXBContext.newInstance(GlobalSettings.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(settings, settingsFile.toFile());
        
        logger.info("Saved global settings to {}", settingsFile);
    }
    
    public GlobalSettings getSettings() {
        return settings;
    }
}
