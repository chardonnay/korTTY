package de.kortty.ui;

import de.kortty.core.SessionJournalHtmlRenderer;
import de.kortty.core.SessionJournalLogEntry;

import java.time.ZoneId;
import java.util.List;

/**
 * Builds the {@code korttyAppendLog([...])} call that pushes live capture-log batches into the
 * loaded journal page. The per-entry literal comes from
 * {@link SessionJournalHtmlRenderer#logEntryJs} — the same mapping that builds the page's embedded
 * {@code LOG} array — so the push path can never drift from the page. Deliberately JavaFX-free.
 */
final class SessionJournalLiveScript {

    private SessionJournalLiveScript() {
    }

    /** The guarded append call for one batch; an empty batch yields an empty string (no-op). */
    static String appendLogCall(List<SessionJournalLogEntry> batch, ZoneId zone,
                                String hiddenInputText, String screenshotLabel) {
        if (batch == null || batch.isEmpty()) {
            return "";
        }
        StringBuilder script = new StringBuilder(batch.size() * 48 + 64);
        script.append("if(window.korttyAppendLog){window.korttyAppendLog([");
        boolean first = true;
        for (SessionJournalLogEntry entry : batch) {
            if (!first) {
                script.append(',');
            }
            first = false;
            script.append(SessionJournalHtmlRenderer.logEntryJs(
                entry, zone, hiddenInputText, screenshotLabel));
        }
        script.append("]);}");
        return script.toString();
    }
}
