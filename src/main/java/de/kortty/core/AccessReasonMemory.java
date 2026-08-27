package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Remembers the access reasons answered inside one terminal tab.
 *
 * <p>A CyberArk-style jump host asks for the reason of the operation on every authentication, so
 * splitting a tab put the very same dialog in front of the user again although they had already
 * answered it when the tab was opened. Suppressing the prompt alone is not enough: the answer still
 * has to be <em>sent</em>, because a server that asks for a reason closes the connection when the
 * reply is empty. The answer is therefore kept for the lifetime of the tab and replayed.
 *
 * <p>One instance belongs to one {@code TerminalView}, which is one tab including all of its split
 * panes. Answers never cross tabs, and they are never persisted: a new tab asks again.
 */
public final class AccessReasonMemory {

    private static final Logger logger = LoggerFactory.getLogger(AccessReasonMemory.class);

    private final Map<String, String> answers = new ConcurrentHashMap<>();

    /**
     * Answers one access-reason prompt, asking the user only the first time this tab sees it.
     *
     * <p>Both the target and the prompt are part of the key: a split to a different account, or a
     * server asking something else than before, is a question this tab has not answered yet.
     *
     * @param target the connection the prompt belongs to, as {@code user@host:port}
     * @param prompt the server's prompt text
     * @param ask shows the dialog; only called when this tab has no answer yet
     * @return the reason to send, which may be empty when the user cancels
     */
    public String answer(String target, String prompt, Supplier<String> ask) {
        String key = key(target, prompt);
        String remembered = answers.get(key);
        if (remembered != null) {
            logger.info("Reusing the access reason already answered in this tab for {}", target);
            return remembered;
        }
        String reason = ask.get();
        // A cancelled dialog must not be remembered: replaying an empty reason would silently hand
        // every later split the one answer that makes the server drop the connection.
        if (reason != null && !reason.isBlank()) {
            answers.put(key, reason);
        }
        return reason != null ? reason : "";
    }

    /** @return whether this tab has already answered that prompt. */
    public boolean remembers(String target, String prompt) {
        return answers.containsKey(key(target, prompt));
    }

    /**
     * Drops a remembered answer after the server refused it.
     *
     * <p>A reason stays valid only as long as what it refers to — a ticket number expires while the
     * tab it was typed in is still open. Without this, that tab would replay the stale reason into
     * every later split and fail every time, with no dialog left to correct it.
     */
    public void forget(String target, String prompt) {
        if (answers.remove(key(target, prompt)) != null) {
            logger.info("Forgot the access reason for {} after the server refused it", target);
        }
    }

    private static String key(String target, String prompt) {
        return (target != null ? target : "") + " | " + (prompt != null ? prompt.trim() : "");
    }
}
