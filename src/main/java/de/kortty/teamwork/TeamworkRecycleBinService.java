package de.kortty.teamwork;

import de.kortty.model.ServerConnection;
import de.kortty.model.ConnectionSource;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds teamwork connections that the user has "deleted" locally so they can be restored.
 * Does not remove them from the remote source.
 */
public class TeamworkRecycleBinService {

    private static final Logger logger = LoggerFactory.getLogger(TeamworkRecycleBinService.class);
    private static final String RECYCLE_FILE = "teamwork-recycle-bin.xml";

    private static final Class<?>[] JAXB_CLASSES = {
        RecycleWrapper.class,
        ServerConnection.class,
        de.kortty.model.ConnectionSettings.class,
        de.kortty.model.WindowGeometry.class,
        ConnectionSource.class,
        SSHTunnel.class,
        JumpServer.class,
        AuthMethod.class,
        TunnelType.class,
        de.kortty.model.TerminalLogConfig.class,
        de.kortty.model.SessionJournalConfig.class
    };

    private static final JAXBContext JAXB_CTX;
    static {
        try {
            JAXB_CTX = JAXBContext.newInstance(JAXB_CLASSES);
        } catch (jakarta.xml.bind.JAXBException e) {
            throw new IllegalStateException("Failed to create JAXB context for teamwork recycle bin", e);
        }
    }

    private final Path configDir;
    private final Object lock = new Object();
    private final List<ServerConnection> deleted = new ArrayList<>();

    public TeamworkRecycleBinService(Path configDir) {
        this.configDir = configDir;
    }

    public void load() {
        Path file = configDir.resolve(RECYCLE_FILE);
        synchronized (lock) {
            if (!Files.exists(file)) {
                deleted.clear();
                return;
            }
            try {
                Unmarshaller unmarshaller = JAXB_CTX.createUnmarshaller();
                try (InputStream in = Files.newInputStream(file)) {
                    RecycleWrapper w = (RecycleWrapper) unmarshaller.unmarshal(in);
                    List<ServerConnection> loaded = w.getConnections() != null ? w.getConnections() : new ArrayList<>();
                    deleted.clear();
                    deleted.addAll(loaded);
                }
            } catch (Exception e) {
                logger.warn("Failed to load teamwork recycle bin", e);
                deleted.clear();
            }
        }
    }

    public void save() {
        Path file = configDir.resolve(RECYCLE_FILE);
        List<ServerConnection> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(deleted);
        }
        Path tempFile = null;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tempFile = parent != null
                ? Files.createTempFile(parent, "teamwork-recycle-bin-", ".xml")
                : Files.createTempFile("teamwork-recycle-bin-", ".xml");
            Marshaller marshaller = JAXB_CTX.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            RecycleWrapper w = new RecycleWrapper();
            w.setConnections(snapshot);
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                marshaller.marshal(w, out);
            }
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;
        } catch (Exception e) {
            logger.error("Failed to save teamwork recycle bin", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ex) {
                    logger.warn("Failed to delete temp recycle bin file", ex);
                }
            }
        }
    }

    public void addDeleted(ServerConnection connection) {
        if (connection == null || !connection.isTeamworkConnection()) return;
        synchronized (lock) {
            deleted.add(connection);
            save();
        }
    }

    public List<ServerConnection> getDeleted() {
        synchronized (lock) {
            return new ArrayList<>(deleted);
        }
    }

    public void restore(String connectionId) {
        synchronized (lock) {
            deleted.removeIf(c -> java.util.Objects.equals(c.getId(), connectionId));
            save();
        }
    }

    /** Returns the set of connection IDs that are in the recycle bin (hidden from main list). */
    public Set<String> getDeletedIds() {
        synchronized (lock) {
            return deleted.stream().map(ServerConnection::getId).collect(Collectors.toSet());
        }
    }

    @SuppressWarnings("unused")
    @XmlRootElement(name = "teamworkRecycleBin")
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = { "connections" })
    public static class RecycleWrapper {
        @XmlElementWrapper(name = "connections")
        @XmlElement(name = "connection")
        private List<ServerConnection> connections = new ArrayList<>();

        public List<ServerConnection> getConnections() {
            return connections;
        }

        public void setConnections(List<ServerConnection> connections) {
            this.connections = connections != null ? connections : new ArrayList<>();
        }
    }
}
