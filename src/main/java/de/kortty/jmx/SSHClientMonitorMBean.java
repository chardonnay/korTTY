package de.kortty.jmx;

import java.util.List;
import java.util.Map;

/**
 * JMX MBean interface for monitoring the SSH client, registered as {@code de.kortty:type=SSHClient}.
 * Note: The interface name must be <ImplementationClassName>MBean
 */
public interface SSHClientMonitorMBean {

    /**
     * Gets the number of live SSH and Mosh terminal connections. Every split pane that runs on its
     * own connection counts; local shells do not.
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
     * Not tracked; always 0. The scrollback lives in the terminal widgets on the JavaFX thread and
     * is not counted for monitoring. The attribute stays for existing JMX clients.
     *
     * @deprecated not tracked; always 0
     */
    @Deprecated
    long getBufferedTextSize();

    /**
     * Gets the display names of the live connections in connect order, one per connection
     * (a name repeats when several panes are connected to the same server).
     */
    List<String> getActiveConnectionNames();

    /**
     * Gets one line per live connection, keyed by a random id:
     * {@code "Connection: <name>, Protocol: <SSH|MOSH|MOSH_CLIENT>, Connected At: <local time>"}.
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
