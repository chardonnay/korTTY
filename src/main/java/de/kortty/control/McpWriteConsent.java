package de.kortty.control;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's consent for every write an MCP client ({@code client_kind = "mcp"}) asks for.
 *
 * <p>Any thread, never the JavaFX application thread: {@link #authorize} blocks the connection's
 * worker thread for up to {@link #PROMPT_TIMEOUT_MILLIS} while the {@link Prompter} shows a modal on
 * the JavaFX thread. A prompt that times out, cannot be shown or fails counts as a denial.
 *
 * <p>The answers are Deny (the default), Allow once, and Allow for this pane in this session. The
 * last one is offered only for {@code pane.send_text} whose text submits nothing: no line break, no
 * other control character and no {@code submit}. {@code pane.run}, {@code pane.send_keys} and any
 * text that submits a line always ask, because one click must never let an injected model run
 * whatever it wants afterwards (decision D17). A session grant covers exactly one pane for one MCP
 * session — the {@code mcp_session} id a {@code kortty-cli mcp} process sends with every
 * connection, or the single connection when a client sends none — and only that kind of text.
 *
 * <p>Refusals that need no question come first ({@link #refuseIfGuarded}): a pane that is pacing a
 * paste, shows a full-screen program, is suspected to run another identity, mirrors its input
 * (broadcast or multi-exec), or belongs to a coding agent or a korTTY agent run.
 *
 * <p>Every decision is recorded through the {@link ControlAuditSink} under the verb
 * {@value #AUDIT_VERB}, with counts and shapes only — never the text.
 *
 * <p>This narrows what an MCP host can do through korTTY; it is not a sandbox. The client kind and
 * the client name are declared by the client.
 */
public final class McpWriteConsent {

    private static final Logger LOG = LoggerFactory.getLogger(McpWriteConsent.class);

    /** How long the user has to answer before the write is denied. */
    public static final long PROMPT_TIMEOUT_MILLIS = 60_000L;

    /** The verb every consent decision is audited under; not a writing verb, so it never notifies. */
    public static final String AUDIT_VERB = "mcp.consent";

    /** Data reason: the user pressed Deny or closed the prompt. */
    public static final String REASON_DENIED = "denied";

    /** Data reason: the prompt was not answered in time. */
    public static final String REASON_TIMEOUT = "timeout";

    /** Data reason: no prompt could be shown, so nobody could allow the write. */
    public static final String REASON_NO_PROMPT = "no_prompt";

    /** The longest client name shown in the prompt and written to the audit line. */
    static final int MAX_CLIENT_CHARS = 64;

    /** The most session grants kept; beyond that all of them are dropped and the user asked again. */
    static final int MAX_GRANTS = 1024;

    private static final String VERB_SEND_TEXT = "pane.send_text";

    /** The user's answer. */
    public enum Decision {
        /** Refuse the write; the default button. */
        DENY,
        /** Perform this one write. */
        ALLOW_ONCE,
        /** Perform it, and every later non-submitting text for the same pane in this MCP session. */
        ALLOW_PANE_FOR_SESSION
    }

    /**
     * What the prompt shows.
     *
     * @param client the client name, as the client reported it, already made safe to show
     * @param paneId the target pane
     * @param paneLabel the pane as the user knows it: tab title, host and pane id
     * @param verb the wire method
     * @param text the exact text, or the key names for {@code pane.send_keys}
     * @param submits whether the write submits a line (Enter, a line break, {@code pane.run})
     * @param sessionOffered whether "Allow for this pane in this session" may be offered
     */
    public record Request(String client, String paneId, String paneLabel, String verb, String text,
                          boolean submits, boolean sessionOffered) {
    }

    /**
     * Shows the question to the user and waits for the answer.
     *
     * <p>Called on a control worker thread; an implementation must never block the JavaFX thread and
     * must refuse ({@link Decision#DENY} or an exception) when it is called on it.
     */
    @FunctionalInterface
    public interface Prompter {

        /**
         * @param request what to show
         * @param timeoutMillis how long to wait for the answer
         * @return the answer; null counts as {@link Decision#DENY}
         * @throws TimeoutException when the user did not answer in time
         * @throws InterruptedException when the waiting thread was interrupted
         */
        Decision ask(Request request, long timeoutMillis) throws TimeoutException, InterruptedException;
    }

    /** A prompter for a korTTY without a user interface: nobody can allow anything. */
    public static final Prompter NO_PROMPT = (request, timeoutMillis) -> {
        throw new IllegalStateException("No consent prompt is available");
    };

    private final Prompter prompter;

    private final ControlAuditSink audit;

    private final long timeoutMillis;

    /** One prompt at a time: two MCP hosts must not stack modals over each other. */
    private final Semaphore promptLock = new Semaphore(1, true);

    private final Set<String> grants = new LinkedHashSet<>();

    /**
     * @param prompter how the user is asked; {@link #NO_PROMPT} when null
     * @param audit where each decision is recorded; {@link ControlAuditSink#LOGGING} when null
     */
    public McpWriteConsent(Prompter prompter, ControlAuditSink audit) {
        this(prompter, audit, PROMPT_TIMEOUT_MILLIS);
    }

    /** The same with another answer deadline; for tests. */
    McpWriteConsent(Prompter prompter, ControlAuditSink audit, long timeoutMillis) {
        this.prompter = prompter == null ? NO_PROMPT : prompter;
        this.audit = audit == null ? ControlAuditSink.LOGGING : audit;
        this.timeoutMillis = timeoutMillis;
    }

    /** A consent that can never be given: every MCP write is denied. Fail-closed default wiring. */
    public static McpWriteConsent denyingAll(ControlAuditSink audit) {
        return new McpWriteConsent(NO_PROMPT, audit);
    }

    /**
     * Refuses the write without asking when one of the pane's guards is raised.
     *
     * @throws ControlApiException {@link ControlErrorCode#MCP_WRITE_REFUSED} with {@code data.reason}
     */
    public void refuseIfGuarded(ControlSession session, String verb, String paneId,
                                McpPaneWriteState state) throws ControlApiException {
        McpPaneWriteState checked = state == null ? McpPaneWriteState.unknown(paneId) : state;
        String reason = checked.refusal().orElse(null);
        if (reason == null) {
            return;
        }
        record(session, verb, paneId, "refused", reason, -1, false);
        throw new ControlApiException(ControlErrorCode.MCP_WRITE_REFUSED,
            "korTTY does not type into this pane for an MCP client right now",
            Map.of("pane", paneId, "reason", reason));
    }

    /**
     * Asks the user — or finds a matching session grant — and returns only when the write may go
     * ahead.
     *
     * @param session the MCP client's connection
     * @param verb the wire method
     * @param paneId the target pane
     * @param paneLabel the pane as the prompt names it
     * @param text the exact text, or the key names for {@code pane.send_keys}
     * @param submits whether the write submits a line
     * @throws ControlApiException {@link ControlErrorCode#MCP_WRITE_DENIED} with {@code data.reason}
     *     {@value #REASON_DENIED}, {@value #REASON_TIMEOUT} or {@value #REASON_NO_PROMPT}
     */
    public void authorize(ControlSession session, String verb, String paneId, String paneLabel,
                          String text, boolean submits) throws ControlApiException {
        Objects.requireNonNull(session, "session");
        String body = text == null ? "" : text;
        boolean sessionOffered = offersSession(verb, body, submits);
        String key = grantKey(session, paneId);
        if (sessionOffered && hasGrant(key)) {
            record(session, verb, paneId, "allowed", "session_grant", body.length(), submits);
            return;
        }
        Request request = new Request(displayClient(session.client()), paneId,
            paneLabel == null || paneLabel.isBlank() ? paneId : paneLabel, verb, body, submits,
            sessionOffered);
        Decision decision;
        String failure;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if (!promptLock.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("another consent prompt stayed open");
            }
            try {
                long remaining = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                decision = prompter.ask(request, remaining);
                failure = null;
            } finally {
                promptLock.release();
            }
        } catch (TimeoutException e) {
            decision = Decision.DENY;
            failure = REASON_TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = Decision.DENY;
            failure = REASON_NO_PROMPT;
        } catch (RuntimeException e) {
            LOG.warn("The MCP write consent prompt could not be shown: {}", e.toString());
            decision = Decision.DENY;
            failure = REASON_NO_PROMPT;
        }
        if (decision == null) {
            decision = Decision.DENY;
        }
        if (decision == Decision.ALLOW_PANE_FOR_SESSION && !sessionOffered) {
            // A prompter that offered a button it was told not to offer gets no more than one write.
            decision = Decision.ALLOW_ONCE;
        }
        switch (decision) {
            case ALLOW_ONCE -> record(session, verb, paneId, "allowed", "once", body.length(), submits);
            case ALLOW_PANE_FOR_SESSION -> {
                addGrant(key);
                record(session, verb, paneId, "allowed", "pane_session", body.length(), submits);
            }
            default -> {
                String reason = failure == null ? REASON_DENIED : failure;
                record(session, verb, paneId, "denied", reason, body.length(), submits);
                throw new ControlApiException(ControlErrorCode.MCP_WRITE_DENIED,
                    REASON_TIMEOUT.equals(reason)
                        ? "Nobody allowed the write in korTTY in time"
                        : "The write was not allowed in korTTY",
                    Map.of("pane", paneId, "reason", reason));
            }
        }
    }

    /** Drops every session grant, so the next write of every MCP session asks again. */
    public void revokeAll() {
        synchronized (grants) {
            grants.clear();
        }
    }

    /** How many session grants are held; for tests. */
    int grantCount() {
        synchronized (grants) {
            return grants.size();
        }
    }

    /**
     * Whether "Allow for this pane in this session" may be offered: only for {@code pane.send_text}
     * that submits nothing — no {@code submit}, no line break and no other control character.
     */
    public static boolean offersSession(String verb, String text, boolean submits) {
        if (!VERB_SEND_TEXT.equals(verb) || submits || text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (isHidden(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The text as the prompt shows it: every control character, and every invisible formatting or
     * direction character, as a visible symbol, so nothing reaches the pane that the user could not
     * see. A line break stays a line break after its symbol so a multi-line text remains readable.
     */
    public static String visible(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                out.append("␍␊\n");
                i++;
            } else if (c == '\n') {
                out.append("␊\n");
            } else if (c == '\r') {
                out.append("␍\n");
            } else if (c < 0x20) {
                // The Unicode "control pictures" block: NUL is U+2400, ESC is U+241B.
                out.append((char) (0x2400 + c));
            } else if (c == 0x7f) {
                out.append('␡');
            } else if (isHidden(c)) {
                out.append(String.format("<U+%04X>", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The client name as the prompt and the audit line show it: only letters, digits and
     * {@code ._-+/@:()} and spaces, at most {@value #MAX_CLIENT_CHARS} characters, so a client cannot
     * forge a second log line or a misleading prompt layout with its name.
     */
    public static String displayClient(String client) {
        if (client == null || client.isBlank()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < client.length() && out.length() < MAX_CLIENT_CHARS; i++) {
            char c = client.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || " ._-+/@:()".indexOf(c) >= 0;
            out.append(safe ? c : '_');
        }
        return out.toString().strip().isEmpty() ? "unknown" : out.toString();
    }

    /** Control characters, C1 controls and the invisible formatting and direction characters. */
    private static boolean isHidden(char c) {
        if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
            return true;
        }
        return (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e)
            || (c >= 0x2060 && c <= 0x2069) || c == 0xfeff || c == 0x00ad || c == 0x061c;
    }

    /** One pane of one MCP session; the client name is part of it so two hosts never share one. */
    private static String grantKey(ControlSession session, String paneId) {
        String scope = session.mcpSession() != null
            ? "s:" + session.mcpSession() : "c:" + session.connectionId();
        return scope + '\u0000' + displayClient(session.client()) + '\u0000' + paneId;
    }

    private boolean hasGrant(String key) {
        synchronized (grants) {
            return grants.contains(key);
        }
    }

    private void addGrant(String key) {
        synchronized (grants) {
            if (grants.size() >= MAX_GRANTS) {
                grants.clear();
            }
            grants.add(key);
        }
    }

    /** One audit line per decision; counts and shapes only, never the text. Never throws. */
    private void record(ControlSession session, String verb, String paneId, String outcome,
                        String reason, int chars, boolean submits) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("verb", verb);
        detail.put("decision", outcome);
        detail.put("reason", reason);
        if (chars >= 0) {
            detail.put("chars", chars);
            detail.put("submits", submits);
        }
        detail.put("client", displayClient(session == null ? null : session.client()).replace(' ', '_'));
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, Object> entry : detail.entrySet()) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(entry.getKey()).append('=').append(entry.getValue());
        }
        try {
            audit.record(AUDIT_VERB, paneId, line.toString());
        } catch (RuntimeException e) {
            LOG.debug("The control-API audit sink failed for {}: {}", AUDIT_VERB, e.toString());
        }
    }
}
