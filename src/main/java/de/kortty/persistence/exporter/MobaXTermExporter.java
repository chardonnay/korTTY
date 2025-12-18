package de.kortty.persistence.exporter;

import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exports connections to MobaXterm INI format.
 * 
 * Format:
 * [Bookmarks]
 * SubRep=GroupName
 * ImgNum=41
 * servername=#109#0%hostname%22%username%-1%-1%-1%-1%0%0%%%-1%0%0%0%%1080%%0%0%1#MobaFont%10%0%0%-1%15%236,236,236%30,30,30%180,180,192%0%-1%0%%xterm%-1%-1%_Std_Colors_0_%80%24%0%1%-1%<none>%%0%1%-1%-1#0#
 * 
 * #109 = SSH connection type
 */
public class MobaXTermExporter implements ConnectionExporter {
    
    private static final Logger logger = LoggerFactory.getLogger(MobaXTermExporter.class);
    
    @Override
    public String getName() {
        return "MobaXterm";
    }
    
    @Override
    public String getFileExtension() {
        return "mxtsessions";
    }
    
    @Override
    public String getFileDescription() {
        return "MobaXterm Session-Dateien (*.mxtsessions)";
    }
    
    @Override
    public void exportConnections(List<ServerConnection> connections, Path file) throws Exception {
        // Group connections by their group
        Map<String, List<ServerConnection>> groupedConnections = new LinkedHashMap<>();
        
        for (ServerConnection conn : connections) {
            String group = conn.getGroup() != null ? conn.getGroup() : "";
            groupedConnections.computeIfAbsent(group, k -> new ArrayList<>()).add(conn);
        }
        
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            int sectionNum = 0;
            
            for (Map.Entry<String, List<ServerConnection>> entry : groupedConnections.entrySet()) {
                String group = entry.getKey();
                List<ServerConnection> groupConnections = entry.getValue();
                
                // Write section header
                if (sectionNum == 0) {
                    writer.write("[Bookmarks]");
                } else {
                    writer.write("[Bookmarks_" + sectionNum + "]");
                }
                writer.newLine();
                
                // SubRep (group name)
                writer.write("SubRep=" + group);
                writer.newLine();
                
                // ImgNum (icon number, 41 = server icon)
                writer.write("ImgNum=41");
                writer.newLine();
                
                // Write each connection
                for (ServerConnection conn : groupConnections) {
                    String line = formatConnection(conn);
                    writer.write(line);
                    writer.newLine();
                }
                
                writer.newLine();
                sectionNum++;
            }
        }
        
        logger.info("Exported {} connections to MobaXterm format: {}", connections.size(), file);
    }
    
    private String formatConnection(ServerConnection conn) {
        // MobaXterm SSH format:
        // name=#109#0%host%port%user%-1%-1%-1%-1%0%0%%%-1%0%0%0%%1080%%0%0%1#MobaFont%10%0%0%-1%15%236,236,236%30,30,30%180,180,192%0%-1%0%%xterm%-1%-1%_Std_Colors_0_%80%24%0%1%-1%<none>%%0%1%-1%-1#0#
        
        StringBuilder sb = new StringBuilder();
        sb.append(sanitizeName(conn.getName()));
        sb.append("=#109#0%"); // SSH type
        sb.append(conn.getHost());
        sb.append("%");
        sb.append(conn.getPort());
        sb.append("%");
        sb.append(conn.getUsername());
        // Standard MobaXterm SSH settings
        sb.append("%-1%-1%-1%-1%0%0%%%-1%0%0%0%%1080%%0%0%1");
        // Font and color settings
        sb.append("#MobaFont%10%0%0%-1%15%236,236,236%30,30,30%180,180,192%0%-1%0%%xterm%-1%-1%_Std_Colors_0_%80%24%0%1%-1%<none>%%0%1%-1%-1#0#");
        
        return sb.toString();
    }
    
    private String sanitizeName(String name) {
        // Remove characters that might break the INI format
        return name.replace("=", "_").replace("%", "_").replace("#", "_");
    }

    
    @Override
    public String toString() {
        return getName();
    }
}
