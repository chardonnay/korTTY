package de.kortty.control;

import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.RedactionResult;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.core.SessionJournalService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The masking and the size caps every text an MCP client ({@code client_kind = "mcp"}) reads is put
 * through: {@code pane.read}, {@code pane.wait_output}, the agent evidence and command lines of
 * {@code agent.list}/{@code agent.get} and of the pane descriptions, and the tab and window titles.
 *
 * <p>An MCP client counts as a cloud AI profile: its model usually runs at a vendor, so masking is
 * always on and has no opt-out. Two passes, the ones {@link AiOutboundRedaction#redact} applies to
 * the AI selection actions: the pane's known secrets (the connection password and the
 * organisation's {@code [[rule.session-journal.replace]]} rules, from
 * {@code TerminalView.createSecretRedactor()}), then the well-known token formats. A pane whose
 * session cannot be resolved still gets the organisation's rules and the token formats.
 *
 * <p>The caps sit well below the protocol's own limits ({@link ControlApiProtocol#MAX_READ_LINES},
 * {@link ControlApiProtocol#MAX_RESULT_BYTES}): at most {@link #MAX_LINES} rows and
 * {@link #MAX_CHARS} characters, the most recent ones kept. Masking runs before the character cap,
 * so a cut can never split a secret and leave half of it unmasked.
 *
 * <p>One instance per request: it counts what it masked and is not thread-safe. The masking runs on
 * the connection or timer thread once the text has been read, never on the JavaFX application thread;
 * only {@link #secretsFor} hops to it, to look up the panes' sessions.
 */
final class McpOutputMasking {

    /** The most rows an MCP client is given by one read. */
    static final int MAX_LINES = 2_000;

    /** The most characters (line breaks included) an MCP client is given by one read. */
    static final int MAX_CHARS = 64_000;

    /** The result field reporting how many secrets were masked in the returned text. */
    static final String FIELD_MASKED_COUNT = "masked_count";

    private final SessionJournalRedactor secrets;

    private int maskedCount;

    private McpOutputMasking(SessionJournalRedactor secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * A masking pass for one pane's text.
     *
     * @param knownSecrets the pane's session redactor, or null when the pane has none: the
     *     organisation's replacement rules and the token formats are masked then
     */
    static McpOutputMasking with(SessionJournalRedactor knownSecrets) {
        return new McpOutputMasking(knownSecrets != null ? knownSecrets : policyOnly());
    }

    /** The {@code lines} an MCP read may ask for, clamped rather than refused. */
    static int clampLines(int requested) {
        return Math.max(1, Math.min(requested, MAX_LINES));
    }

    /**
     * The known-secret redactors of several panes, read in one JavaFX hop. A pane that is no longer
     * open, or that has no session, maps to null.
     */
    static Map<String, SessionJournalRedactor> secretsFor(ControlSurface surface, UiDispatcher ui,
                                                          Collection<String> paneIds)
            throws ControlApiException {
        List<String> ids = new ArrayList<>();
        for (String id : new LinkedHashSet<>(paneIds)) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> {
            Map<String, SessionJournalRedactor> found = new LinkedHashMap<>();
            for (String id : ids) {
                found.put(id, surface.secretRedactorFor(id).orElse(null));
            }
            return found;
        });
    }

    /** How many secrets this pass has masked in what it returned. */
    int maskedCount() {
        return maskedCount;
    }

    /** Masks one string; null and empty pass through. */
    String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        RedactionResult result = AiOutboundRedaction.redact(text, secrets);
        maskedCount += result.count();
        return result.text();
    }

    /**
     * The pane text as an MCP client may see it: the last {@link #MAX_LINES} rows, masked, then cut
     * to the last {@link #MAX_CHARS} characters. {@code truncated} is set when anything was dropped.
     */
    PaneText text(PaneText source) {
        if (source == null) {
            return null;
        }
        List<String> lines = source.lines();
        boolean truncated = source.truncated();
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(lines.size() - MAX_LINES, lines.size());
            truncated = true;
        }
        // Whole rows only, and twice the budget: bounds the regex work on a pathologically wide pane
        // without ever cutting inside a row before it is masked.
        int first = firstFitting(lines, 2L * MAX_CHARS);
        if (first > 0) {
            lines = lines.subList(first, lines.size());
            truncated = true;
        }
        List<String> masked = new ArrayList<>(lines.size());
        int[] counts = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isEmpty()) {
                masked.add(line == null ? "" : line);
                continue;
            }
            RedactionResult result = AiOutboundRedaction.redact(line, secrets);
            masked.add(result.text());
            counts[i] = result.count();
        }
        int keepFrom = firstFitting(masked, MAX_CHARS);
        List<String> kept = new ArrayList<>(masked.subList(keepFrom, masked.size()));
        if (keepFrom > 0) {
            truncated = true;
        }
        if (kept.isEmpty() && !masked.isEmpty()) {
            // A single row longer than the whole budget: keep its tail, cut after masking.
            String last = masked.get(masked.size() - 1);
            kept.add(last.substring(last.length() - (MAX_CHARS - 1)));
            keepFrom = masked.size() - 1;
            truncated = true;
        }
        for (int i = keepFrom; i < counts.length; i++) {
            maskedCount += counts[i];
        }
        return new PaneText(source.paneId(), source.mode(), kept, source.columns(), source.rows(),
            source.alternateScreen(), mask(source.oscTitle()), truncated);
    }

    /** A coding agent as an MCP client may see it: evidence line and command line masked. */
    AgentInfo agent(AgentInfo info) {
        if (info == null) {
            return null;
        }
        return new AgentInfo(info.paneId(), info.tabId(), info.windowId(), info.detected(), info.kind(),
            info.state(), info.displayName(), info.alias(), info.matchedRuleId(), mask(info.evidence()),
            info.stateSinceMillis(), info.secondsInState(), info.doneUntilSeen(), info.pid(),
            mask(info.command()));
    }

    /** A pane description as an MCP client may see it: its embedded agent masked. */
    PaneInfo pane(PaneInfo info) {
        if (info == null || info.agent() == null) {
            return info;
        }
        return new PaneInfo(info.paneId(), info.tabId(), info.windowId(), info.index(), info.focused(),
            info.protocol(), info.connected(), info.localShell(), info.workingDirectory(),
            info.shellPid(), info.columns(), info.rows(), info.alternateScreen(),
            info.bracketedPaste(), agent(info.agent()));
    }

    /** A tab as an MCP client may see it: the title, which a remote OSC sequence can set, masked. */
    TabInfo tab(TabInfo info) {
        if (info == null) {
            return null;
        }
        return new TabInfo(info.tabId(), info.windowId(), mask(info.title()), info.protocol(), info.host(),
            info.active(), info.connected(), info.paneCount(), info.agents());
    }

    /** A window as an MCP client may see it: the title masked. */
    WindowInfo window(WindowInfo info) {
        if (info == null) {
            return null;
        }
        return new WindowInfo(info.windowId(), info.index(), mask(info.title()), info.focused(),
            info.tabCount());
    }

    /** The index of the first row from which the rest fits {@code budget} characters, line breaks included. */
    private static int firstFitting(List<String> lines, long budget) {
        long used = 0L;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            long size = (line == null ? 0 : line.length()) + 1L;
            if (used + size > budget) {
                return i + 1;
            }
            used += size;
        }
        return 0;
    }

    /** The organisation's replacement rules alone, for a pane whose session is unknown. */
    private static SessionJournalRedactor policyOnly() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.setReplacements(SessionJournalService.policyReplacements());
        return redactor;
    }
}
