package de.kortty.codingagent;

import com.sithtermfx.core.TtyConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/**
 * The verbs the panel, the dashboard context menu and (later) the Stage-3 API run against a
 * coding agent: send text, send key chords, submit a prompt, explain the detection and rename.
 * Every write goes through {@link PaneAccess#connectorFor} — the pane's own decorated connector —
 * synchronously on the caller's thread, checks {@code isConnected()} first and converts an
 * {@link IOException} into {@link CodingAgentActionException.Code#WRITE_FAILED}. A prompt is
 * refused while the agent is BLOCKED and when its first line would be swallowed by korTTY's own
 * AI shortcut filter. The audit sink receives one line per verb with byte counts only, never text.
 */
public final class CodingAgentActions {

    /** Receives one record per executed verb. */
    public interface AuditSink {
        void record(String verb, PaneRef pane, String detail);

        AuditSink LOGGING = (verb, pane, detail) -> LoggerFactory.getLogger(CodingAgentActions.class)
            .info("coding-agent action {} pane={}/{} {}", verb, pane.tabId(), pane.paneId(), detail);
    }

    private static final String VERB_SEND_TEXT = "sendText";
    private static final String VERB_SEND_KEYS = "sendKeys";
    private static final String VERB_PROMPT = "prompt";
    private static final String VERB_EXPLAIN = "explain";
    private static final String VERB_RENAME = "rename";

    private final CodingAgentRegistry registry;
    private final PaneAccess panes;
    private final AuditSink audit;

    public CodingAgentActions(CodingAgentRegistry registry, PaneAccess panes, AuditSink audit) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.panes = Objects.requireNonNull(panes, "panes");
        this.audit = audit == null ? AuditSink.LOGGING : audit;
    }

    /** Writes {@code text} verbatim (UTF-8) to the pane. */
    public void sendText(PaneRef pane, String text) throws CodingAgentActionException {
        requireEntry(pane);
        if (text == null || text.isEmpty()) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.EMPTY_INPUT, "Nothing to send");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        write(pane, bytes);
        audit(VERB_SEND_TEXT, pane, bytes.length);
    }

    /** Writes the chords' bytes in order. */
    public void sendKeys(PaneRef pane, List<KeyChord> chords) throws CodingAgentActionException {
        requireEntry(pane);
        byte[] bytes = KeyChordEncoder.encode(chords);
        if (bytes.length == 0) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.EMPTY_INPUT, "No key to send");
        }
        write(pane, bytes);
        audit(VERB_SEND_KEYS, pane, bytes.length);
    }

    /** Writes one chord. */
    public void sendKey(PaneRef pane, KeyChord chord) throws CodingAgentActionException {
        sendKeys(pane, chord == null ? List.of() : List.of(chord));
    }

    /**
     * Submits a prompt: a single line as text + Enter, several lines inside bracketed-paste markers
     * when the pane has enabled them (otherwise joined by Enter). Refused while the agent is BLOCKED
     * and when the first line would be intercepted by the host's AI shortcut (unless the bracketed
     * markers make the filter treat it as text).
     */
    public void prompt(PaneRef pane, String text) throws CodingAgentActionException {
        CodingAgentEntry entry = requireEntry(pane);
        String normalised = KeyChordEncoder.normalise(text);
        if (normalised.isBlank()) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.EMPTY_INPUT, "Empty prompt");
        }
        if (entry.state() == CodingAgentState.BLOCKED) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.AGENT_BLOCKED,
                entry.displayName() + " is waiting for a decision; answer it before sending a prompt");
        }
        boolean multiLine = KeyChordEncoder.isMultiLine(normalised);
        boolean bracketed = multiLine && panes.isBracketedPasteEnabled(pane);
        if (!bracketed) {
            String firstLine = firstLine(normalised);
            if (panes.wouldHostShortcutIntercept(firstLine)) {
                throw new CodingAgentActionException(CodingAgentActionException.Code.HOST_SHORTCUT_CONFLICT,
                    "The first line starts with korTTY's AI shortcut command '" + panes.hostShortcutCommandName()
                        + "' and would not reach the agent");
            }
        }
        byte[] bytes = KeyChordEncoder.promptPayload(normalised, bracketed).getBytes(StandardCharsets.UTF_8);
        write(pane, bytes);
        audit(VERB_PROMPT, pane, "bytes=" + bytes.length + " lines=" + (normalised.split("\n", -1).length)
            + " bracketed=" + bracketed);
    }

    /** A multi-line human explanation of the current detection (rule, evidence, process, time in state). */
    public String explain(PaneRef pane) throws CodingAgentActionException {
        CodingAgentEntry entry = requireEntry(pane);
        DetectionResult detection = entry.detection();
        long now = registry.clockMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(detection != null ? detection.explain() : "No detection result").append('\n');
        String ruleId = detection != null ? detection.matchedRuleId() : null;
        sb.append("Rule: ").append(ruleId != null ? ruleId : "none (fallback state)").append('\n');
        sb.append("State: ").append(entry.state());
        if (entry.doneUntilSeen()) {
            sb.append(" (done until seen)");
        }
        sb.append(" for ").append(DurationText.mmss(entry.secondsInState(now)))
            .append(", since ").append(Instant.ofEpochMilli(entry.stateSinceMillis())).append('\n');
        AgentProcess process = entry.process();
        if (process != null) {
            sb.append("Process: pid ").append(process.pid());
            if (process.command() != null && !process.command().isBlank()) {
                sb.append(' ').append(process.command());
            }
            if (process.startedAt() != null) {
                sb.append(" (started ").append(process.startedAt()).append(')');
            }
        } else {
            sb.append("Process: unknown");
        }
        sb.append('\n');
        if (entry.alias() != null) {
            sb.append("Alias: ").append(entry.alias()).append('\n');
        }
        sb.append("Pane: ").append(pane.tabId()).append('/').append(pane.paneId());
        audit.record(VERB_EXPLAIN, pane, "chars=" + sb.length());
        return sb.toString();
    }

    /** Sets the alias shown for the agent; blank restores the agent name. */
    public void rename(PaneRef pane, String alias) throws CodingAgentActionException {
        requireEntry(pane);
        boolean cleared = alias == null || alias.isBlank();
        registry.setAlias(pane, alias);
        audit.record(VERB_RENAME, pane, cleared ? "alias=cleared" : "aliasChars=" + alias.strip().length());
    }

    /** True when the pane is open and its connector reports connected. */
    public boolean isConnected(PaneRef pane) {
        if (pane == null) {
            return false;
        }
        Optional<TtyConnector> connector = panes.connectorFor(pane);
        return connector.isPresent() && connector.get().isConnected();
    }

    private CodingAgentEntry requireEntry(PaneRef pane) throws CodingAgentActionException {
        if (pane == null) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.PANE_NOT_FOUND, "No pane given");
        }
        return registry.entry(pane).orElseThrow(() -> new CodingAgentActionException(
            CodingAgentActionException.Code.PANE_NOT_FOUND, "No coding agent registered for " + pane));
    }

    private void write(PaneRef pane, byte[] bytes) throws CodingAgentActionException {
        TtyConnector connector = panes.connectorFor(pane).orElseThrow(() -> new CodingAgentActionException(
            CodingAgentActionException.Code.PANE_NOT_FOUND, "The pane is no longer open: " + pane));
        if (!connector.isConnected()) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.NOT_CONNECTED,
                "The pane is not connected: " + pane);
        }
        try {
            connector.write(bytes);
        } catch (IOException e) {
            throw new CodingAgentActionException(CodingAgentActionException.Code.WRITE_FAILED,
                "Writing to the terminal failed: " + e.getMessage(), e);
        }
    }

    private void audit(String verb, PaneRef pane, int byteCount) {
        audit(verb, pane, "bytes=" + byteCount);
    }

    private void audit(String verb, PaneRef pane, String detail) {
        try {
            audit.record(verb, pane, detail);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(CodingAgentActions.class).debug("Audit sink failed for {}: {}", verb, e.toString());
        }
    }

    private static String firstLine(String normalised) {
        int newline = normalised.indexOf('\n');
        return newline < 0 ? normalised : normalised.substring(0, newline);
    }
}
