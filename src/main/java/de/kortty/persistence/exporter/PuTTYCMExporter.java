package de.kortty.persistence.exporter;

import de.kortty.model.ServerConnection;
import de.kortty.model.SSHTunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exports connections to PuTTY Connection Manager (puttycm) format.
 * 
 * PuTTY Connection Manager by RAMESH NATARAJAN stores connections in Windows Registry
 * or as portable database file (puttycm.db or .puttycm).
 * 
 * For cross-platform compatibility, we export to a CSV-like format that can be imported.
 * 
 * Format (CSV):
 * Name,Protocol,Host,Port,Username,Group,LocalTunnels,RemoteTunnels,DynamicTunnels,Comment
 * 
 * Tunnel format: localport:remotehost:remoteport
 */
public class PuTTYCMExporter implements ConnectionExporter {
    
    private static final Logger logger = LoggerFactory.getLogger(PuTTYCMExporter.class);
    
    @Override
    public String getName() {
        return "PuTTY Connection Manager";
    }
    
    @Override
    public String getFileExtension() {
        return "csv";
    }
    
    @Override
    public String getFileDescription() {
        return "PuTTY CM CSV Export (*.csv)";
    }
    
    @Override
    public void exportConnections(List<ServerConnection> connections, Path file) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // Write BOM for Excel compatibility
            writer.write('\ufeff');
            
            // Write header
            writer.write("Name,Protocol,Host,Port,Username,Group,LocalTunnels,RemoteTunnels,DynamicTunnels,Comment");
            writer.newLine();
            
            // Write connections
            for (ServerConnection conn : connections) {
                if (conn.isPlaceholder()) {
                    continue; // Skip placeholder connections
                }
                
                StringBuilder line = new StringBuilder();
                
                // Name (escape quotes)
                line.append(escapeCsv(conn.getName()));
                line.append(",");
                
                // Protocol (always SSH for our connections)
                line.append("SSH");
                line.append(",");
                
                // Host
                line.append(escapeCsv(conn.getHost()));
                line.append(",");
                
                // Port
                line.append(conn.getPort());
                line.append(",");
                
                // Username
                line.append(escapeCsv(conn.getUsername() != null ? conn.getUsername() : ""));
                line.append(",");
                
                // Group (use folder path)
                String group = conn.getGroup();
                if (group != null && !group.trim().isEmpty()) {
                    // Convert KorTTY group path (/) to PuTTY CM format (\)
                    group = group.replace("/", "\\");
                }
                line.append(escapeCsv(group != null ? group : ""));
                line.append(",");
                
                // Tunnels
                String localTunnels = "";
                String remoteTunnels = "";
                String dynamicTunnels = "";
                
                if (conn.getSshTunnels() != null && !conn.getSshTunnels().isEmpty()) {
                    List<String> local = new ArrayList<>();
                    List<String> remote = new ArrayList<>();
                    List<String> dynamic = new ArrayList<>();
                    
                    for (SSHTunnel tunnel : conn.getSshTunnels()) {
                        if (!tunnel.isEnabled()) {
                            continue;
                        }
                        
                        switch (tunnel.getType()) {
                            case LOCAL:
                                // Format: localport:remotehost:remoteport
                                local.add(String.format("%d:%s:%d",
                                    tunnel.getLocalPort(),
                                    tunnel.getRemoteHost(),
                                    tunnel.getRemotePort()));
                                break;
                            case REMOTE:
                                // Format: remoteport:localhost:localport
                                remote.add(String.format("%d:%s:%d",
                                    tunnel.getRemotePort(),
                                    tunnel.getLocalHost(),
                                    tunnel.getLocalPort()));
                                break;
                            case DYNAMIC:
                                // Format: localport (SOCKS proxy)
                                dynamic.add(String.valueOf(tunnel.getLocalPort()));
                                break;
                        }
                    }
                    
                    localTunnels = String.join(";", local);
                    remoteTunnels = String.join(";", remote);
                    dynamicTunnels = String.join(";", dynamic);
                }
                
                line.append(escapeCsv(localTunnels));
                line.append(",");
                line.append(escapeCsv(remoteTunnels));
                line.append(",");
                line.append(escapeCsv(dynamicTunnels));
                line.append(",");
                
                // Comment (use connection ID as reference)
                String comment = "Exported from KorTTY (ID: " + conn.getId() + ")";
                line.append(escapeCsv(comment));
                
                writer.write(line.toString());
                writer.newLine();
            }
            
            logger.info("Exported {} connections to PuTTY CM format: {}", connections.size(), file);
        }
    }
    
    /**
     * Escapes a CSV field value.
     */
    private String escapeCsv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        
        // If the value contains comma, quote, or newline, wrap it in quotes and escape internal quotes
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        
        return value;
    }
}
