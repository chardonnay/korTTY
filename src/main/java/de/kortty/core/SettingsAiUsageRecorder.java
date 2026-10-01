package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link AiUsageRecorder} backed by the global settings: resolves the stored profile by id,
 * adds the usage through {@link AiTokenUsageManager#recordUsage} and schedules a coalesced
 * settings save, so it is safe to call from any thread, including background journal and
 * swarm workers.
 */
public final class SettingsAiUsageRecorder implements AiUsageRecorder {

    private static final Logger logger = LoggerFactory.getLogger(SettingsAiUsageRecorder.class);

    private final Supplier<GlobalSettings> settingsSupplier;
    private final Runnable saveScheduler;
    private final List<Consumer<AiProfile>> listeners = new CopyOnWriteArrayList<>();

    public SettingsAiUsageRecorder(GlobalSettingsManager settingsManager) {
        this(settingsManager::getSettings, settingsManager::scheduleSave);
    }

    SettingsAiUsageRecorder(Supplier<GlobalSettings> settingsSupplier, Runnable saveScheduler) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.saveScheduler = Objects.requireNonNull(saveScheduler, "saveScheduler");
    }

    /** Called with the updated stored profile after each recording; listeners run on the recording thread. */
    public void addListener(Consumer<AiProfile> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<AiProfile> listener) {
        listeners.remove(listener);
    }

    @Override
    public void record(AiProfile profile, AiTokenUsage usage) {
        if (profile == null || profile.getId() == null || usage == null || usage.totalTokens() <= 0) {
            return;
        }
        GlobalSettings settings = settingsSupplier.get();
        if (settings == null || settings.getAiProfiles() == null) {
            return;
        }
        AiProfile stored = settings.getAiProfiles().stream()
            .filter(candidate -> candidate != null && profile.getId().equals(candidate.getId()))
            .findFirst()
            .orElse(null);
        if (stored == null) {
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.recordUsage(stored, usage);
        logger.debug("Recorded {} AI tokens for profile {} (period total {})",
            usage.totalTokens(), stored.getName(), snapshot.usedTotalTokens());
        try {
            saveScheduler.run();
        } catch (RuntimeException e) {
            logger.warn("Could not schedule saving AI token usage: {}", e.getMessage());
        }
        for (Consumer<AiProfile> listener : listeners) {
            try {
                listener.accept(stored);
            } catch (RuntimeException e) {
                logger.debug("AI usage listener failed: {}", e.getMessage());
            }
        }
    }
}
