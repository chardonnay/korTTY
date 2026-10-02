package de.kortty.jmx;

import de.kortty.KorTTYApplication;
import de.kortty.core.ActiveConnectionRegistry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JMX MBean implementation for monitoring the SSH client. Connection attributes are read live from
 * the {@link ActiveConnectionRegistry} that the terminal tabs report into.
 */
public class SSHClientMonitor implements SSHClientMonitorMBean {

    private final ActiveConnectionRegistry registry;
    private final LocalDateTime startTime;

    public SSHClientMonitor(ActiveConnectionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.startTime = LocalDateTime.now();
    }

    @Override
    public int getActiveConnectionCount() {
        return registry.connectionCount();
    }

    @Override
    public long getUsedMemoryBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @Override
    public long getMaxMemoryBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    /** @deprecated not tracked; always 0 (see {@link SSHClientMonitorMBean#getBufferedTextSize()}). */
    @Deprecated
    @Override
    public long getBufferedTextSize() {
        return 0L;
    }

    @Override
    public List<String> getActiveConnectionNames() {
        return registry.connectionNames();
    }

    @Override
    public Map<String, String> getConnectionStatistics() {
        return registry.statistics();
    }

    @Override
    public long getUptimeSeconds() {
        return Duration.between(startTime, LocalDateTime.now()).getSeconds();
    }

    @Override
    public String getVersion() {
        return KorTTYApplication.getAppVersion();
    }

    @Override
    public void forceGarbageCollection() {
        System.gc();
    }
}
