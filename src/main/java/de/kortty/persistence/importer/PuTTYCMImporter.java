package de.kortty.persistence.importer;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports connections from PuTTY Connection Manager (puttycm) CSV format.
 * 
 * Expected format:
 * Name,Protocol,Host,Port,Username,Group,LocalTunnels,RemoteTunnels,DynamicTunnels,Comment
 * 
 * LocalTunnels format: localport:remotehost:remoteport (multiple separated by ;)
 * RemoteTunnels format: remoteport:localhost:localport (multiple separated by ;)
 * DynamicTunnels format: localport (multiple separated by ;)
 * Group format: Windows path with backslashes (will be converted to forward slashes)
 */
public class PuTTYCMImporter implements ConnectionImporter {
    
    private static final Logger logger = LoggerFactory.getLogger(PuTTYCMImporter.class);
    
    @Override
    public String getName() {
        return "PuTTY Connection Manager";
    }
    
    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"csv"};
    }
    
    @Override
    public String getFileDescription() {
        return "PuTTY CM CSV Export (*.csv)";
    }
    
    @Override
    public boolean canImport(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        
        String fileName = file.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".csv")) {
            return false;
        }
        
        // Check if the CSV file has the expected header
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine != null) {
                // Skip BOM if present
                if (firstLine.startsWith("\ufeff")) {
                    firstLine = firstLine.substring(1);
                }
                
                // Check for PuTTY CM header format
                return firstLine.toLowerCase().contains("name") && 
                       firstLine.toLowerCase().contains("protocol") && 
                       firstLine.toLowerCase().contains("host");
            }
        } catch (IOException e) {
            return false;
        }
        
        return false;
    }
    
    @Override
    public List<ServerConnection> importConnections(Path file) throws Exception {
        List<ServerConnection> connections = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            
            // Skip BOM if present
            if (line != null && line.startsWith("\ufeff")) {
                line = line.substring(1);
            }
            
            // Skip header line
            if (line != null && line.toLowerCase().contains("name")) {
                line = reader.readLine();
            }
            
            int lineNumber = 2;
            while (line != null) {
                line = line.trim();
                
                if (!line.isEmpty()) {
                    try {
                        ServerConnection conn = parseCsvLine(line, lineNumber);
                        if (conn != null) {
                            connections.add(conn);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to parse line {}: {}", lineNumber, e.getMessage());
                    }
                }
                
                line = reader.readLine();
                lineNumber++;
            }
        }
        
        logger.info("Imported {} connections from PuTTY CM format: {}", connections.size(), file);
        return connections;
    }
    
    /**
     * Parses a CSV line and creates a ServerConnection.
     */
    private ServerConnection parseCsvLine(String line, int lineNumber) throws Exception {
        List<String> fields = parseCsvFields(line);
        
        // Expected format: Name,Protocol,Host,Port,Username,Group,LocalTunnels,RemoteTunnels,DynamicTunnels,Comment
        if (fields.size() < 4) {
            throw new Exception("Invalid CSV format: expected at least 4 fields");
        }
        
        String name = fields.get(0);
        String protocol = fields.get(1);
        String host = fields.get(2);
        int port = 22;
        
        try {
            port = Integer.parseInt(fields.get(3));
        } catch (NumberFormatException e) {
            logger.warn("Invalid port number at line {}, using default 22", lineNumber);
        }
        
        // Only import SSH connections
        if (!protocol.equalsIgnoreCase("SSH")) {
            logger.debug("Skipping non-SSH connection: {} (protocol: {})", name, protocol);
            return null;
        }
        
        ServerConnection conn = new ServerConnection();
        conn.setName(name);
        conn.setHost(host);
        conn.setPort(port);
        conn.setAuthMethod(AuthMethod.PASSWORD); // Default, user can change later
        
        // Username (field 4)
        if (fields.size() > 4 && !fields.get(4).isEmpty()) {
            conn.setUsername(fields.get(4));
        }
        
        // Group (field 5) - convert Windows path to Unix path
        if (fields.size() > 5 && !fields.get(5).isEmpty()) {
            String group = fields.get(5);
            // Convert backslashes to forward slashes
            group = group.replace("\\", "/");
            conn.setGroup(group);
        }
        
        // Parse tunnels
        List<SSHTunnel> tunnels = new ArrayList<>();
        
        // Local tunnels (field 6)
        if (fields.size() > 6 && !fields.get(6).isEmpty()) {
            tunnels.addAll(parseLocalTunnels(fields.get(6)));
        }
        
        // Remote tunnels (field 7)
        if (fields.size() > 7 && !fields.get(7).isEmpty()) {
            tunnels.addAll(parseRemoteTunnels(fields.get(7)));
        }
        
        // Dynamic tunnels (field 8)
        if (fields.size() > 8 && !fields.get(8).isEmpty()) {
            tunnels.addAll(parseDynamicTunnels(fields.get(8)));
        }
        
        if (!tunnels.isEmpty()) {
            conn.setSshTunnels(tunnels);
        }
        
        return conn;
    }
    
    /**
     * Parses local tunnels from the format: localport:remotehost:remoteport
     */
    private List<SSHTunnel> parseLocalTunnels(String tunnelString) {
        List<SSHTunnel> tunnels = new ArrayList<>();
        
        for (String tunnelSpec : tunnelString.split(";")) {
            tunnelSpec = tunnelSpec.trim();
            if (tunnelSpec.isEmpty()) {
                continue;
            }
            
            String[] parts = tunnelSpec.split(":");
            if (parts.length == 3) {
                try {
                    SSHTunnel tunnel = new SSHTunnel();
                    tunnel.setType(TunnelType.LOCAL);
                    tunnel.setLocalHost("localhost");
                    tunnel.setLocalPort(Integer.parseInt(parts[0]));
                    tunnel.setRemoteHost(parts[1]);
                    tunnel.setRemotePort(Integer.parseInt(parts[2]));
                    tunnel.setEnabled(true);
                    tunnels.add(tunnel);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid local tunnel format: {}", tunnelSpec);
                }
            }
        }
        
        return tunnels;
    }
    
    /**
     * Parses remote tunnels from the format: remoteport:localhost:localport
     */
    private List<SSHTunnel> parseRemoteTunnels(String tunnelString) {
        List<SSHTunnel> tunnels = new ArrayList<>();
        
        for (String tunnelSpec : tunnelString.split(";")) {
            tunnelSpec = tunnelSpec.trim();
            if (tunnelSpec.isEmpty()) {
                continue;
            }
            
            String[] parts = tunnelSpec.split(":");
            if (parts.length == 3) {
                try {
                    SSHTunnel tunnel = new SSHTunnel();
                    tunnel.setType(TunnelType.REMOTE);
                    tunnel.setRemoteHost("0.0.0.0"); // Listen on all interfaces
                    tunnel.setRemotePort(Integer.parseInt(parts[0]));
                    tunnel.setLocalHost(parts[1]);
                    tunnel.setLocalPort(Integer.parseInt(parts[2]));
                    tunnel.setEnabled(true);
                    tunnels.add(tunnel);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid remote tunnel format: {}", tunnelSpec);
                }
            }
        }
        
        return tunnels;
    }
    
    /**
     * Parses dynamic tunnels from the format: localport
     */
    private List<SSHTunnel> parseDynamicTunnels(String tunnelString) {
        List<SSHTunnel> tunnels = new ArrayList<>();
        
        for (String tunnelSpec : tunnelString.split(";")) {
            tunnelSpec = tunnelSpec.trim();
            if (tunnelSpec.isEmpty()) {
                continue;
            }
            
            try {
                SSHTunnel tunnel = new SSHTunnel();
                tunnel.setType(TunnelType.DYNAMIC);
                tunnel.setLocalHost("localhost");
                tunnel.setLocalPort(Integer.parseInt(tunnelSpec));
                tunnel.setEnabled(true);
                tunnels.add(tunnel);
            } catch (NumberFormatException e) {
                logger.warn("Invalid dynamic tunnel format: {}", tunnelSpec);
            }
        }
        
        return tunnels;
    }
    
    /**
     * Parses CSV fields handling quoted values and escaped quotes.
     */
    private List<String> parseCsvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
                    currentField.append('"');
                    i++; // Skip next quote
                } else {
                    // Toggle quote state
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                // Field separator
                fields.add(currentField.toString());
                currentField.setLength(0);
            } else {
                currentField.append(c);
            }
        }
        
        // Add last field
        fields.add(currentField.toString());
        
        return fields;
    }
}
