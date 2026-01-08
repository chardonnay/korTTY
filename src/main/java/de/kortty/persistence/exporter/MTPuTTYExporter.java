package de.kortty.persistence.exporter;

import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports connections to MTPuTTY (Multi-Tabbed PuTTY) XML format.
 * 
 * Format:
 * <Servers>
 *   <Server>
 *     <DisplayName>Server Name</DisplayName>
 *     <ServerName>hostname</ServerName>
 *     <Port>22</Port>
 *     <UserName>user</UserName>
 *     <Folder>GroupName</Folder>
 *   </Server>
 * </Servers>
 */
public class MTPuTTYExporter implements ConnectionExporter {
    
    private static final Logger logger = LoggerFactory.getLogger(MTPuTTYExporter.class);
    
    @Override
    public String getName() {
        return "MTPuTTY";
    }
    
    @Override
    public String getFileExtension() {
        return "xml";
    }
    
    @Override
    public String getFileDescription() {
        return "MTPuTTY Server-Dateien (*.xml)";
    }
    
    @Override
    public void exportConnections(List<ServerConnection> connections, Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        
        // Root element
        Element serversElement = doc.createElement("Servers");
        doc.appendChild(serversElement);
        
        // Export each connection
        for (ServerConnection connection : connections) {
            Element serverElement = doc.createElement("Server");
            serversElement.appendChild(serverElement);
            
            // DisplayName
            Element displayName = doc.createElement("DisplayName");
            displayName.setTextContent(connection.getName());
            serverElement.appendChild(displayName);
            
            // ServerName
            Element serverName = doc.createElement("ServerName");
            serverName.setTextContent(connection.getHost());
            serverElement.appendChild(serverName);
            
            // Port
            Element port = doc.createElement("Port");
            port.setTextContent(String.valueOf(connection.getPort()));
            serverElement.appendChild(port);
            
            // UserName
            Element userName = doc.createElement("UserName");
            userName.setTextContent(connection.getUsername());
            serverElement.appendChild(userName);
            
            // Folder (group)
            if (connection.getGroup() != null && !connection.getGroup().isBlank()) {
                Element folder = doc.createElement("Folder");
                folder.setTextContent(connection.getGroup());
                serverElement.appendChild(folder);
            }
            
            // SSH Tunnels (Port Forwarding)
            if (connection.getSshTunnels() != null && !connection.getSshTunnels().isEmpty()) {
                Element tunnelsElement = doc.createElement("SSHTunnels");
                serverElement.appendChild(tunnelsElement);
                
                for (de.kortty.model.SSHTunnel tunnel : connection.getSshTunnels()) {
                    Element tunnelElement = doc.createElement("SSHTunnel");
                    tunnelsElement.appendChild(tunnelElement);
                    
                    Element enabled = doc.createElement("Enabled");
                    enabled.setTextContent(String.valueOf(tunnel.isEnabled()));
                    tunnelElement.appendChild(enabled);
                    
                    Element type = doc.createElement("Type");
                    type.setTextContent(tunnel.getType().name());
                    tunnelElement.appendChild(type);
                    
                    Element localHost = doc.createElement("LocalHost");
                    localHost.setTextContent(tunnel.getLocalHost() != null ? tunnel.getLocalHost() : "localhost");
                    tunnelElement.appendChild(localHost);
                    
                    Element localPort = doc.createElement("LocalPort");
                    localPort.setTextContent(String.valueOf(tunnel.getLocalPort()));
                    tunnelElement.appendChild(localPort);
                    
                    if (tunnel.getType() != de.kortty.model.TunnelType.DYNAMIC) {
                        Element remoteHost = doc.createElement("RemoteHost");
                        remoteHost.setTextContent(tunnel.getRemoteHost() != null ? tunnel.getRemoteHost() : "localhost");
                        tunnelElement.appendChild(remoteHost);
                        
                        Element remotePort = doc.createElement("RemotePort");
                        remotePort.setTextContent(String.valueOf(tunnel.getRemotePort()));
                        tunnelElement.appendChild(remotePort);
                    }
                    
                    if (tunnel.getDescription() != null && !tunnel.getDescription().trim().isEmpty()) {
                        Element description = doc.createElement("Description");
                        description.setTextContent(tunnel.getDescription());
                        tunnelElement.appendChild(description);
                    }
                }
            }
            
            // Note: We don't export passwords for security reasons
        }
        
        // Write to file
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(file.toFile());
        transformer.transform(source, result);
        
        logger.info("Exported {} connections to MTPuTTY format: {}", connections.size(), file);
    }

    
    @Override
    public String toString() {
        return getName();
    }
}
