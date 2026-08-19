package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Answers questions about one session journal. The AI only ever sees the curated document —
 * the information the journal already collected while the session ran (summaries, screenshot
 * analyses, notes) as a numbered context — never the raw capture log. When a question needs
 * log evidence, the model names literal search strings, {@link SessionJournalLogSearcher} runs
 * them internally, and only match statistics plus a few sample lines go back to the model.
 *
 * <p>Never throws: like {@link SessionJournalTopicSelector}, every failure mode degrades to a
 * deterministic answer (internal search over question tokens) with a localized warning.</p>
 */
public final class SessionJournalAskService {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalAskService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Context budgets in characters; small local models get a tighter budget than cloud models.
    static final int CLOUD_CONTEXT_CHARS = 16_000;
    static final int LOCAL_CONTEXT_CHARS = 8_000;
    static final int MIN_CONTEXT_CHARS = 1_000;
    static final int MAX_ENTRY_CHARS = 600;
    static final int MAX_TRANSCRIPT_EXCHANGES = 4;
    static final int MAX_TRANSCRIPT_CHARS = 4_000;
    static final int MAX_TERMS = 4;
    static final int MAX_HITS_PER_TERM = 50;
    static final int MAX_SNIPPETS_PER_TERM = 10;
    static final long CALL_TIMEOUT_SECONDS = 120;

    /** A journal entry the answer cites, in the order the model saw it. */
    public record Source(int ordinal, String entryId, String title) {
    }

    /** The internal log search's findings for one term; {@code hits} capped, the count exact. */
    public record LogEvidence(String term, long totalMatches, boolean truncated,
                              List<SessionJournalLogSearcher.Hit> hits) {
    }

    /** One earlier question/answer pair of the running conversation. */
    public record Exchange(String question, String answerMarkdown) {
    }

    /**
     * @param markdown    the answer text; null when the AI produced none (deterministic fallback)
     * @param sources     cited journal entries, deep-linkable via their entry ids
     * @param logEvidence internal log-search results backing the answer
     * @param aiUsed      false when the answer degraded to the deterministic search
     * @param warning     localized reason for a degraded answer, null when everything worked
     */
    public record Answer(String markdown, List<Source> sources, List<LogEvidence> logEvidence,
                         boolean aiUsed, String warning) {
    }

    private final SessionJournalService service;
    private final SessionJournalAiSupport.AiInvoker invoker;
    private final IntSupplier contextBudgetChars;

    SessionJournalAskService(SessionJournalService service,
                             SessionJournalAiSupport.AiInvoker invoker,
                             IntSupplier contextBudgetChars) {
        this.service = service;
        this.invoker = invoker;
        this.contextBudgetChars = contextBudgetChars;
    }

    /** Production instance following the journal AI profile (journal profile → default). */
    public static SessionJournalAskService application(SessionJournalService service) {
        return new SessionJournalAskService(service,
            SessionJournalAiSupport.applicationInvoker(),
            SessionJournalAskService::applicationContextBudget);
    }

    /** True when policy permits journal Q&amp;A and an AI profile is resolvable. */
    public boolean isAvailable() {
        return de.kortty.policy.PolicyManager.effective().sessionJournalAiAskAllowed()
            && invoker != null && invoker.isAvailable();
    }

    /**
     * Answers {@code question} about the journal. Runs synchronously — call it from a background
     * thread. Never throws; a null return means the question was blank.
     */
    public Answer ask(SessionJournalMeta meta, String question, List<Exchange> transcript,
                      String languageCode, BooleanSupplier cancelled) {
        if (meta == null || meta.getDirectory() == null
            || question == null || question.isBlank()) {
            return null;
        }
        List<SessionJournalEntry> entries = List.of();
        try {
            entries = service.loadDocument(meta.getDirectory()).getEntries();
        } catch (Exception e) {
            logger.warn("Journal ask could not load the document: {}", e.getMessage());
        }
        Context context = buildContext(entries, budget());
        if (!isAvailable()) {
            return deterministicAnswer(meta, context, question, cancelled,
                i18n("journal.ask.warning.unavailable",
                    "AI is unavailable; the journal was searched as text instead."));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SessionJournal-Ask");
            thread.setDaemon(true);
            return thread;
        });
        try {
            String system = SessionJournalPrompts.askSystemPrompt(languageCode);
            String user = SessionJournalPrompts.askUserPrompt(
                meta.getUsername(), meta.getHost(),
                meta.getStartedAt() != null ? meta.getStartedAt().format(TIME) : null,
                context.numberedLines(), transcriptLines(transcript), question);
            AiExecutionResult result = call(executor, system, user, cancelled);
            SessionJournalAiSupport.AskAnswer parsed = result != null
                ? SessionJournalAiSupport.parseAskAnswer(result.content(), context.ordinalEntries().size())
                : null;
            if (parsed == null) {
                return deterministicAnswer(meta, context, question, cancelled,
                    i18n("journal.ask.warning.failed",
                        "The AI request failed; the journal was searched as text instead."));
            }

            List<LogEvidence> evidence = searchTerms(meta, parsed.logSearchTerms(), cancelled);
            if (evidence.isEmpty()) {
                return new Answer(parsed.answer(), sources(context, parsed.sources()),
                    List.of(), true, null);
            }

            String groundingSystem = SessionJournalPrompts.askGroundingSystemPrompt(languageCode);
            String groundingUser = SessionJournalPrompts.askGroundingUserPrompt(
                question, parsed.answer(), evidenceLines(evidence));
            AiExecutionResult grounded = call(executor, groundingSystem, groundingUser, cancelled);
            SessionJournalAiSupport.AskAnswer groundedParsed = grounded != null
                ? SessionJournalAiSupport.parseAskAnswer(grounded.content(), context.ordinalEntries().size())
                : null;
            if (groundedParsed == null) {
                // The preliminary answer plus the raw evidence is still a useful result.
                return new Answer(parsed.answer(), sources(context, parsed.sources()),
                    evidence, true,
                    i18n("journal.ask.warning.groundingFailed",
                        "The final AI pass failed; the preliminary answer is shown with the raw search results."));
            }
            List<Integer> citedOrdinals = !groundedParsed.sources().isEmpty()
                ? groundedParsed.sources() : parsed.sources();
            return new Answer(groundedParsed.answer(), sources(context, citedOrdinals),
                evidence, true, null);
        } finally {
            executor.shutdownNow();
        }
    }

    /** One AI call with the timeout and cooperative cancellation; null on any failure. */
    private AiExecutionResult call(ExecutorService executor, String system, String user,
                                   BooleanSupplier cancelled) {
        Future<AiExecutionResult> future = executor.submit(() -> invoker.execute(system, user));
        long deadline = System.nanoTime() + CALL_TIMEOUT_SECONDS * 1_000_000_000L;
        try {
            while (true) {
                try {
                    return future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        future.cancel(true);
                        return null;
                    }
                    if (System.nanoTime() > deadline) {
                        future.cancel(true);
                        logger.warn("Journal ask timed out after {}s", CALL_TIMEOUT_SECONDS);
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            future.cancel(true);
            logger.warn("Journal ask failed: {}", e.getMessage());
            return null;
        }
    }

    // ==== context ====

    /** The numbered context the model sees, and the entries behind the ordinals (1-based). */
    record Context(List<String> numberedLines, List<SessionJournalEntry> ordinalEntries) {
    }

    /**
     * Selects entries into the char budget by usefulness — session summary first, then window
     * summaries, screenshot analyses, notes, the rest — and renders the survivors back in
     * chronological order as ordinals {@code 1..n}.
     */
    static Context buildContext(List<SessionJournalEntry> entries, int budgetChars) {
        if (entries == null || entries.isEmpty()) {
            return new Context(List.of("(the journal has no curated entries yet)"), List.of());
        }
        List<SessionJournalEntry> byPriority = new ArrayList<>(entries);
        byPriority.sort(Comparator.comparingInt(entry -> kindRank(entry.getKind())));
        Set<SessionJournalEntry> selected = new LinkedHashSet<>();
        int used = 0;
        for (SessionJournalEntry entry : byPriority) {
            String line = entryLine(0, entry);
            if (!selected.isEmpty() && used + line.length() > budgetChars) {
                continue; // smaller later entries may still fit
            }
            selected.add(entry);
            used += line.length();
        }
        List<SessionJournalEntry> ordinalEntries = new ArrayList<>();
        for (SessionJournalEntry entry : entries) { // original = chronological order
            if (selected.contains(entry)) {
                ordinalEntries.add(entry);
            }
        }
        List<String> lines = new ArrayList<>(ordinalEntries.size());
        for (int i = 0; i < ordinalEntries.size(); i++) {
            lines.add(entryLine(i + 1, ordinalEntries.get(i)));
        }
        return new Context(lines, ordinalEntries);
    }

    private static int kindRank(SessionJournalEntryKind kind) {
        if (kind == null) {
            return 6;
        }
        return switch (kind) {
            case SESSION_SUMMARY -> 0;
            case AI_SUMMARY -> 1;
            case SCREENSHOT -> 2;
            case USER_NOTE -> 3;
            case AGENT -> 4;
            case SYSTEM -> 5;
        };
    }

    /** "3. [AI_SUMMARY 2026-08-03 14:15] Title — text | tags: … | note: …", capped per entry. */
    static String entryLine(int ordinal, SessionJournalEntry entry) {
        StringBuilder sb = new StringBuilder(MAX_ENTRY_CHARS + 64);
        if (ordinal > 0) {
            sb.append(ordinal).append(". ");
        }
        sb.append('[').append(entry.getKind() != null ? entry.getKind().name() : "ENTRY");
        if (entry.getCreatedAt() != null) {
            sb.append(' ').append(entry.getCreatedAt().format(TIME));
        }
        sb.append("] ");
        int headerLength = sb.length();
        append(sb, entry.getTitle(), 120);
        appendPart(sb, headerLength, " — ", entry.getText(), 360);
        appendPart(sb, headerLength, " | screenshot: ", entry.getAiDescription(), 240);
        if (entry.getAiTags() != null && !entry.getAiTags().isEmpty()) {
            appendPart(sb, headerLength, " | tags: ", String.join(", ", entry.getAiTags()), 120);
        }
        appendPart(sb, headerLength, " | note: ", entry.getUserNote(), 200);
        if (sb.length() > headerLength + MAX_ENTRY_CHARS) {
            sb.setLength(headerLength + MAX_ENTRY_CHARS);
            sb.append('…');
        }
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, int headerLength,
                                   String separator, String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > headerLength) {
            sb.append(separator);
        }
        append(sb, value, maxChars);
    }

    private static void append(StringBuilder sb, String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return;
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ').trim();
        sb.append(flat.length() > maxChars ? flat.substring(0, maxChars) + "…" : flat);
    }

    /** The last exchanges as plain lines, bounded so history can never crowd out the context. */
    static List<String> transcriptLines(List<Exchange> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        List<Exchange> recent = transcript.size() > MAX_TRANSCRIPT_EXCHANGES
            ? transcript.subList(transcript.size() - MAX_TRANSCRIPT_EXCHANGES, transcript.size())
            : transcript;
        List<String> lines = new ArrayList<>(recent.size() * 2);
        int used = 0;
        for (Exchange exchange : recent) {
            StringBuilder q = new StringBuilder("Q: ");
            append(q, exchange.question(), 400);
            StringBuilder a = new StringBuilder("A: ");
            append(a, exchange.answerMarkdown(), 600);
            used += q.length() + a.length();
            if (used > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            lines.add(q.toString());
            lines.add(a.toString());
        }
        return lines;
    }

    // ==== internal log search ====

    private List<LogEvidence> searchTerms(SessionJournalMeta meta, List<String> terms,
                                          BooleanSupplier cancelled) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<LogEvidence> evidence = new ArrayList<>();
        for (String term : terms.subList(0, Math.min(terms.size(), MAX_TERMS))) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                break;
            }
            SessionJournalLogSearcher.Result result = SessionJournalLogSearcher.search(
                meta.getDirectory(),
                SessionJournalLogSearcher.Spec.ofLiteral(List.of(term)),
                MAX_HITS_PER_TERM, cancelled);
            evidence.add(new LogEvidence(term, result.totalMatches(), result.truncated(),
                result.hits()));
        }
        return evidence;
    }

    /** Per-term statistics plus a few sample lines — never the raw log — for the grounding pass. */
    static List<String> evidenceLines(List<LogEvidence> evidence) {
        List<String> lines = new ArrayList<>();
        for (LogEvidence item : evidence) {
            int shown = Math.min(item.hits().size(), MAX_SNIPPETS_PER_TERM);
            lines.add("\"" + item.term() + "\": " + item.totalMatches()
                + " matching log lines" + (shown > 0 ? " (" + shown + " shown)" : ""));
            for (int i = 0; i < shown; i++) {
                SessionJournalLogSearcher.Hit hit = item.hits().get(i);
                lines.add("  [" + hit.timestamp().format(TIME) + " " + hit.kind().key() + "] "
                    + hit.snippet() + (hit.repeat() > 1 ? " (x" + hit.repeat() + ")" : ""));
            }
        }
        return lines;
    }

    // ==== fallback ====

    /** No model involved: question tokens run through the internal search, entries text-matched. */
    private Answer deterministicAnswer(SessionJournalMeta meta, Context context, String question,
                                       BooleanSupplier cancelled, String warning) {
        List<String> terms = deterministicTerms(question);
        List<LogEvidence> evidence = searchTerms(meta, terms, cancelled);
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < context.ordinalEntries().size(); i++) {
            SessionJournalEntry entry = context.ordinalEntries().get(i);
            String haystack = entryLine(0, entry).toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                    sources.add(source(i + 1, entry));
                    break;
                }
            }
        }
        return new Answer(null, List.copyOf(sources), evidence, false, warning);
    }

    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "with", "this", "that", "were", "have", "which", "what", "when", "does",
        "und", "oder", "eine", "einem", "einen", "einer", "wurde", "wurden", "sind", "wird",
        "dass", "beim", "nach", "über", "welche", "welchem", "welchen", "gibt", "hat", "kann",
        "journal", "session", "sitzung");

    /** Question tokens worth searching: identifiers first, generic short words dropped. */
    static List<String> deterministicTerms(String question) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String raw : question.split("[^\\p{L}\\p{N}._/\\-]+")) {
            String token = raw.strip();
            if (token.length() < 4 || STOPWORDS.contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (token.contains(".") || token.contains("_") || token.contains("/")) {
                identifiers.add(token);
            } else {
                words.add(token);
            }
        }
        List<String> terms = new ArrayList<>(identifiers);
        for (String word : words) {
            if (terms.size() >= MAX_TERMS) {
                break;
            }
            terms.add(word);
        }
        return terms.size() > MAX_TERMS ? terms.subList(0, MAX_TERMS) : terms;
    }

    private static List<Source> sources(Context context, List<Integer> ordinals) {
        List<Source> sources = new ArrayList<>();
        for (int ordinal : ordinals) {
            if (ordinal >= 1 && ordinal <= context.ordinalEntries().size()) {
                sources.add(source(ordinal, context.ordinalEntries().get(ordinal - 1)));
            }
        }
        return List.copyOf(sources);
    }

    private static Source source(int ordinal, SessionJournalEntry entry) {
        String title = entry.getTitle();
        if (title == null || title.isBlank()) {
            title = entry.getKind() != null ? entry.getKind().name() : "";
        }
        return new Source(ordinal, entry.getId(), title);
    }

    // ==== budget ====

    private int budget() {
        try {
            return Math.max(MIN_CONTEXT_CHARS, contextBudgetChars.getAsInt());
        } catch (RuntimeException e) {
            return LOCAL_CONTEXT_CHARS;
        }
    }

    /** Provider-dependent default clamped by the profile's own selection limit. */
    private static int applicationContextBudget() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null;
            AiProfile profile = SessionJournalAiSupport.resolveProfile(settings);
            if (profile == null) {
                return LOCAL_CONTEXT_CHARS;
            }
            boolean local = profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI
                || (profile.getConnectionMode() != null && profile.getConnectionMode().isEmbedded())
                || isLocalEndpoint(profile.getApiUrl());
            int budget = local ? LOCAL_CONTEXT_CHARS : CLOUD_CONTEXT_CHARS;
            Integer maxSelection = profile.getMaxSelectionChars();
            if (maxSelection != null && maxSelection > 0) {
                budget = Math.min(budget, Math.max(MIN_CONTEXT_CHARS, maxSelection));
            }
            return budget;
        } catch (RuntimeException e) {
            return LOCAL_CONTEXT_CHARS;
        }
    }

    private static boolean isLocalEndpoint(String apiUrl) {
        if (apiUrl == null) {
            return false;
        }
        String lower = apiUrl.toLowerCase(Locale.ROOT);
        return lower.contains("localhost") || lower.contains("127.0.0.1");
    }

    private static String i18n(String key, String fallback) {
        String value = de.kortty.ui.I18n.get(key);
        return value != null && !value.equals(key) ? value : fallback;
    }
}
