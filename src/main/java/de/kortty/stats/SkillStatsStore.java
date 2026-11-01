package de.kortty.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.kortty.core.AtomicFileWriter;
import de.kortty.core.AtomicFileWriter.FileMode;
import de.kortty.KorTTYApplication;
import de.kortty.model.AiSkill;
import de.kortty.model.AiTokenizerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The local skill statistics: how often each skill was sent automatically or pinned, what it sent in tokens, and its
 * size history. Everything stays in {@value #FILE_NAME} under {@value #DIRECTORY_NAME} in the configuration folder;
 * nothing is sent anywhere.
 *
 * <p>Nothing is recorded and nothing is written while the recording gate is closed: the gate is the
 * {@code aiSkillStatisticsEnabled} setting and the enterprise policy. Until the application sets the gate, it is
 * closed. The data is kept in memory and written atomically and owner-only by a daemon task every 30 seconds while
 * it is dirty, and once more when the application exits. A file that cannot be parsed is moved aside to
 * {@code skill-usage.json.broken} and the statistics start empty.</p>
 */
public final class SkillStatsStore {

    public static final String DIRECTORY_NAME = "stats";
    public static final String FILE_NAME = "skill-usage.json";
    public static final Duration FLUSH_INTERVAL = Duration.ofSeconds(30);

    private static final Logger logger = LoggerFactory.getLogger(SkillStatsStore.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static volatile SkillStatsStore instance;

    private final Path file;
    private final Clock clock;
    /** Guards {@link #data}, {@link #dirty}. Never held while file I/O runs. */
    private final Object lock = new Object();
    /** Serializes the writes, and the reset, so an older snapshot cannot replace a newer one on disk. */
    private final Object writeLock = new Object();
    private volatile BooleanSupplier recordingGate = () -> false;
    private SkillStatsData data;
    /** Set when the file exists but could not be read; it is left in place and never overwritten this session. */
    private volatile boolean unreadable;
    private boolean dirty;
    private ScheduledExecutorService flusher;

    /** The application's store, created on first use in the configuration folder. */
    public static SkillStatsStore get() {
        SkillStatsStore current = instance;
        if (current == null) {
            synchronized (SkillStatsStore.class) {
                current = instance;
                if (current == null) {
                    Path file = KorTTYApplication.getConfigDirectory().resolve(DIRECTORY_NAME).resolve(FILE_NAME);
                    current = new SkillStatsStore(file, Clock.systemDefaultZone());
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Replaces the application's store; tests use it with a store in a temporary directory. */
    public static void replaceInstanceForTesting(SkillStatsStore store) {
        instance = store;
    }

    /** A store over {@code file}, read now. A missing file starts empty; an unreadable one is moved aside. */
    public SkillStatsStore(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.data = load(this.file);
    }

    /** The file this store reads and writes. */
    public Path file() {
        return file;
    }

    /** Sets the gate that decides whether statistics are recorded; read on every request. */
    public void setRecordingGate(BooleanSupplier gate) {
        recordingGate = gate != null ? gate : () -> false;
    }

    /** Whether requests are recorded now. A gate that throws is closed. */
    public boolean isRecordingAllowed() {
        try {
            return recordingGate.getAsBoolean();
        } catch (RuntimeException e) {
            logger.warn("The skill statistics gate could not be read; nothing is recorded: {}", e.toString());
            return false;
        }
    }

    /**
     * Adds the skills of one successful request. Called by {@link SkillUsageCollector} only, once per request. Nothing
     * happens while recording is off.
     */
    public void recordCommitted(List<SkillUsageEvent> events) {
        if (events == null || events.isEmpty() || !isRecordingAllowed()) {
            return;
        }
        synchronized (lock) {
            String today = LocalDate.now(clock).toString();
            for (SkillUsageEvent event : events) {
                SkillStatsData.SkillRecord record = data.skills.computeIfAbsent(
                    event.skillId() != null ? event.skillId() : "", id -> new SkillStatsData.SkillRecord());
                record.name = event.skillName();
                record.lastUsed = today;
                SkillStatsData.Bucket bucket = record.daily.computeIfAbsent(today, day -> new SkillStatsData.Bucket());
                if (event.automatic()) {
                    bucket.automatic++;
                } else {
                    bucket.pinned++;
                }
                bucket.tokens += Math.max(0, event.tokens());
                bucket.surfaces.merge(event.surface() != null ? event.surface().name() : "CHAT", 1, Integer::sum);
                // A size without the learned rules is what was sent, not the skill's size. The fingerprint covers the
                // rules, so such a snapshot would stand for the complete skill; the periodic size check takes it.
                if (event.learnedRulesCounted()) {
                    observeLocked(record, event.fingerprint(), event.tokens(), event.tokenizer(), today);
                }
                data.tokenizer = event.tokenizer() != null ? event.tokenizer().name() : null;
            }
            dirty = true;
        }
    }

    /**
     * Takes a size snapshot of every skill whose fingerprint differs from its last snapshot. Called by the periodic
     * task, so a skill changed in the editor is noted within 30 seconds. Nothing happens while recording is off.
     */
    public void observeSkills(Collection<AiSkill> skills) {
        if (skills == null || skills.isEmpty() || !isRecordingAllowed()) {
            return;
        }
        synchronized (lock) {
            String today = LocalDate.now(clock).toString();
            AiTokenizerType tokenizer = currentTokenizer();
            for (AiSkill skill : skills) {
                if (skill == null || skill.getId() == null || skill.getContent() == null) {
                    continue;
                }
                SkillSize size = SkillSize.of(skill, tokenizer);
                SkillStatsData.SkillRecord record = data.skills.computeIfAbsent(
                    skill.getId(), id -> new SkillStatsData.SkillRecord());
                record.name = skill.getName();
                observeLocked(record, size.fingerprint(), size.tokens(), tokenizer, today);
            }
            dirty = true;
        }
    }

    /**
     * Writes the statistics if they changed and recording is still on. Rolls the retention up first. Returns whether a
     * file was written. A failed write keeps the data dirty for the next attempt.
     */
    public boolean flush() {
        synchronized (writeLock) {
            String json;
            synchronized (lock) {
                if (!dirty || !isRecordingAllowed() || unreadable) {
                    return false;
                }
                SkillStatsRollup.apply(data, LocalDate.now(clock));
                json = GSON.toJson(data);
                dirty = false;
            }
            try {
                Path directory = file.getParent();
                Files.createDirectories(directory);
                AtomicFileWriter.restrictToOwner(directory);
                AtomicFileWriter.writeStoreAtomically(file, json, FileMode.OWNER_ONLY);
                return true;
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not write the skill statistics to {}: {}", file, e.toString());
                synchronized (lock) {
                    dirty = true;
                }
                return false;
            }
        }
    }

    /** Deletes every recorded number and size history. The skills themselves are not touched. */
    public void reset() throws IOException {
        synchronized (writeLock) {
            synchronized (lock) {
                data = new SkillStatsData();
                dirty = false;
            }
            Files.deleteIfExists(file);
        }
    }

    /**
     * The report for the dialog. {@code activeSkills} are the skills configured now, which supply the live sizes and
     * the rows without recorded usage.
     */
    public SkillStatsReport report(Collection<AiSkill> activeSkills, SkillStatsReport.Range range) {
        SkillStatsData copy;
        synchronized (lock) {
            copy = GSON.fromJson(GSON.toJson(data), SkillStatsData.class);
        }
        copy.normalize();
        LocalDate today = LocalDate.now(clock);
        return SkillStatsReport.build(copy, activeSkills, range, today, tokenizerOf(copy));
    }

    /** Starts the periodic task: every 30 seconds it notes the changed skills and writes the dirty statistics. */
    public void startFlushing(Supplier<List<AiSkill>> skillSource) {
        synchronized (lock) {
            if (flusher != null) {
                return;
            }
            flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kortty-skill-statistics");
                thread.setDaemon(true);
                return thread;
            });
        }
        flusher.scheduleWithFixedDelay(() -> tick(skillSource), FLUSH_INTERVAL.toSeconds(),
            FLUSH_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Stops the periodic task and writes what is pending. Safe to call more than once. */
    public void close() {
        ScheduledExecutorService running;
        synchronized (lock) {
            running = flusher;
            flusher = null;
        }
        if (running != null) {
            running.shutdownNow();
        }
        try {
            flush();
        } catch (RuntimeException e) {
            logger.warn("Could not write the skill statistics at shutdown: {}", e.toString());
        }
    }

    private void tick(Supplier<List<AiSkill>> skillSource) {
        try {
            if (skillSource != null && isRecordingAllowed()) {
                observeSkills(new ArrayList<>(skillSource.get()));
            }
        } catch (RuntimeException e) {
            // Skills are read while the editor may change them; the next tick reads them again.
            logger.debug("Skipped the skill size check: {}", e.toString());
        }
        try {
            flush();
        } catch (RuntimeException e) {
            logger.warn("Skill statistics flush failed: {}", e.toString());
        }
    }

    /**
     * Takes a size snapshot when the text or the tokenizer changed. The tokenizer is part of the key: a size counted
     * with another tokenizer is not comparable, so the same text counted again gets its own snapshot. One snapshot
     * per text and tokenizer and day is kept, so two profiles that alternate do not add one per request.
     */
    private void observeLocked(SkillStatsData.SkillRecord record, String fingerprint, int tokens,
                               AiTokenizerType tokenizer, String today) {
        if (fingerprint == null) {
            return;
        }
        String counter = tokenizer != null ? tokenizer.name() : AiTokenizerType.ESTIMATE.name();
        String key = fingerprint + '|' + counter;
        if (key.equals(record.lastFingerprint)) {
            return;
        }
        record.lastFingerprint = key;
        boolean taken = record.snapshots.stream().anyMatch(snapshot -> today.equals(snapshot.date)
            && fingerprint.equals(snapshot.fingerprint) && counter.equals(snapshot.tokenizer));
        if (!taken) {
            record.snapshots.add(new SkillStatsData.Snapshot(today, tokens, counter, fingerprint));
        }
    }

    private AiTokenizerType currentTokenizer() {
        return tokenizerOf(data);
    }

    private static AiTokenizerType tokenizerOf(SkillStatsData document) {
        if (document.tokenizer == null) {
            return AiTokenizerType.ESTIMATE;
        }
        try {
            return AiTokenizerType.valueOf(document.tokenizer);
        } catch (IllegalArgumentException e) {
            return AiTokenizerType.ESTIMATE;
        }
    }

    private SkillStatsData load(Path source) {
        if (!Files.exists(source)) {
            return new SkillStatsData();
        }
        String json;
        try {
            json = Files.readString(source, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            moveAside(source, e);
            return new SkillStatsData();
        } catch (IOException | RuntimeException e) {
            // A read that fails for a moment (a locked file, a network drive) says nothing about the content. The file
            // stays where it is and nothing is written over it until a restart reads it again.
            unreadable = true;
            logger.warn("The skill statistics file could not be read ({}); it is kept and not overwritten: {}",
                e.toString(), source);
            return new SkillStatsData();
        }
        try {
            SkillStatsData parsed = GSON.fromJson(json, SkillStatsData.class);
            if (parsed == null || parsed.version != SkillStatsData.VERSION) {
                throw new JsonParseException("unknown statistics version");
            }
            parsed.normalize();
            return parsed;
        } catch (RuntimeException e) {
            moveAside(source, e);
            return new SkillStatsData();
        }
    }

    /** Moves a file that cannot be parsed aside, keeping an earlier backup: a new name when {@code .broken} exists. */
    private static void moveAside(Path source, Exception cause) {
        Path broken = source.resolveSibling(source.getFileName() + ".broken");
        if (Files.exists(broken)) {
            broken = source.resolveSibling(source.getFileName() + ".broken." + System.currentTimeMillis());
        }
        try {
            Files.move(source, broken);
            logger.warn("The skill statistics file could not be read ({}); it was moved to {}", cause.toString(), broken);
        } catch (IOException moveFailure) {
            logger.warn("The skill statistics file could not be read ({}) and could not be moved aside: {}",
                cause.toString(), moveFailure.toString());
        }
    }
}
