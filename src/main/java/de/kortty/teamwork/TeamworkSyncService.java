package de.kortty.teamwork;

import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import de.kortty.core.GlobalSettingsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Fetches teamwork connections from configured sources, caches them locally,
 * and notifies listeners when the cache is updated.
 */
public class TeamworkSyncService {

    private static final Logger logger = LoggerFactory.getLogger(TeamworkSyncService.class);

    private final Path configDir;
    private final GlobalSettingsManager globalSettingsManager;
    private final TeamworkCacheRepository cacheRepository;
    private final GitTeamworkAdapter gitAdapter;
    private final SharedFileTeamworkAdapter sharedFileAdapter;

    private volatile List<CachedTeamworkSource> cache = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public TeamworkSyncService(Path configDir, GlobalSettingsManager globalSettingsManager) {
        this.configDir = configDir;
        this.globalSettingsManager = globalSettingsManager;
        this.cacheRepository = new TeamworkCacheRepository(configDir);
        this.gitAdapter = new GitTeamworkAdapter(configDir);
        this.sharedFileAdapter = new SharedFileTeamworkAdapter(configDir);
    }

    /**
     * Load cache from disk and start background sync.
     */
    public void start() {
        cache = cacheRepository.loadCache();
        scheduleSync();
    }

    /**
     * Stop background sync.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Returns all teamwork connections from cache (merged from all sources).
     */
    public List<ServerConnection> getTeamworkConnections() {
        return cacheRepository.loadMergedConnectionsFromCache(cache);
    }

    /**
     * Run sync once immediately (e.g. after user changed sources).
     */
    public void syncNow() {
        runSync();
    }

    public void addCacheUpdateListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeCacheUpdateListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void scheduleSync() {
        int intervalMinutes = globalSettingsManager.getSettings().getTeamworkDefaultCheckIntervalMinutes();
        if (intervalMinutes < 1) intervalMinutes = 15;
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TeamworkSync");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runSync, 0, intervalMinutes, TimeUnit.MINUTES);
    }

    private void runSync() {
        List<TeamworkSourceConfig> sources = globalSettingsManager.getSettings().getTeamworkSources().stream()
            .filter(TeamworkSourceConfig::isEnabled)
            .collect(Collectors.toList());
        if (sources.isEmpty()) {
            cache = new ArrayList<>();
            cacheRepository.saveCache(cache);
            notifyListeners();
            return;
        }
        long now = System.currentTimeMillis();
        List<CachedTeamworkSource> newCache = new ArrayList<>();
        for (TeamworkSourceConfig source : sources) {
            TeamworkLoadResult result = loadFromSource(source);
            if (result != null) {
                newCache.add(new CachedTeamworkSource(
                    source.getId(),
                    now,
                    result.getVersionToken(),
                    result.getConnections()
                ));
            } else {
                // Keep previous cache for this source if load failed
                cache.stream()
                    .filter(c -> c.getSourceId().equals(source.getId()))
                    .findFirst()
                    .ifPresent(newCache::add);
            }
        }
        cache = newCache;
        cacheRepository.saveCache(cache);
        notifyListeners();
    }

    private TeamworkLoadResult loadFromSource(TeamworkSourceConfig source) {
        if (source.getType() == TeamworkSourceType.GIT) {
            return gitAdapter.loadConnections(source);
        }
        if (source.getType() == TeamworkSourceType.SHARED_FILE) {
            return sharedFileAdapter.loadConnections(source);
        }
        return null;
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception e) {
                logger.warn("Teamwork cache listener error", e);
            }
        }
    }
}
