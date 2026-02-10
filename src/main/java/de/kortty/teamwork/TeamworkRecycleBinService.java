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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Holds teamwork connections that the user has "deleted" locally so they can be restored.
 * Does not remove them from the remote source.
 */
public class TeamworkRecycleBinService {

    private static final Logger logger = LoggerFactory.getLogger(TeamworkRecycleBinService.class);
    private static final String RECYCLE_FILE = "teamwork-recycle-bin.xml";

    private final Path configDir;
    private volatile List<ServerConnection> deleted = new CopyOnWriteArrayList<>();

    public TeamworkRecycleBinService(Path configDir) {
        this.configDir = configDir;
    }

    public void load() {
        Path file = configDir.resolve(RECYCLE_FILE);
        if (!Files.exists(file)) {
            deleted = new CopyOnWriteArrayList<>();
            return;
        }
        try {
            JAXBContext context = JAXBContext.newInstance(
                RecycleWrapper.class,
                ServerConnection.class,
                ConnectionSource.class,
                SSHTunnel.class,
                JumpServer.class,
                AuthMethod.class,
                TunnelType.class,
                de.kortty.model.TerminalLogConfig.class,
                de.kortty.model.TerminalLogConfig.LogFormat.class
            );
            Unmarshaller unmarshaller = context.createUnmarshaller();
            try (InputStream in = Files.newInputStream(file)) {
                RecycleWrapper w = (RecycleWrapper) unmarshaller.unmarshal(in);
                List<ServerConnection> loaded = w.getConnections() != null ? w.getConnections() : new ArrayList<>();
                deleted = new CopyOnWriteArrayList<>(loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load teamwork recycle bin", e);
            deleted = new CopyOnWriteArrayList<>();
        }
    }

    public void save() {
        Path file = configDir.resolve(RECYCLE_FILE);
        try {
            JAXBContext context = JAXBContext.newInstance(
                RecycleWrapper.class,
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
            RecycleWrapper w = new RecycleWrapper();
            w.setConnections(deleted);
            try (OutputStream out = Files.newOutputStream(file)) {
                marshaller.marshal(w, out);
            }
        } catch (Exception e) {
            logger.error("Failed to save teamwork recycle bin", e);
        }
    }

    public void addDeleted(ServerConnection connection) {
        if (connection == null || !connection.isTeamworkConnection()) return;
        deleted.add(connection);
        save();
    }

    public List<ServerConnection> getDeleted() {
        return new ArrayList<>(deleted);
    }

    public void restore(String connectionId) {
        deleted.removeIf(c -> c.getId().equals(connectionId));
        save();
    }

    /** Returns the set of connection IDs that are in the recycle bin (hidden from main list). */
    public Set<String> getDeletedIds() {
        return deleted.stream().map(ServerConnection::getId).collect(Collectors.toSet());
    }

    @SuppressWarnings("unused")
    @XmlRootElement(name = "teamworkRecycleBin")
    @XmlType(propOrder = { "connections" })
    public static class RecycleWrapper {
        @XmlElementWrapper(name = "connections")
        @jakarta.xml.bind.annotation.XmlElement(name = "connection")
        private List<ServerConnection> connections = new ArrayList<>();

        public List<ServerConnection> getConnections() {
            return connections;
        }

        public void setConnections(List<ServerConnection> connections) {
            this.connections = connections != null ? connections : new ArrayList<>();
        }
    }
}
