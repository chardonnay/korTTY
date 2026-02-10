package de.kortty.teamwork;

import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import de.kortty.model.SSHTunnel;
import de.kortty.model.JumpServer;
import de.kortty.model.AuthMethod;
import de.kortty.model.TunnelType;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Persists teamwork connection cache to disk for fast offline access.
 */
public class TeamworkCacheRepository {

    private static final Logger logger = LoggerFactory.getLogger(TeamworkCacheRepository.class);
    private static final String CACHE_FILE = "teamwork-cache.xml";

    private final Path configDir;

    public TeamworkCacheRepository(Path configDir) {
        this.configDir = configDir;
    }

    @SuppressWarnings("unused")
    @XmlRootElement(name = "teamworkCache")
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = { "cachedSources" })
    public static class CacheWrapper {
        @XmlElementWrapper(name = "sources")
        @XmlElement(name = "source")
        private List<CachedTeamworkSource> cachedSources = new ArrayList<>();

        public List<CachedTeamworkSource> getCachedSources() {
            return cachedSources;
        }

        public void setCachedSources(List<CachedTeamworkSource> cachedSources) {
            this.cachedSources = cachedSources;
        }
    }

    public List<CachedTeamworkSource> loadCache() {
        Path file = configDir.resolve(CACHE_FILE);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JAXBContext context = JAXBContext.newInstance(
                CacheWrapper.class,
                CachedTeamworkSource.class,
                ServerConnection.class,
                de.kortty.model.ConnectionSource.class,
                SSHTunnel.class,
                JumpServer.class,
                AuthMethod.class,
                TunnelType.class,
                de.kortty.model.TerminalLogConfig.class,
                de.kortty.model.TerminalLogConfig.LogFormat.class
            );
            Unmarshaller unmarshaller = context.createUnmarshaller();
            try (InputStream in = Files.newInputStream(file)) {
                CacheWrapper wrapper = (CacheWrapper) unmarshaller.unmarshal(in);
                List<CachedTeamworkSource> list = wrapper.getCachedSources();
                return list != null ? list : new ArrayList<>();
            }
        } catch (Exception e) {
            logger.warn("Failed to load teamwork cache, using empty", e);
            return new ArrayList<>();
        }
    }

    public void saveCache(List<CachedTeamworkSource> cached) {
        Path file = configDir.resolve(CACHE_FILE);
        try {
            JAXBContext context = JAXBContext.newInstance(
                CacheWrapper.class,
                CachedTeamworkSource.class,
                ServerConnection.class,
                ConnectionSource.class,
                SSHTunnel.class,
                JumpServer.class,
                AuthMethod.class,
                TunnelType.class,
                de.kortty.model.TerminalLogConfig.class,
                de.kortty.model.TerminalLogConfig.LogFormat.class
            );
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            CacheWrapper wrapper = new CacheWrapper();
            wrapper.setCachedSources(cached != null ? cached : new ArrayList<>());
            try (OutputStream out = Files.newOutputStream(file)) {
                marshaller.marshal(wrapper, out);
            }
            logger.debug("Saved teamwork cache with {} sources", cached != null ? cached.size() : 0);
        } catch (Exception e) {
            logger.error("Failed to save teamwork cache", e);
        }
    }

    /**
     * Returns all connections from cache across all sources (merged list).
     * Each connection already has connectionSource and teamworkSourceId set.
     */
    public List<ServerConnection> loadMergedConnectionsFromCache(List<CachedTeamworkSource> cached) {
        if (cached == null) return new ArrayList<>();
        return cached.stream()
            .flatMap(s -> s.getConnections().stream())
            .collect(Collectors.toList());
    }
}
