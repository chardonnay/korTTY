package de.kortty.memory;

import com.google.gson.Gson;
import de.kortty.core.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the rule learning has been told and how much it has done: rules the user rejected for good ("Nie für diese
 * Regel"), rules postponed for a week ("Nicht jetzt"), the number of detection calls and of proposal cards per day,
 * and the one card per run. Persisted in {@value #FILE_NAME} in the memory directory, written atomically and
 * owner-only. A file that cannot be read starts empty and is replaced by the next change.
 *
 * <p>Rules are identified by {@link #ruleHash}, so a rule that is worded the same way after normalisation is the
 * same rule. The per-run guard is kept in memory only: a new application start may show one card again.</p>
 */
public final class LearningState {

    public static final String FILE_NAME = "learning.json";
    public static final int MAX_DETECTION_CALLS_PER_DAY = 10;
    public static final int MAX_PROPOSAL_CARDS_PER_DAY = 3;
    public static final Duration SNOOZE = Duration.ofDays(7);

    static final int MAX_REJECTED = 1000;
    static final int KEEP_DAYS = 7;
    private static final int MAX_CLAIMED_RUNS = 100;
    private static final int STATE_VERSION = 1;
    private static final Logger logger = LoggerFactory.getLogger(LearningState.class);
    private static final Gson GSON = new Gson();

    private final Path file;
    private final Clock clock;
    private final Set<String> claimedRuns = new LinkedHashSet<>();
    private Data data;

    /** The state in {@code memoryDirectory}, which holds {@value #FILE_NAME}; the file is read now. */
    public LearningState(Path memoryDirectory, Clock clock) {
        this.file = memoryDirectory.toAbsolutePath().normalize().resolve(FILE_NAME);
        this.clock = clock;
        this.data = load(file);
    }

    /** The hash that identifies a rule: its text, lower case and with single spaces. */
    public static String ruleHash(String ruleText) {
        String normalized = ruleText == null ? "" : ruleText.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        return MemoryFormat.sha256(normalized);
    }

    /** Whether the user rejected the rule for good. */
    public synchronized boolean isRejected(String ruleText) {
        return data.rejected.contains(ruleHash(ruleText));
    }

    /** Records "Nie für diese Regel". The rule is never proposed again; a snooze for it ends. */
    public synchronized void reject(String ruleText) {
        String hash = ruleHash(ruleText);
        data.rejected.remove(hash);
        data.rejected.add(hash);
        while (data.rejected.size() > MAX_REJECTED) {
            data.rejected.remove(0);
        }
        data.snoozed.remove(hash);
        save();
    }

    /** Whether the rule is postponed: snoozed and the snooze has not run out. */
    public synchronized boolean isSnoozed(String ruleText) {
        String until = data.snoozed.get(ruleHash(ruleText));
        return until != null && clock.instant().isBefore(Instant.parse(until));
    }

    /** Records "Nicht jetzt": the rule is not proposed again for {@link #SNOOZE}. */
    public synchronized void snooze(String ruleText) {
        data.snoozed.put(ruleHash(ruleText), clock.instant().plus(SNOOZE).toString());
        save();
    }

    /**
     * Takes one detection call from today's budget of {@link #MAX_DETECTION_CALLS_PER_DAY}. Returns false, and takes
     * nothing, when the budget is used up.
     */
    public synchronized boolean tryUseDetectionCall() {
        Day today = today();
        if (today.detections >= MAX_DETECTION_CALLS_PER_DAY) {
            return false;
        }
        today.detections++;
        save();
        return true;
    }

    public synchronized int detectionCallsToday() {
        return today().detections;
    }

    /**
     * Claims the proposal card of the run {@code runId}. Returns false when this run already showed a card, or when
     * today's budget of {@link #MAX_PROPOSAL_CARDS_PER_DAY} cards is used up. A blank run id counts as its own run.
     */
    public synchronized boolean claimProposalCard(String runId) {
        String run = runId == null || runId.isBlank() ? "" : runId.strip();
        if (!run.isEmpty() && claimedRuns.contains(run)) {
            return false;
        }
        Day today = today();
        if (today.cards >= MAX_PROPOSAL_CARDS_PER_DAY) {
            return false;
        }
        today.cards++;
        if (!run.isEmpty()) {
            claimedRuns.add(run);
            if (claimedRuns.size() > MAX_CLAIMED_RUNS) {
                Iterator<String> oldest = claimedRuns.iterator();
                oldest.next();
                oldest.remove();
            }
        }
        save();
        return true;
    }

    public synchronized int proposalCardsToday() {
        return today().cards;
    }

    private Day today() {
        LocalDate day = LocalDate.now(clock);
        return data.days.computeIfAbsent(day.toString(), key -> new Day());
    }

    private void save() {
        prune();
        try {
            Files.createDirectories(file.getParent());
            AtomicFileWriter.writeStoreAtomically(file, GSON.toJson(data), AtomicFileWriter.FileMode.OWNER_ONLY);
        } catch (IOException | RuntimeException e) {
            // The state stays in memory for this run; the learning must never fail a chat or an agent run.
            logger.warn("The rule learning state could not be saved to {}", file, e);
        }
    }

    private void prune() {
        Instant now = clock.instant();
        data.snoozed.values().removeIf(until -> !now.isBefore(Instant.parse(until)));
        LocalDate oldest = LocalDate.now(clock).minusDays(KEEP_DAYS);
        data.days.keySet().removeIf(day -> LocalDate.parse(day).isBefore(oldest));
    }

    private static Data load(Path file) {
        if (!Files.exists(file)) {
            return new Data();
        }
        try {
            Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            return loaded == null ? new Data() : loaded.normalized();
        } catch (IOException | RuntimeException e) {
            logger.warn("The rule learning state in {} is unreadable; it starts empty", file, e);
            return new Data();
        }
    }

    /** The persisted document. Fields may be missing in a hand-edited file, so every access goes through here. */
    private static final class Data {

        int version = STATE_VERSION;
        List<String> rejected = new ArrayList<>();
        Map<String, String> snoozed = new LinkedHashMap<>();
        Map<String, Day> days = new LinkedHashMap<>();

        /** Drops every entry that does not parse, so that no later read or prune can fail on it. */
        Data normalized() {
            if (rejected == null) {
                rejected = new ArrayList<>();
            }
            if (snoozed == null) {
                snoozed = new LinkedHashMap<>();
            }
            if (days == null) {
                days = new LinkedHashMap<>();
            }
            rejected.removeIf(hash -> hash == null || hash.isBlank());
            snoozed.entrySet().removeIf(entry -> !isInstant(entry.getKey(), entry.getValue()));
            days.entrySet().removeIf(entry -> !isDay(entry.getKey()) || entry.getValue() == null);
            return this;
        }

        private static boolean isInstant(String hash, String until) {
            if (hash == null || hash.isBlank() || until == null) {
                return false;
            }
            try {
                Instant.parse(until);
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }

        private static boolean isDay(String day) {
            if (day == null) {
                return false;
            }
            try {
                LocalDate.parse(day);
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
    }

    /** The counters of one day. */
    private static final class Day {

        int detections;
        int cards;
    }
}
