package de.kortty.persistence.importer;

import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports connections from MTPuTTY (Multi-Tabbed PuTTY) XML format.
 * 
 * MTPuTTY stores connections in servers.xml with the following structure:
 * <Servers>
 *   <Server>
 *     <DisplayName>Server Name</DisplayName>
 *     <ServerName>hostname</ServerName>
 *     <Port>22</Port>
 *     <UserName>user</UserName>
 *     <Password>base64encoded</Password>
 *     <Folder>GroupName</Folder>
 *   </Server>
 * </Servers>
 */
public class MTPuTTYImporter implements ConnectionImporter {
    
    private static final Logger logger = LoggerFactory.getLogger(MTPuTTYImporter.class);
    
    @Override
    public String getName() {
        return "MTPuTTY";
    }
    
    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"xml"};
    }
    
    @Override
    public String getFileDescription() {
        return "MTPuTTY Server-Dateien (*.xml)";
    }
    
    @Override
    public boolean canImport(Path file) {
        if (!Files.exists(file) || !file.toString().toLowerCase().endsWith(".xml")) {
            return false;
        }
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file.toFile());
            
            // Check for MTPuTTY structure
            Element root = doc.getDocumentElement();
            return "Servers".equals(root.getTagName()) || 
                   root.getElementsByTagName("Server").getLength() > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public List<ServerConnection> importConnections(Path file) throws Exception {
        List<ServerConnection> connections = new ArrayList<>();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Security: Disable external entities
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file.toFile());
        
        NodeList serverNodes = doc.getElementsByTagName("Server");
        
        for (int i = 0; i < serverNodes.getLength(); i++) {
            Element serverElement = (Element) serverNodes.item(i);
            
            try {
                ServerConnection connection = parseServer(serverElement);
                if (connection != null) {
                    connections.add(connection);
                }
            } catch (Exception e) {
                logger.warn("Failed to parse server entry {}", i, e);
            }
        }
        
        logger.info("Imported {} connections from MTPuTTY file: {}", connections.size(), file);
        return connections;
    }
    
    private ServerConnection parseServer(Element serverElement) {
        String displayName = getElementText(serverElement, "DisplayName");
        String serverName = getElementText(serverElement, "ServerName");
        String portStr = getElementText(serverElement, "Port");
        String userName = getElementText(serverElement, "UserName");
        String folder = getElementText(serverElement, "Folder");
        
        if (serverName == null || serverName.isBlank()) {
            return null;
        }
        
        int port = 22;
        try {
            if (portStr != null && !portStr.isBlank()) {
                port = Integer.parseInt(portStr);
            }
        } catch (NumberFormatException e) {
            logger.warn("Invalid port number: {}", portStr);
        }
        
        ServerConnection connection = new ServerConnection();
        connection.setName(displayName != null ? displayName : serverName);
        connection.setHost(serverName);
        connection.setPort(port);
        connection.setUsername(userName != null ? userName : "root");
        connection.setGroup(folder);
        
        // Note: We don't import passwords as they might be encrypted differently
        // The user will need to re-enter them
        
        return connection;
    }
    
    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }
}
