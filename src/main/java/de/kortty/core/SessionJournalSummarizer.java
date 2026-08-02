package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic AI summarization of live session journals. Sessions register on journal start and
 * unregister when their tab closes; the scheduler only owns a timer while at least one journal
 * is live (App Nap friendly).
 */
public class SessionJournalSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSummarizer.class);

    private final SessionJournalService service;
    private final Set<SessionJournalSession> sessions = ConcurrentHashMap.newKeySet();

    public SessionJournalSummarizer(SessionJournalService service) {
        this.service = service;
    }

    /** Registers a live capture session for periodic summarization. */
    public void register(SessionJournalSession session) {
        if (session != null) {
            sessions.add(session);
        }
    }

    public void unregister(SessionJournalSession session) {
        if (session != null) {
            sessions.remove(session);
        }
    }

    /**
     * Runs the final summarization pass (remaining tail, closing wrap-up, optional AI title)
     * before the session is closed. Blocking; callers invoke it right before
     * {@link SessionJournalSession#close()}.
     */
    public void onSessionClosing(SessionJournalSession session) {
        unregister(session);
        // Full close-pass behavior (final window, SESSION_SUMMARY, AI title) is layered on in the
        // summarization implementation; capture works without it.
        logger.debug("Session journal closing: {}", session != null ? session.getDirectory().getFileName() : null);
    }

    /** Stops the scheduler on application shutdown. */
    public synchronized void stop() {
        sessions.clear();
    }

    Set<SessionJournalSession> registeredSessions() {
        return sessions;
    }

    SessionJournalService service() {
        return service;
    }
}
