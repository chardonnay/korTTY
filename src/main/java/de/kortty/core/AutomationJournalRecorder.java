package de.kortty.core;

import de.kortty.model.AutomationRunStatus;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * Records what an automation run did on one target server into that target's session journal:
 * the commands sent, their output, and notes such as an AI decision. Every method is
 * best-effort and never throws — a journal problem must never fail the job itself.
 *
 * <p>Alongside the capture it hashes the redacted commands and output, so a later run that
 * produced exactly the same can be recognised as a duplicate.</p>
 */
public class AutomationJournalRecorder {

    private static final Logger logger = LoggerFactory.getLogger(AutomationJournalRecorder.class);

    /** Recorder that records nothing; used when journals are off for a run. */
    public static final AutomationJournalRecorder NOOP = new AutomationJournalRecorder(null, null, null);

    private final SessionJournalSession session;
    private final SessionJournalService service;
    private final String connectionId;
    private final MessageDigest digest;
    private volatile AutomationRunStatus targetStatus;
    private volatile int commandCount;

    AutomationJournalRecorder(SessionJournalSession session, SessionJournalService service, String connectionId) {
        this.session = session;
        this.service = service;
        this.connectionId = connectionId;
        this.digest = session != null ? newDigest() : null;
    }

    /** True when this recorder writes into a journal (false for {@link #NOOP}). */
    public boolean isRecording() {
        return session != null;
    }

    /** One command sent to the server, as the user would recognise it (without shell wrappers). */
    public void appendCommand(String command) {
        if (session == null || command == null || command.isBlank()) {
            return;
        }
        try {
            session.appendInputLine(command.strip());
            commandCount++;
            hash("$ " + session.redact(command.strip()));
        } catch (RuntimeException e) {
            logger.debug("Automation journal could not record a command: {}", e.getMessage());
        }
    }

    /** The output of the last command; stderr lines are prefixed so they stay distinguishable. */
    public void appendOutput(String stdout, String stderr) {
        if (session == null) {
            return;
        }
        try {
            if (stdout != null && !stdout.isEmpty()) {
                String text = withTrailingNewline(stdout);
                session.appendOutputChunk(text);
                hash(session.redact(text));
            }
            if (stderr != null && !stderr.isBlank()) {
                StringBuilder prefixed = new StringBuilder();
                for (String line : stderr.split("\\R", -1)) {
                    if (!line.isEmpty()) {
                        prefixed.append("[stderr] ").append(line).append('\n');
                    }
                }
                session.appendOutputChunk(prefixed.toString());
                hash(session.redact(prefixed.toString()));
            }
        } catch (RuntimeException e) {
            logger.debug("Automation journal could not record output: {}", e.getMessage());
        }
    }

    /**
     * A note that belongs in the journal's timeline — the job's summary, an AI decision, the
     * swarm's combined report. It is also part of the duplicate hash.
     */
    public void appendNote(String title, String text) {
        if (session == null || text == null || text.isBlank()) {
            return;
        }
        try {
            session.appendUserNote(text);
            hash("# " + session.redact(text));
            SessionJournalEntry entry = new SessionJournalEntry();
            entry.setKind(SessionJournalEntryKind.SYSTEM);
            entry.setState(SessionJournalEntry.State.RAW);
            entry.setCreatedAt(OffsetDateTime.now());
            entry.setTitle(title);
            entry.setText(session.redact(text.strip()));
            service.appendEntry(session.getDirectory(), entry);
        } catch (Exception e) {
            logger.debug("Automation journal could not record a note: {}", e.getMessage());
        }
    }

    /** A short line in the capture log only (exit codes, timeouts) — no timeline entry. */
    public void appendLogNote(String text) {
        if (session == null || text == null || text.isBlank()) {
            return;
        }
        try {
            session.appendUserNote(text);
            hash("# " + session.redact(text));
        } catch (RuntimeException e) {
            logger.debug("Automation journal could not record a log note: {}", e.getMessage());
        }
    }

    /** A secret known only after the journal started; redacted from everything captured afterwards. */
    public void addSecret(String secret) {
        if (session != null && secret != null && !secret.isBlank()) {
            session.addKnownSecret(secret);
        }
    }

    /** The outcome on this target, when it differs from the run as a whole (multi-target jobs). */
    public void setTargetStatus(AutomationRunStatus status) {
        this.targetStatus = status;
    }

    public AutomationRunStatus getTargetStatus() {
        return targetStatus;
    }

    /** Number of commands recorded so far. */
    public int getCommandCount() {
        return commandCount;
    }

    /** The journal folder, or null for {@link #NOOP}. */
    public Path directory() {
        return session != null ? session.getDirectory() : null;
    }

    String connectionId() {
        return connectionId;
    }

    SessionJournalSession session() {
        return session;
    }

    /** Hex SHA-256 of everything recorded so far; null for {@link #NOOP}. */
    synchronized String contentHash() {
        if (digest == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    private synchronized void hash(String text) {
        if (digest != null && text != null) {
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
    }

    private static String withTrailingNewline(String text) {
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
