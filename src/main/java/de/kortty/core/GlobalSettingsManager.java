package de.kortty.core;

import de.kortty.model.GlobalSettings;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private long loadedSettingsLastModifiedMillis;
    private de.kortty.policy.PolicyClamp policyClamp;

    public GlobalSettingsManager(Path configDir) {
        this.configDir = configDir;
        this.settings = new GlobalSettings();
    }

    /**
     * Installs the enterprise-policy clamp. Every subsequent load (including
     * {@link #reloadIfChanged()}) re-applies the forced values, so a hand-edited
     * {@code global-settings.xml} can never override a policy-managed setting.
     */
    public synchronized void setPolicyClamp(de.kortty.policy.PolicyClamp policyClamp) {
        this.policyClamp = policyClamp;
        if (policyClamp != null) {
            policyClamp.apply(settings);
        }
    }

    /**
     * Loads settings from XML file.
     */
    public synchronized void load() throws Exception {
        Path settingsFile = settingsFile();

        if (!Files.exists(settingsFile)) {
            logger.info("Settings file not found, using defaults");
            this.settings = new GlobalSettings();
            this.loadedSettingsLastModifiedMillis = 0L;
            applyPolicyClamp();
            return;
        }
        
        long lastModifiedMillis = lastModifiedMillis(settingsFile);
        try {
            // Include all nested classes in context
            JAXBContext context = JAXBContext.newInstance(
                GlobalSettings.class,
                de.kortty.model.AiProfile.class,
                de.kortty.model.AiSkill.class,
                de.kortty.model.AiSkillBuiltinBaseline.class,
                de.kortty.model.AiSkillTarget.class,
                de.kortty.model.ConnectionSettings.class,
                de.kortty.model.SnippetEditorProfile.class,
                de.kortty.model.TerminalRecordingFormat.class,
                de.kortty.model.TerminalRecordingScope.class,
                de.kortty.model.WindowGeometry.class,
                de.kortty.model.TeamworkSourceConfig.class,
                de.kortty.model.TeamworkSourceType.class
            );
            Unmarshaller unmarshaller = context.createUnmarshaller();
            this.settings = (GlobalSettings) unmarshaller.unmarshal(settingsFile.toFile());
            this.settings.initializeAiConfiguration();
            logger.info("Loaded global settings from {} - language: '{}'", settingsFile, this.settings.getLanguage());
        } catch (Exception e) {
            logger.error("Failed to load settings, using defaults", e);
            this.settings = new GlobalSettings();
        } finally {
            this.loadedSettingsLastModifiedMillis = lastModifiedMillis;
            applyPolicyClamp();
        }
    }
    
    /**
     * Saves settings to XML file.
     */
    public synchronized void save() throws Exception {
        Path settingsFile = settingsFile();
        
        // Include all nested classes in context
        JAXBContext context = JAXBContext.newInstance(
            GlobalSettings.class,
            de.kortty.model.AiProfile.class,
            de.kortty.model.AiSkill.class,
            de.kortty.model.AiSkillBuiltinBaseline.class,
            de.kortty.model.AiSkillTarget.class,
            de.kortty.model.ConnectionSettings.class,
            de.kortty.model.SnippetEditorProfile.class,
            de.kortty.model.TerminalRecordingFormat.class,
            de.kortty.model.TerminalRecordingScope.class,
            de.kortty.model.WindowGeometry.class,
            de.kortty.model.TeamworkSourceConfig.class,
            de.kortty.model.TeamworkSourceType.class
        );
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        if (policyClamp != null) {
            // Re-clamp forced values and swap in filtered lists so policy-managed objects never
            // reach the user XML; the live lists are left untouched (no in-place mutation that a
            // concurrent reader could trip over) and restored right after marshaling.
            de.kortty.policy.PolicyClamp.MarshalScope scope = policyClamp.beforeSave(settings);
            try {
                marshaller.marshal(settings, settingsFile.toFile());
            } finally {
                policyClamp.afterSave(scope);
            }
        } else {
            marshaller.marshal(settings, settingsFile.toFile());
        }
        this.loadedSettingsLastModifiedMillis = lastModifiedMillis(settingsFile);

        logger.info("Saved global settings to {}", settingsFile);
    }
    
    public synchronized boolean reloadIfChanged() throws Exception {
        Path settingsFile = settingsFile();
        long currentLastModifiedMillis = lastModifiedMillis(settingsFile);
        if (currentLastModifiedMillis == loadedSettingsLastModifiedMillis) {
            return false;
        }
        load();
        return true;
    }

    public synchronized GlobalSettings getSettings() {
        return settings;
    }

    private void applyPolicyClamp() {
        if (policyClamp != null) {
            policyClamp.apply(settings);
        }
    }

    private Path settingsFile() {
        return configDir.resolve(SETTINGS_FILE);
    }

    private long lastModifiedMillis(Path settingsFile) throws IOException {
        if (!Files.exists(settingsFile)) {
            return 0L;
        }
        return Files.getLastModifiedTime(settingsFile).toMillis();
    }
}
