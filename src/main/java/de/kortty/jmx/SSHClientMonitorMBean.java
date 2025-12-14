package de.kortty.jmx;

import java.util.List;
import java.util.Map;

/**
 * JMX MBean interface for monitoring the SSH client.
 * Note: The interface name must be <ImplementationClassName>MBean
 */
public interface SSHClientMonitorMBean {
    
    /**
     * Gets the number of active SSH connections.
     */
    int getActiveConnectionCount();
    
    /**
     * Gets the total memory used by the JVM in bytes.
     */
    long getUsedMemoryBytes();
    
    /**
     * Gets the maximum memory available to the JVM in bytes.
     */
    long getMaxMemoryBytes();
    
    /**
     * Gets the total size of buffered terminal text across all sessions.
     */
    long getBufferedTextSize();
    
    /**
     * Gets the names of all active connections.
     */
    List<String> getActiveConnectionNames();
    
    /**
     * Gets detailed statistics for each connection.
     */
    Map<String, String> getConnectionStatistics();
    
    /**
     * Gets the application uptime in seconds.
     */
    long getUptimeSeconds();
    
    /**
     * Gets the application version.
     */
    String getVersion();
    
    /**
     * Forces garbage collection (use with caution).
     */
    void forceGarbageCollection();
}
