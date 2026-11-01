package de.kortty.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import de.kortty.core.AiSecretGuard;
import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cleans up one memory file in three separate steps. Each step proposes a complete new text, which the user sees as a
 * diff before anything is saved:
 * <ul>
 *   <li>merging and de-duplicating the korTTY entries of the file: one AI call that sees only those entries; the
 *       result is written as a korTTY change with a consent granted at the moment of confirmation, so the store still
 *       proves that the user's own text is untouched;</li>
 *   <li>archiving korTTY entries older than {@value #ARCHIVE_AFTER_DAYS} days into {@code archive.md};</li>
 *   <li>shortening the user's own paragraphs: a separate proposal, saved as the user's own change, never combined with
 *       the merge.</li>
 * </ul>
 * The AI call goes through {@link Assistant}; the caller masks the prompt for its provider. Nothing here writes on its
 * own: the proposal methods only compute, and the apply methods run after the user confirmed.
 */
public final class MemoryCleanupService {

    /** korTTY entries older than this many days are offered for archiving. */
    public static final int ARCHIVE_AFTER_DAYS = 180;
    /** Longest rule the merge keeps; longer rules are shortened and counted. */
    static final int MAX_RULE_CHARS = MemoryStore.MAX_ENTRY_CHARS;
    static final String MERGE_SOURCE = "merge";
    static final String MERGE_SYSTEM_PROMPT = "You clean up a list of short rules that an AI assistant keeps for the user. "
        + "Merge rules that say the same thing and remove duplicates; keep every distinct instruction. "
        + "Do not add rules, do not invent details and keep the language of each rule. "
        + "Each rule is one imperative sentence of at most 300 characters. Never write passwords, tokens or other secrets. "
        + "Answer only with JSON of the form {\"rules\": [\"...\"]}.";
    static final String SHORTEN_SYSTEM_PROMPT = "You shorten paragraphs of the user's own notes for an AI memory. "
        + "Keep every fact, instruction, name, command and piece of code exactly as it is; only remove words that repeat "
        + "or add nothing. Return the same number of paragraphs in the same order, each as one paragraph without blank "
        + "lines, and keep the language. Answer only with JSON of the form {\"paragraphs\": [\"...\"]}.";

    private static final Gson GSON = new Gson();

    /** The AI call the cleanup needs. The caller masks {@code userPrompt} for the provider before it is sent. */
    public interface Assistant {

        String complete(String systemPrompt, String userPrompt) throws Exception;
    }

    /** What a proposal changes. */
    public enum Kind {
        /** The korTTY entries are merged; the user's text stays as it is. */
        MERGE_KORTTY_ENTRIES,
        /** The user's own paragraphs are shortened. */
        SHORTEN_USER_TEXT
    }

    /** Why a proposal could not be made; the file is left as it is. */
    public enum Problem {
        /** The file has no korTTY entries to merge. */
        NOTHING_TO_MERGE,
        /** The file has no paragraphs of the user's own to shorten. */
        NOTHING_TO_SHORTEN,
        /** The assistant returned nothing. */
        NO_REPLY,
        /** The reply is not the JSON the request asked for, or it is empty. */
        MALFORMED_REPLY,
        /** The merge would add rules, or the reply has another number of paragraphs than the file. */
        COUNT_MISMATCH,
        /** None of the suggested changes differs from the text or is an actual shortening. */
        NOTHING_TO_CHANGE
    }

    /** A proposal that cannot be made. */
    public static final class CleanupException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Problem problem;

        public CleanupException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * A complete new text for one memory file, ready to show as a diff. Only this class makes one, so a confirmed write
     * can name only a text that the proposal methods computed.
     */
    public static final class Proposal {

        private final Kind kind;
        private final MemoryScope scope;
        private final String baseDigest;
        private final String currentText;
        private final String proposedText;
        private final int changedCount;

        /**
         * @param baseDigest   the digest of the text the proposal was made from; the save must name it
         * @param currentText  the text the proposal was made from
         * @param proposedText the text to save when the user confirms
         * @param changedCount the rules shortened to the maximum length (merge), or the paragraphs changed (shorten)
         */
        Proposal(Kind kind, MemoryScope scope, String baseDigest, String currentText, String proposedText,
                 int changedCount) {
            this.kind = kind;
            this.scope = scope;
            this.baseDigest = baseDigest;
            this.currentText = currentText;
            this.proposedText = proposedText;
            this.changedCount = changedCount;
        }

        /** What changes. */
        public Kind kind() {
            return kind;
        }

        /** The file. */
        public MemoryScope scope() {
            return scope;
        }

        /** The digest of the text the proposal was made from. */
        public String baseDigest() {
            return baseDigest;
        }

        /** The text the proposal was made from. */
        public String currentText() {
            return currentText;
        }

        /** The text to save when the user confirms. */
        public String proposedText() {
            return proposedText;
        }

        /** The rules shortened to the maximum length (merge), or the paragraphs changed (shorten). */
        public int changedCount() {
            return changedCount;
        }
    }

    private final Clock clock;

    public MemoryCleanupService() {
        this(Clock.systemDefaultZone());
    }

    public MemoryCleanupService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Proposes one merged list of korTTY entries. The reply's rules replace the old entries as fresh fences; the
     * user's own text is kept, and the proposal is refused when the rules are malformed, add rules or contain a secret.
     */
    public Proposal proposeMerge(MemoryScope scope, MemoryStore.Snapshot current, Assistant assistant) throws Exception {
        List<KorttyEntry> entries = MemoryFormat.parse(current.text()).entries();
        if (entries.isEmpty()) {
            throw new CleanupException(Problem.NOTHING_TO_MERGE);
        }
        List<String> bodies = entries.stream().map(KorttyEntry::body).toList();
        String reply = assistant.complete(MERGE_SYSTEM_PROMPT, GSON.toJson(bodies));
        List<String> rules = new ArrayList<>();
        int shortened = 0;
        for (String item : parseStrings(reply, "rules")) {
            String rule = singleLine(item);
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.length() > MAX_RULE_CHARS) {
                rule = truncate(rule, MAX_RULE_CHARS);
                shortened++;
            }
            rules.add(rule);
        }
        if (rules.isEmpty()) {
            throw new CleanupException(Problem.MALFORMED_REPLY);
        }
        if (rules.size() > entries.size()) {
            throw new CleanupException(Problem.COUNT_MISMATCH);
        }
        String proposed = rebuildWithRules(current.text(), rules);
        requireUserTextUnchanged(current.text(), proposed);
        requireNoSecret(proposed);
        return new Proposal(Kind.MERGE_KORTTY_ENTRIES, scope, current.sha256(), current.text(), proposed, shortened);
    }

    /**
     * Proposes shorter versions of the user's own paragraphs. Each paragraph is replaced only by a shorter one with
     * no blank line in it; the others stay as they are.
     */
    public Proposal proposeUserShortening(MemoryScope scope, MemoryStore.Snapshot current, Assistant assistant)
        throws Exception {
        List<MemoryEntriesModel.Row> rows = MemoryEntriesModel.rows(current.text(), MemoryBlock.empty(), false)
            .stream()
            .filter(MemoryEntriesModel.Row::isUserText)
            .toList();
        if (rows.isEmpty()) {
            throw new CleanupException(Problem.NOTHING_TO_SHORTEN);
        }
        List<String> originals = rows.stream().map(MemoryEntriesModel.Row::text).toList();
        String reply = assistant.complete(SHORTEN_SYSTEM_PROMPT, GSON.toJson(originals));
        List<String> replies = parseStrings(reply, "paragraphs");
        if (replies.size() != originals.size()) {
            throw new CleanupException(Problem.COUNT_MISMATCH);
        }
        String text = current.text();
        int changed = 0;
        // Back to front: a replaced paragraph never moves the ones before it.
        for (int index = rows.size() - 1; index >= 0; index--) {
            String candidate = replies.get(index).strip();
            String original = originals.get(index);
            if (candidate.isEmpty() || candidate.equals(original) || candidate.length() >= original.length()
                || candidate.matches("(?s).*\\R\\s*\\R.*") || hasFenceMarker(candidate)) {
                continue;
            }
            text = MemoryRowEdit.replaceUserParagraph(text, rows.get(index), candidate);
            changed++;
        }
        if (changed == 0) {
            throw new CleanupException(Problem.NOTHING_TO_CHANGE);
        }
        requireNoSecret(text);
        return new Proposal(Kind.SHORTEN_USER_TEXT, scope, current.sha256(), current.text(), text, changed);
    }

    /**
     * Writes a confirmed merge. The consent is granted now, for this one change; the store refuses the write if the
     * digest changed since the proposal or if the user's own text would change.
     */
    public MemoryStore.Snapshot applyMerge(MemoryStore store, Proposal proposal) throws IOException {
        if (proposal.kind() != Kind.MERGE_KORTTY_ENTRIES) {
            throw new IllegalArgumentException("Only a merge is written as a korTTY change.");
        }
        MemoryConsent consent = MemoryConsent.grant(proposal.scope(), clock.instant());
        return store.write(proposal.scope(), proposal.baseDigest(), proposal.proposedText(),
            MemoryStore.WriteReason.KORTTY_CONSENTED, consent);
    }

    /** Writes a confirmed shortening of the user's own text, as the user's own save. */
    public MemoryStore.Snapshot applyUserShortening(MemoryUserActions actions, Proposal proposal) throws IOException {
        if (proposal.kind() != Kind.SHORTEN_USER_TEXT) {
            throw new IllegalArgumentException("Only a shortening of the user's text is saved as the user's save.");
        }
        return actions.save(proposal.scope(), proposal.baseDigest(), proposal.proposedText());
    }

    /** The korTTY entries of {@code text} created more than {@value #ARCHIVE_AFTER_DAYS} days before {@code today}. */
    public static List<KorttyEntry> archiveCandidates(String text, LocalDate today) {
        LocalDate cutoff = today.minusDays(ARCHIVE_AFTER_DAYS);
        return MemoryFormat.parse(text).entries().stream()
            .filter(entry -> createdBefore(entry, cutoff))
            .sorted(Comparator.comparing(KorttyEntry::created))
            .toList();
    }

    /** Moves the chosen korTTY entries into the archive, as a confirmed korTTY change. */
    public MemoryStore.Snapshot archive(MemoryStore store, MemoryScope scope, Set<String> entryIds) throws IOException {
        MemoryConsent consent = MemoryConsent.grant(scope, clock.instant());
        return store.archiveKorttyEntries(scope, Set.copyOf(entryIds), consent);
    }

    private static boolean createdBefore(KorttyEntry entry, LocalDate cutoff) {
        try {
            return LocalDate.parse(entry.created()).isBefore(cutoff);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * The text with the user's paragraphs kept as the store counts them and every korTTY entry replaced by the new
     * fences, appended in order. The shape matches what the store writes for a removal, so the user's text joins up the
     * same way.
     */
    String rebuildWithRules(String text, List<String> rules) {
        StringBuilder out = new StringBuilder();
        for (Segment segment : MemoryFormat.parse(text).segments()) {
            if (segment instanceof UserText user) {
                String kept = MemoryFormat.trimBlankEdges(user.text());
                out.append(kept);
                if (!kept.isEmpty() && !kept.endsWith("\n")) {
                    out.append('\n');
                }
            }
        }
        LocalDate today = LocalDate.now(clock);
        Set<String> ids = new HashSet<>();
        for (String rule : rules) {
            if (!out.isEmpty()) {
                out.append(out.charAt(out.length() - 1) == '\n' ? "\n" : "\n\n");
            }
            out.append(MemoryFormat.newEntry(newId(ids), "- " + rule, today, MERGE_SOURCE));
        }
        return out.toString();
    }

    /** Whether the text holds a fence marker. Only korTTY writes those; a paragraph with one would become an entry. */
    private static boolean hasFenceMarker(String text) {
        return !MemoryFormat.withoutFenceMarkers(text).equals(text);
    }

    private static void requireUserTextUnchanged(String before, String after) throws MemoryInvariantException {
        if (!MemoryFormat.userSegments(before).equals(MemoryFormat.userSegments(after))) {
            throw new MemoryInvariantException("A merge may not change the text the user wrote.");
        }
    }

    private static void requireNoSecret(String text) throws MemorySecretException {
        AiSecretGuard.Result check = AiSecretGuard.check(MemoryFormat.textForSecretCheck(text));
        if (!check.ok()) {
            throw new MemorySecretException(check.first().orElseThrow());
        }
    }

    private static String newId(Set<String> used) {
        while (true) {
            String id = String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
            if (used.add(id)) {
                return id;
            }
        }
    }

    /**
     * The strings of a JSON reply: a bare array, or the array under {@code key} of an object. Text around the JSON, such
     * as a code fence, is ignored.
     */
    static List<String> parseStrings(String reply, String key) {
        if (reply == null || reply.isBlank()) {
            throw new CleanupException(Problem.NO_REPLY);
        }
        int start = firstIndexOf(reply, '{', '[');
        int end = Math.max(reply.lastIndexOf('}'), reply.lastIndexOf(']'));
        if (start < 0 || end < start) {
            throw new CleanupException(Problem.MALFORMED_REPLY);
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(reply.substring(start, end + 1));
        } catch (RuntimeException malformed) {
            throw new CleanupException(Problem.MALFORMED_REPLY);
        }
        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().get(key) instanceof JsonArray found) {
            array = found;
        } else {
            throw new CleanupException(Problem.MALFORMED_REPLY);
        }
        List<String> strings = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new CleanupException(Problem.MALFORMED_REPLY);
            }
            strings.add(element.getAsString());
        }
        return strings;
    }

    private static int firstIndexOf(String text, char first, char second) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** The first {@code max} characters, cut back to a word boundary when one is near the end. */
    private static String truncate(String text, int max) {
        String cut = text.substring(0, max);
        int space = cut.lastIndexOf(' ');
        return (space > max / 2 ? cut.substring(0, space) : cut).stripTrailing();
    }
}
