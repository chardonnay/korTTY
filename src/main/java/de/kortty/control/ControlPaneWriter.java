package de.kortty.control;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code pane.*} write path: arbitrary panes, with no registered-agent precondition.
 *
 * <p>This deliberately does <strong>not</strong> go through {@code CodingAgentActions}, every verb of
 * which begins with {@code requireEntry(pane)} and would answer {@code pane_not_found} for a
 * perfectly valid agent-less pane.
 *
 * <p>Any thread, never the JavaFX application thread. The bracketed-paste probe, the host-shortcut
 * check and the write itself run inside <strong>one</strong> {@link UiDispatcher} hop, so the write
 * has exactly the visibility and ordering guarantees of a user keystroke and nothing can change
 * between the check and the write.
 *
 * <p>The audit line is recorded inside that same hop, immediately after the write, and deliberately
 * <strong>not</strong> after {@link UiCalls#await} returns: {@code await} does not cancel the task
 * when its budget expires, so a hop that timed out while a nested FX event loop was up still types
 * the bytes into the pane once the toolkit drains. Auditing on the calling thread would skip that
 * write's log line altogether — and with it the first-write takeover notification, which
 * {@code ControlApiWiring}'s sink hangs off the very same call — leaving a write that is neither
 * logged nor announced.
 */
public final class ControlPaneWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ControlPaneWriter.class);

    /** The wire name of the text verb, used for the audit line. */
    private static final String VERB_SEND_TEXT = "pane.send_text";

    /** The wire name of the key verb. */
    private static final String VERB_SEND_KEYS = "pane.send_keys";

    /** The wire name of the command verb. */
    private static final String VERB_RUN = "pane.run";

    private final ControlSurface surface;

    private final UiDispatcher ui;

    private final ControlAuditSink audit;

    /**
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param audit the audit sink; {@link ControlAuditSink#LOGGING} when null
     */
    public ControlPaneWriter(ControlSurface surface, UiDispatcher ui, ControlAuditSink audit) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.audit = audit == null ? ControlAuditSink.LOGGING : audit;
    }

    /**
     * Types text into a pane.
     *
     * @param paneId the resolved pane id
     * @param text the text; {@code \n} is normalised to {@code \r}
     * @param submit whether to append a carriage return
     * @param bracketedMode {@code auto}, {@code never} or {@code always}
     * @param allowShortcutConflict whether to write even though korTTY's own AI shortcut would
     *     swallow the first line
     * @throws ControlApiException {@link ControlErrorCode#EMPTY_INPUT},
     *     {@link ControlErrorCode#INVALID_PARAMS}, {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#NOT_CONNECTED}, {@link ControlErrorCode#WRITE_FAILED},
     *     {@link ControlErrorCode#HOST_SHORTCUT_CONFLICT}
     */
    public WriteResult sendText(String paneId, String text, boolean submit, String bracketedMode,
                                boolean allowShortcutConflict) throws ControlApiException {
        return writeText(VERB_SEND_TEXT, paneId, text, submit, bracketedMode, allowShortcutConflict);
    }

    /**
     * Presses keys in a pane.
     *
     * @param paneId the resolved pane id
     * @param keyNames the {@link ControlKeyTable} names, in order
     * @throws ControlApiException {@link ControlErrorCode#EMPTY_INPUT},
     *     {@link ControlErrorCode#UNKNOWN_KEY}, {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#NOT_CONNECTED}, {@link ControlErrorCode#WRITE_FAILED}
     */
    public WriteResult sendKeys(String paneId, List<String> keyNames) throws ControlApiException {
        requirePane(paneId);
        List<String> normalised = ControlKeyTable.normalise(keyNames);
        byte[] payload = ControlKeyTable.encodeAll(normalised);
        int written = UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
            () -> unchecked(() -> {
                int bytes = surface.write(paneId, payload);
                record(VERB_SEND_KEYS, paneId, "keys=" + normalised.size() + " bytes=" + bytes);
                return bytes;
            }));
        return new WriteResult(paneId, written, false, false, normalised);
    }

    /**
     * Runs one command line in a pane: the text plus a carriage return.
     *
     * <p>An embedded line break is {@link ControlErrorCode#INVALID_PARAMS}, so one audited "command"
     * cannot smuggle a second one past a reviewer reading the audit log.
     *
     * @throws ControlApiException as {@link #sendText}, plus
     *     {@link ControlErrorCode#INVALID_PARAMS} for an embedded line break
     */
    public WriteResult run(String paneId, String command) throws ControlApiException {
        if (command != null && (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "A command must be a single line",
                Map.of("param", "command", "hint", "send several commands as several pane.run calls"));
        }
        return writeText(VERB_RUN, paneId, command, true, "never", false);
    }

    private WriteResult writeText(String verb, String paneId, String text, boolean submit,
                                  String bracketedMode, boolean allowShortcutConflict)
            throws ControlApiException {
        requirePane(paneId);
        if (text == null || text.isBlank()) {
            throw new ControlApiException(ControlErrorCode.EMPTY_INPUT, "Nothing to send",
                Map.of("param", VERB_RUN.equals(verb) ? "command" : "text"));
        }
        // Reject a bad spelling before the hop so a typo never reaches the terminal.
        BracketedPaste.shouldBracket(bracketedMode, text, false);
        WriteResult result = UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> unchecked(() -> {
            boolean bracketed =
                BracketedPaste.shouldBracket(bracketedMode, text, surface.isBracketedPasteEnabled(paneId));
            if (!bracketed && !allowShortcutConflict && surface.wouldHostShortcutIntercept(firstLine(text))) {
                throw new ControlApiException(ControlErrorCode.HOST_SHORTCUT_CONFLICT,
                    "The first line starts with korTTY's own AI shortcut command '"
                        + surface.hostShortcutCommandName() + "' and would not reach the pane",
                    Map.of("pane", paneId, "shortcut", String.valueOf(surface.hostShortcutCommandName()),
                        "hint", "pass allow_shortcut_conflict:true to write it anyway"));
            }
            byte[] payload = BracketedPaste.encode(text, bracketed, submit);
            int written = surface.write(paneId, payload);
            record(verb, paneId, "bytes=" + written + " bracketed=" + bracketed
                + " submitted=" + submit);
            return new WriteResult(paneId, written, bracketed, submit, List.of());
        }));
        return result;
    }

    private static String firstLine(String text) {
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        int newline = normalised.indexOf('\n');
        return newline < 0 ? normalised : normalised.substring(0, newline);
    }

    private static void requirePane(String paneId) throws ControlApiException {
        if (paneId == null || paneId.isBlank()) {
            throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND, "No pane given",
                Map.of("param", "pane"));
        }
    }

    /**
     * Records one audit line. A sink that throws is swallowed at debug level, matching
     * {@code CodingAgentActions}: a successful write must not become a JSON-RPC error.
     */
    private void record(String verb, String paneId, String detail) {
        try {
            audit.record(verb, paneId, detail);
        } catch (RuntimeException e) {
            LOG.debug("The control-API audit sink failed for {}: {}", verb, e.toString());
        }
    }

    /** Lets a {@link ControlApiException} cross a {@link java.util.function.Supplier} boundary. */
    private static <T> T unchecked(ThrowingSupplier<T> body) {
        try {
            return body.get();
        } catch (ControlApiException e) {
            throw new CompletionException(e);
        }
    }

    /** A supplier whose body may fail with the package's checked exception. */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ControlApiException;
    }
}
