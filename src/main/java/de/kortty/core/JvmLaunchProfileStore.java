package de.kortty.core;

import de.kortty.model.JvmResourceProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tiny, dependency-light store for the selected {@link JvmResourceProfile}, kept in a dedicated
 * one-line properties file under the config dir ({@code ~/.kortty/jvm-launch.properties}).
 *
 * <p>This mirror exists because {@code de.kortty.JvmRelauncher} must read the profile at the very
 * start of {@code main}, before any logging/JavaFX/JAXB machinery is touched, and a 1-line
 * {@link Properties} read is far cheaper and more robust on that critical path than parsing the
 * full {@code global-settings.xml}. {@code GlobalSettings} remains the source of truth for the
 * settings dialog; this file is (re)written whenever the setting is saved.</p>
 */
public final class JvmLaunchProfileStore {

    static final String FILE_NAME = "jvm-launch.properties";
    private static final String KEY = "jvmResourceProfile";

    private JvmLaunchProfileStore() {
    }

    /** Reads the persisted profile, defaulting to {@link JvmResourceProfile#BALANCED} on any problem. */
    public static JvmResourceProfile read(Path configDir) {
        if (configDir == null) {
            return JvmResourceProfile.BALANCED;
        }
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return JvmResourceProfile.BALANCED;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | RuntimeException e) {
            return JvmResourceProfile.BALANCED;
        }
        return JvmResourceProfile.fromName(props.getProperty(KEY));
    }

    /**
     * Writes the profile. BALANCED deletes the file so the relaunch path short-circuits with no
     * I/O. Never throws — a persistence failure must not break saving settings.
     */
    public static void write(Path configDir, JvmResourceProfile profile) {
        if (configDir == null) {
            return;
        }
        Path file = configDir.resolve(FILE_NAME);
        try {
            if (profile == null || profile == JvmResourceProfile.BALANCED) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(configDir);
            Properties props = new Properties();
            props.setProperty(KEY, profile.name());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "korTTY JVM resource profile (applied at next launch)");
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort mirror; GlobalSettings XML remains the source of truth.
        }
    }
}
