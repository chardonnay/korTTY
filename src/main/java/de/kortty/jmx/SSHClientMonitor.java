package de.kortty.jmx;

import de.kortty.KorTTYApplication;
import de.kortty.core.SSHSession;
import de.kortty.core.SessionManager;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JMX MBean implementation for monitoring the SSH client.
 */
public class SSHClientMonitor implements SSHClientMonitorMBean {
    
    private final SessionManager sessionManager;
    private final LocalDateTime startTime;
    
    public SSHClientMonitor(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.startTime = LocalDateTime.now();
    }
    
    @Override
    public int getActiveConnectionCount() {
        return sessionManager.getActiveSessionCount();
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
    
    @Override
    public long getBufferedTextSize() {
        return sessionManager.getTotalBufferedTextSize();
    }
    
    @Override
    public List<String> getActiveConnectionNames() {
        return sessionManager.getActiveConnectionNames();
    }
    
    @Override
    public Map<String, String> getConnectionStatistics() {
        Map<String, String> stats = new HashMap<>();
        
        for (SSHSession session : sessionManager.getAllSessions()) {
            String key = session.getSessionId();
            StringBuilder value = new StringBuilder();
            value.append("Connection: ").append(session.getConnection().getDisplayName());
            value.append(", Connected: ").append(session.isConnected());
            value.append(", Buffer Size: ").append(session.getBufferedTextSize()).append(" chars");
            value.append(", Connected At: ").append(session.getConnectedAt());
            
            stats.put(key, value.toString());
        }
        
        return stats;
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
