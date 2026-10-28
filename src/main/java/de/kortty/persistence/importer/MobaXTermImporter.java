package de.kortty.persistence.importer;

import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports connections from MobaXterm INI format.
 * 
 * MobaXterm stores connections in MobaXterm.ini with sections like:
 * [Bookmarks]
 * SubRep=ServerGroup
 * ImgNum=41
 * ServerName= user@hostname #22 #0 #...
 * 
 * Or newer format:
 * [Bookmarks_1]
 * SubRep=
 * ImgNum=42
 * server1=#109#0%hostname%22%user%...
 */
public class MobaXTermImporter implements ConnectionImporter {
    
    private static final Logger logger = LoggerFactory.getLogger(MobaXTermImporter.class);
    
    // Pattern for SSH bookmark entries
    // Format: name=#109#flag%host%port%user%...
    private static final Pattern SSH_PATTERN = Pattern.compile(
            "([^=]+)=#109#\\d+%([^%]+)%(\\d+)%([^%]*)%.*"
    );
    
    // Serial bookmark entries (icon 131, session type 8): name=#131#8%port%speed%...
    // The port is shown as "COM3  (USB Serial Port (COM3))" or a device path; only port and speed
    // are read, the remaining fields are not documented and keep korTTY's defaults (8N1, no flow control).
    private static final Pattern SERIAL_PATTERN = Pattern.compile(
            "([^=]+)=#\\d+#8%([^%]+)%([^%]*)(?:%.*)?"
    );

    /** The {@code COMn} inside a Windows port label such as {@code COM3  (USB Serial Port (COM3))}. */
    private static final Pattern COM_PORT = Pattern.compile("\\b(COM\\d+)\\b", Pattern.CASE_INSENSITIVE);

    // Alternative format: name= user@host #port #flag ...
    private static final Pattern ALT_PATTERN = Pattern.compile(
            "([^=]+)=\\s*([^@]+)@([^\\s#]+)\\s*#(\\d+).*"
    );
    
    @Override
    public String getName() {
        return "MobaXterm";
    }
    
    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"ini", "mxtsessions"};
    }
    
    @Override
    public String getFileDescription() {
        return "MobaXterm Session-Dateien (*.ini, *.mxtsessions)";
    }
    
    @Override
    public boolean canImport(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        
        String fileName = file.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".ini") && !fileName.endsWith(".mxtsessions")) {
            return false;
        }
        
        // Quick check for MobaXterm format
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[Bookmarks")) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        
        return false;
    }
    
    @Override
    public List<ServerConnection> importConnections(Path file) throws Exception {
        List<ServerConnection> connections = new ArrayList<>();
        
        String currentGroup = null;
        boolean inBookmarksSection = false;
        
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Check for section headers
                if (line.startsWith("[") && line.endsWith("]")) {
                    String section = line.substring(1, line.length() - 1);
                    inBookmarksSection = section.startsWith("Bookmarks");
                    continue;
                }
                
                if (!inBookmarksSection) {
                    continue;
                }
                
                // Parse SubRep (group name)
                if (line.startsWith("SubRep=")) {
                    currentGroup = line.substring(7).trim();
                    if (currentGroup.isEmpty()) {
                        currentGroup = null;
                    }
                    continue;
                }
                
                // Skip non-connection lines
                if (line.startsWith("ImgNum=") || line.isEmpty()) {
                    continue;
                }
                
                // Try to parse SSH connection
                ServerConnection connection = parseConnection(line, currentGroup);
                if (connection != null) {
                    connections.add(connection);
                }
            }
        }
        
        logger.info("Imported {} connections from MobaXterm file: {}", connections.size(), file);
        return connections;
    }
    
    private ServerConnection parseConnection(String line, String group) {
        Matcher serialMatcher = SERIAL_PATTERN.matcher(line);
        if (serialMatcher.matches()) {
            return serialConnection(serialMatcher.group(1).trim(), serialMatcher.group(2), serialMatcher.group(3), group);
        }

        // Try SSH pattern first (#109 is SSH type)
        Matcher sshMatcher = SSH_PATTERN.matcher(line);
        if (sshMatcher.matches()) {
            String name = sshMatcher.group(1).trim();
            String host = sshMatcher.group(2).trim();
            int port = Integer.parseInt(sshMatcher.group(3));
            String user = sshMatcher.group(4).trim();
            
            if (user.isEmpty()) {
                user = "root";
            }
            
            ServerConnection connection = new ServerConnection();
            connection.setName(name);
            connection.setHost(host);
            connection.setPort(port);
            connection.setUsername(user);
            connection.setGroup(group);
            
            return connection;
        }
        
        // Try alternative pattern
        Matcher altMatcher = ALT_PATTERN.matcher(line);
        if (altMatcher.matches()) {
            String name = altMatcher.group(1).trim();
            String user = altMatcher.group(2).trim();
            String host = altMatcher.group(3).trim();
            int port = Integer.parseInt(altMatcher.group(4));
            
            ServerConnection connection = new ServerConnection();
            connection.setName(name);
            connection.setHost(host);
            connection.setPort(port);
            connection.setUsername(user);
            connection.setGroup(group);
            
            return connection;
        }
        
        return null;
    }

    private ServerConnection serialConnection(String name, String portField, String speedField, String group) {
        String port = serialPortName(portField);
        if (port == null) {
            return null;
        }
        de.kortty.model.SerialSettings serial = new de.kortty.model.SerialSettings();
        serial.setPortName(port);
        try {
            int speed = Integer.parseInt(speedField.trim());
            if (speed > 0) {
                serial.setBaudRate(speed);
            }
        } catch (NumberFormatException e) {
            // Keeps the default speed.
        }
        ServerConnection connection = new ServerConnection();
        connection.setName(name);
        connection.setProtocol(de.kortty.model.ConnectionProtocol.SERIAL);
        connection.setSerialSettings(serial);
        connection.setGroup(group);
        return connection;
    }

    /** {@code COM3} from MobaXterm's port label; a device path or other name as it is; null when blank. */
    static String serialPortName(String portField) {
        if (portField == null || portField.isBlank()) {
            return null;
        }
        Matcher com = COM_PORT.matcher(portField);
        if (com.find()) {
            return com.group(1).toUpperCase(java.util.Locale.ROOT);
        }
        return portField.trim();
    }
}
