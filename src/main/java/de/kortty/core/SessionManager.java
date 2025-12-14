package de.kortty.core;

import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active SSH sessions.
 */
public class SessionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    
    private final Map<String, SSHSession> activeSessions = new ConcurrentHashMap<>();
    private final List<SessionListener> listeners = new ArrayList<>();
    
    public SessionManager() {
    }
    
    /**
     * Creates a new SSH session.
     */
    public SSHSession createSession(ServerConnection connection, String password) {
        String sessionId = UUID.randomUUID().toString();
        SSHSession session = new SSHSession(sessionId, connection, password);
        activeSessions.put(sessionId, session);
        
        notifySessionCreated(session);
        logger.info("Created session {} for {}", sessionId, connection.getDisplayName());
        
        return session;
    }
    
    /**
     * Gets an active session by ID.
     */
    public SSHSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }
    
    /**
     * Gets all active sessions.
     */
    public Collection<SSHSession> getAllSessions() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }
    
    /**
     * Gets the number of active sessions.
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
    
    /**
     * Closes a session by ID.
     */
    public void closeSession(String sessionId) {
        SSHSession session = activeSessions.remove(sessionId);
        if (session != null) {
            session.disconnect();
            notifySessionClosed(session);
            logger.info("Closed session {}", sessionId);
        }
    }
    
    /**
     * Closes all active sessions.
     */
    public void closeAllSessions() {
        List<String> sessionIds = new ArrayList<>(activeSessions.keySet());
        for (String sessionId : sessionIds) {
            closeSession(sessionId);
        }
        logger.info("Closed all sessions");
    }
    
    /**
     * Gets session states for project saving.
     */
    public List<SessionState> getSessionStates() {
        List<SessionState> states = new ArrayList<>();
        for (SSHSession session : activeSessions.values()) {
            states.add(session.getState());
        }
        return states;
    }
    
    /**
     * Calculates total buffered text size across all sessions.
     */
    public long getTotalBufferedTextSize() {
        return activeSessions.values().stream()
                .mapToLong(SSHSession::getBufferedTextSize)
                .sum();
    }
    
    /**
     * Gets connection names for all active sessions.
     */
    public List<String> getActiveConnectionNames() {
        return activeSessions.values().stream()
                .map(s -> s.getConnection().getDisplayName())
                .toList();
    }
    
    // Listener management
    
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }
    
    private void notifySessionCreated(SSHSession session) {
        for (SessionListener listener : listeners) {
            try {
                listener.onSessionCreated(session);
            } catch (Exception e) {
                logger.error("Error notifying listener", e);
            }
        }
    }
    
    private void notifySessionClosed(SSHSession session) {
        for (SessionListener listener : listeners) {
            try {
                listener.onSessionClosed(session);
            } catch (Exception e) {
                logger.error("Error notifying listener", e);
            }
        }
    }
    
    /**
     * Listener interface for session events.
     */
    public interface SessionListener {
        void onSessionCreated(SSHSession session);
        void onSessionClosed(SSHSession session);
    }
}
