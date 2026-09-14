package de.kortty.control;

import org.slf4j.LoggerFactory;

/**
 * One audit line per API-driven action.
 *
 * <p>{@code detail} carries counts and shapes only — {@code bytes=28 bracketed=false submitted=true},
 * {@code keys=2}, {@code orientation=vertical} — never terminal text, preserving the Stage-2 privacy
 * invariant.
 *
 * <p>An implementation <strong>must not throw</strong>: {@code CodingAgentActions} calls its sink
 * directly from {@code explain()} and {@code rename()} and only swallows sink exceptions for
 * {@code sendText}/{@code sendKeys}/{@code prompt}, so a throwing sink would turn a successful action
 * into a JSON-RPC error.
 *
 * <p>Any thread.
 */
@FunctionalInterface
public interface ControlAuditSink {

    /**
     * Records one action.
     *
     * @param verb the wire method name
     * @param paneId the pane acted on, or null
     * @param detail counts and shapes only, never terminal text
     */
    void record(String verb, String paneId, String detail);

    /** The default sink: one slf4j INFO line, which is unconditional because journals are per-tab. */
    ControlAuditSink LOGGING = (verb, paneId, detail) ->
        LoggerFactory.getLogger(ControlAuditSink.class)
            .info("control-api {} pane={} {}", verb, paneId, detail);
}
