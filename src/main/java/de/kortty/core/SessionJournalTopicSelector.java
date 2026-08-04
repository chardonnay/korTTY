package de.kortty.core;

import de.kortty.model.SessionJournalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Picks the journal entries that belong to a topic, using the same AI plumbing as the summarizer.
 * Optional by design: when no profile is reachable, the policy forbids AI, or the model answers
 * with nonsense, the caller falls back to the deterministic text match and shows the warning this
 * class produces.
 *
 * <p>Entries go into the prompt as ordinals {@code 1..n} rather than as ids. That keeps journal
 * UUIDs out of the prompt entirely, saves a lot of tokens, and makes a hallucinated reference
 * trivially detectable — it is simply out of range.</p>
 */
public final class SessionJournalTopicSelector {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalTopicSelector.class);

    static final int MAX_ENTRIES_PER_PROMPT = 60;
    static final int MAX_PROMPTS = 8;
    static final int MAX_CHARS_PER_ENTRY = 600;
    static final long CALL_TIMEOUT_SECONDS = 60;
    static final long TOTAL_BUDGET_SECONDS = 180;

    private final SessionJournalAiSupport.AiInvoker invoker;

    /** Which entries the topic covers, and whether the AI actually decided that. */
    public record Selection(Set<String> entryIds, boolean aiUsed, String warning, int aiConsidered) {

        static Selection unavailable(String warning) {
            return new Selection(Set.of(), false, warning, 0);
        }
    }

    SessionJournalTopicSelector(SessionJournalAiSupport.AiInvoker invoker) {
        this.invoker = invoker;
    }

    public static SessionJournalTopicSelector application() {
        return new SessionJournalTopicSelector(SessionJournalAiSupport.applicationInvoker());
    }

    public boolean isAvailable() {
        return invoker != null && invoker.isAvailable();
    }

    /**
     * The ids of the entries the AI considers part of {@code topic}. Never throws: every failure
     * mode returns {@code aiUsed=false} plus a warning so the export can carry on with the text
     * match instead of dying on an unreachable model.
     */
    public Selection select(List<SessionJournalEntry> candidates, String topic, String languageCode) {
        if (candidates == null || candidates.isEmpty()) {
            return new Selection(Set.of(), false, null, 0);
        }
        if (topic == null || topic.isBlank()) {
            return new Selection(Set.of(), false, null, 0);
        }
        if (!isAvailable()) {
            return Selection.unavailable(i18n("journal.export.ai.unavailable",
                "AI selection is unavailable; the topic was matched as text instead."));
        }

        int ceiling = MAX_ENTRIES_PER_PROMPT * MAX_PROMPTS;
        List<SessionJournalEntry> considered = candidates.size() > ceiling
            ? candidates.subList(0, ceiling) : candidates;
        String tailWarning = candidates.size() > ceiling
            ? i18n("journal.export.ai.truncated",
                "Only the first {0} entries went through the AI; the rest were matched as text.")
                .replace("{0}", String.valueOf(ceiling))
            : null;

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SessionJournal-TopicSelect");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Set<String> selected = new LinkedHashSet<>();
            long deadline = System.nanoTime() + TOTAL_BUDGET_SECONDS * 1_000_000_000L;
            for (int offset = 0; offset < considered.size(); offset += MAX_ENTRIES_PER_PROMPT) {
                if (System.nanoTime() > deadline) {
                    return degraded(selected, considered.size(), i18n("journal.export.ai.timeout",
                        "The AI selection ran out of time; the topic was matched as text instead."));
                }
                List<SessionJournalEntry> chunk = considered.subList(
                    offset, Math.min(offset + MAX_ENTRIES_PER_PROMPT, considered.size()));
                List<Integer> ordinals = askChunk(executor, chunk, topic, languageCode);
                if (ordinals == null) {
                    return degraded(selected, considered.size(), i18n("journal.export.ai.failed",
                        "The AI selection failed; the topic was matched as text instead."));
                }
                for (int ordinal : ordinals) {
                    SessionJournalEntry entry = chunk.get(ordinal - 1);
                    if (entry.getId() != null) {
                        selected.add(entry.getId());
                    }
                }
            }
            return new Selection(Set.copyOf(selected), true, tailWarning, considered.size());
        } finally {
            executor.shutdownNow();
        }
    }

    /** A partial AI result is not trustworthy as a whole, so the caller falls back wholesale. */
    private static Selection degraded(Set<String> partial, int considered, String warning) {
        return new Selection(Set.of(), false, warning, considered);
    }

    /** The ordinals the model picked for one chunk, or {@code null} when the call did not work. */
    private List<Integer> askChunk(ExecutorService executor, List<SessionJournalEntry> chunk,
                                   String topic, String languageCode) {
        String system = SessionJournalPrompts.topicSelectionSystemPrompt(languageCode);
        String user = SessionJournalPrompts.topicSelectionUserPrompt(topic, numberedEntries(chunk));
        Future<AiExecutionResult> future = executor.submit(() -> invoker.execute(system, user));
        try {
            AiExecutionResult result = future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return SessionJournalAiSupport.parseIdSelection(
                result != null ? result.content() : null, chunk.size());
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("Session journal topic selection timed out after {}s", CALL_TIMEOUT_SECONDS);
            return null;
        } catch (Exception e) {
            future.cancel(true);
            logger.warn("Session journal topic selection failed: {}", e.getMessage());
            return null;
        }
    }

    /** "1. Title — summary", each entry capped so one long entry cannot crowd out the rest. */
    static List<String> numberedEntries(List<SessionJournalEntry> chunk) {
        List<String> lines = new ArrayList<>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            SessionJournalEntry entry = chunk.get(i);
            StringBuilder sb = new StringBuilder(MAX_CHARS_PER_ENTRY + 8);
            sb.append(i + 1).append(". ");
            append(sb, entry.getTitle(), 120);
            if (entry.getText() != null && !entry.getText().isBlank()) {
                if (sb.length() > String.valueOf(i + 1).length() + 2) {
                    sb.append(" — ");
                }
                append(sb, entry.getText(), 360);
            }
            if (entry.getUserNote() != null && !entry.getUserNote().isBlank()) {
                sb.append(" | ");
                append(sb, entry.getUserNote(), 120);
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static void append(StringBuilder sb, String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return;
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ').trim();
        sb.append(flat.length() > maxChars ? flat.substring(0, maxChars) + "…" : flat);
    }

    private static String i18n(String key, String fallback) {
        String value = de.kortty.ui.I18n.get(key);
        return value != null && !value.equals(key) ? value : fallback;
    }
}
