package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.perf.PerfTrace;
import jakarta.xml.bind.JAXBException;
import javafx.application.Platform;

import java.io.ByteArrayOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    /** Several dialogs save on close within one user action; their writes are merged into one. */
    static final long SAVE_COALESCE_MILLIS = 300;
    private static final long MARSHAL_TIMEOUT_MILLIS = 5000;

    /**
     * Shared, thread-safe JAXBContext for the settings graph. Building it is the expensive part of
     * a save (annotation scan over 15 classes: ~25 ms warm, 100+ ms in a cold JVM) and it used to be
     * rebuilt on every load and save — i.e. on every dialog close, on the FX thread.
     */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(
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
                de.kortty.model.NamedWindowGeometry.class,
                de.kortty.model.TeamworkSourceConfig.class,
                de.kortty.model.TeamworkSourceType.class,
                de.kortty.model.SessionJournalMarkerDefinition.class,
                de.kortty.model.SessionJournalMarkerRule.class
            );
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** One daemon thread writes coalesced saves; the marshal itself still happens on the owner thread. */
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kortty-settings-writer");
        thread.setDaemon(true);
        return thread;
    });
    
    private final Path configDir;
    private GlobalSettings settings;
    private long loadedSettingsLastModifiedMillis;
    private de.kortty.policy.PolicyClamp policyClamp;
    private ScheduledFuture<?> pendingSave;
    private int writeCount;

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
            // No settings file at all: this is a first installation, not an update, so the
            // first-install defaults apply (see GlobalSettings.forFreshInstall()).
            logger.info("Settings file not found, using first-install defaults");
            this.settings = GlobalSettings.forFreshInstall();
            this.loadedSettingsLastModifiedMillis = 0L;
            applyPolicyClamp();
            return;
        }
        
        long lastModifiedMillis = lastModifiedMillis(settingsFile);
        try {
            Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
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
     * Saves the settings synchronously: the durable path for explicit user actions (Settings
     * "Save", master-password flows, shutdown). A save that was merely scheduled is superseded.
     */
    public synchronized void save() throws Exception {
        cancelPendingSave();
        PerfTrace.Span perf = PerfTrace.begin("GlobalSettingsManager.save");
        byte[] bytes = marshalToBytes();
        perf.mark("marshal");
        writeBytes(bytes);
        perf.mark("write").end();
        logger.info("Saved global settings to {}", settingsFile());
    }

    /**
     * Saves soon, off the FX thread, merging every request within {@link #SAVE_COALESCE_MILLIS}
     * into one file write. For bookkeeping writes that must not stall the UI — window geometry on
     * every dialog close — where losing the last few hundred milliseconds on a crash is acceptable.
     * {@link #flushPendingSave()} makes the result durable at shutdown.
     */
    public synchronized void scheduleSave() {
        cancelPendingSave();
        pendingSave = WRITER.schedule(this::runScheduledSave, SAVE_COALESCE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Writes a scheduled save now, on the calling thread; a no-op when nothing is pending. */
    public void flushPendingSave() {
        boolean pending;
        synchronized (this) {
            pending = pendingSave != null && !pendingSave.isDone();
            cancelPendingSave();
        }
        if (pending) {
            try {
                save();
            } catch (Exception e) {
                logger.warn("Could not flush the pending settings save: {}", e.getMessage());
            }
        }
    }

    private void cancelPendingSave() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
            pendingSave = null;
        }
    }

    private void runScheduledSave() {
        try {
            byte[] bytes = marshalOnOwnerThread();
            if (bytes != null) {
                writeBytes(bytes);
                logger.debug("Saved global settings to {} (scheduled)", settingsFile());
            }
        } catch (Exception e) {
            logger.warn("Scheduled settings save failed: {}", e.getMessage());
        }
    }

    /**
     * The settings object is mutated on the FX thread, so the snapshot is taken there whenever the
     * toolkit is running (a few milliseconds with the cached context); only the file write stays on
     * the writer thread. Without a toolkit (tests, headless) the marshal runs inline.
     */
    private byte[] marshalOnOwnerThread() throws Exception {
        if (Platform.isFxApplicationThread()) {
            return marshalToBytes();
        }
        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                try {
                    result.set(marshalToBytes());
                } catch (Exception e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            });
        } catch (IllegalStateException toolkitNotRunning) {
            return marshalToBytes();
        }
        if (!done.await(MARSHAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("FX thread did not marshal the settings within " + MARSHAL_TIMEOUT_MILLIS + " ms");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private synchronized byte[] marshalToBytes() throws Exception {
        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (policyClamp != null) {
            // Re-clamp forced values and swap in filtered lists so policy-managed objects never
            // reach the user XML; the live lists are left untouched (no in-place mutation that a
            // concurrent reader could trip over) and restored right after marshaling.
            de.kortty.policy.PolicyClamp.MarshalScope scope = policyClamp.beforeSave(settings);
            try {
                marshaller.marshal(settings, out);
            } finally {
                policyClamp.afterSave(scope);
            }
        } else {
            marshaller.marshal(settings, out);
        }
        return out.toByteArray();
    }

    /** Atomic replace via a sibling temp file, so a crash mid-write never leaves a truncated settings file. */
    private synchronized void writeBytes(byte[] bytes) throws IOException {
        Path settingsFile = settingsFile();
        Files.createDirectories(settingsFile.getParent());
        Path temp = settingsFile.resolveSibling(SETTINGS_FILE + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        }
        this.loadedSettingsLastModifiedMillis = lastModifiedMillis(settingsFile);
        writeCount++;
    }

    /** Number of file writes so far (tests). */
    synchronized int writeCount() {
        return writeCount;
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
