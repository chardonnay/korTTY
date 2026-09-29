package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.RecordStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * All stored Full-code analyses of one snippet: the root of {@code snippet-analyses/<id>.json}.
 *
 * <p>{@code records} are newest first, and the newest record is always the current one; which
 * record a panel shows is UI state and never changes the stored data. {@code revision} grows by
 * one with every stored change (a backup restore lets the newer revision win).
 *
 * <p>{@code blobs} maps a SHA-256 key to text and is only populated in the file form, where the
 * content fields of the records hold those keys (see {@link SnippetAnalysisStore}). In memory the
 * records carry their text and {@code blobs} is empty.
 *
 * <p>{@code lastWriteError} and {@code loadIssue} are transient: the store fills them in for
 * subscribers and never writes them.
 */
public record SnippetAnalysisHistory(
    int schemaVersion,
    String snippetId,
    List<SnippetAnalysisRecord> records,
    long revision,
    long updatedAt,
    Map<String, String> blobs,
    String lastWriteError,
    LoadIssue loadIssue) {

    public static final int SCHEMA_VERSION = 1;
    /** Transient components, excluded from the JSON by the store. */
    static final Set<String> TRANSIENT_FIELDS = Set.of("lastWriteError", "loadIssue");

    /** Why a stored file could not be used as-is. */
    public record LoadIssue(Kind kind, String detail) {
        public enum Kind {
            /** The file could not be parsed (or was too large) and was moved aside to {@code detail}. */
            QUARANTINED,
            /** The file was written by a newer korTTY; it is shown but never written. */
            NEWER_SCHEMA_READ_ONLY,
            /** The file could neither be read nor moved aside; nothing is written over it. */
            UNREADABLE_READ_ONLY
        }

        public LoadIssue {
            Objects.requireNonNull(kind, "kind");
            detail = detail != null ? detail : "";
        }

        public boolean readOnly() {
            return kind != Kind.QUARANTINED;
        }
    }

    public SnippetAnalysisHistory {
        snippetId = snippetId != null ? snippetId : "";
        records = records == null ? List.of() : records.stream().filter(Objects::nonNull).toList();
        blobs = blobs == null || blobs.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(blobs));
        lastWriteError = lastWriteError == null || lastWriteError.isBlank() ? null : lastWriteError;
    }

    public static SnippetAnalysisHistory empty(String snippetId) {
        return new SnippetAnalysisHistory(SCHEMA_VERSION, snippetId, List.of(), 0L, 0L, Map.of(), null, null);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    /** The newest record, or {@code null}. */
    public SnippetAnalysisRecord current() {
        return records.isEmpty() ? null : records.getFirst();
    }

    public SnippetAnalysisRecord find(String recordId) {
        for (SnippetAnalysisRecord record : records) {
            if (record.id().equals(recordId)) {
                return record;
            }
        }
        return null;
    }

    public boolean isReadOnly() {
        return loadIssue != null && loadIssue.readOnly();
    }

    // ---- Derived status ----

    public RecordStatus statusOf(SnippetAnalysisRecord record) {
        return statusOf(record, null);
    }

    /**
     * Derived, never stored.
     * <ul>
     *   <li>{@code SUPERSEDED}: the record is not the newest.</li>
     *   <li>{@code OPEN}: no run was accepted.</li>
     *   <li>{@code ACCEPTED_NOT_SAVED}: runs were accepted, but none reached the saved snippet.</li>
     *   <li>{@code APPLIED}: the saved accepted runs cover every improvement and dependency id.</li>
     *   <li>{@code PARTIALLY_APPLIED}: otherwise.</li>
     * </ul>
     * An accepted run counts as saved when an editor save stamped {@code savedToSnippetAt}, or when
     * {@code savedSnippetSha256} (the hash of the snippet as stored) equals its
     * {@code acceptedContentSha256}.
     */
    public RecordStatus statusOf(SnippetAnalysisRecord record, String savedSnippetSha256) {
        if (record == null) {
            return RecordStatus.OPEN;
        }
        SnippetAnalysisRecord current = current();
        if (current == null || !current.id().equals(record.id())) {
            return RecordStatus.SUPERSEDED;
        }
        List<ApplyRun> accepted = record.applyRuns().stream().filter(ApplyRun::isAccepted).toList();
        if (accepted.isEmpty()) {
            return RecordStatus.OPEN;
        }
        Set<String> applied = new HashSet<>();
        boolean anySaved = false;
        for (ApplyRun run : accepted) {
            boolean saved = run.savedToSnippetAt() > 0
                || (savedSnippetSha256 != null && !savedSnippetSha256.isBlank()
                    && savedSnippetSha256.equals(run.acceptedContentSha256()));
            if (saved) {
                anySaved = true;
                applied.addAll(run.appliedFindingIds());
            }
        }
        if (!anySaved) {
            return RecordStatus.ACCEPTED_NOT_SAVED;
        }
        return applied.containsAll(record.allFindingIds()) ? RecordStatus.APPLIED : RecordStatus.PARTIALLY_APPLIED;
    }

    // ---- Changes (all return a new history; the store bumps the revision) ----

    /**
     * Prepends {@code record} as the new current record and applies retention: while more than
     * {@code limit} records exist, the oldest record that is neither the new one nor
     * {@linkplain SnippetAnalysisRecord#isProtectedFromRetention() protected} (nor, for a
     * {@link SnippetAnalysisRecord.Purpose#VERIFY} record, the analysis it verifies) is dropped. Retention
     * runs only here, when a new analysis arrives, never on any other change.
     */
    public SnippetAnalysisHistory withNewCurrent(SnippetAnalysisRecord record, int limit) {
        Objects.requireNonNull(record, "record");
        List<SnippetAnalysisRecord> next = new ArrayList<>(records.size() + 1);
        next.add(record.withSnippetId(snippetId));
        for (SnippetAnalysisRecord existing : records) {
            if (!existing.id().equals(record.id())) {
                next.add(existing);
            }
        }
        int max = Math.max(1, limit);
        // A verification is only meaningful next to the analysis it verified: keep that one too.
        String verified = record.purpose() == SnippetAnalysisRecord.Purpose.VERIFY ? record.previousRecordId() : null;
        for (int i = next.size() - 1; i >= 1 && next.size() > max; i--) {
            SnippetAnalysisRecord candidate = next.get(i);
            if (!candidate.isProtectedFromRetention() && !candidate.id().equals(verified)) {
                next.remove(i);
            }
        }
        return withRecords(next);
    }

    /**
     * How many records a new analysis would trim at {@code limit}, so the UI can warn when the user
     * lowers the setting.
     */
    public int trimmableAt(int limit) {
        int max = Math.max(1, limit);
        int size = records.size() + 1;
        int trimmed = 0;
        for (int i = records.size() - 1; i >= 0 && size > max; i--) {
            if (!records.get(i).isProtectedFromRetention()) {
                size--;
                trimmed++;
            }
        }
        return trimmed;
    }

    /**
     * Replaces the record with the same id. A record that is gone (discarded meanwhile) is not
     * brought back.
     */
    public SnippetAnalysisHistory replace(SnippetAnalysisRecord record) {
        Objects.requireNonNull(record, "record");
        List<SnippetAnalysisRecord> next = new ArrayList<>(records);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(record.id())) {
                next.set(i, record.withSnippetId(snippetId));
                return withRecords(next);
            }
        }
        return this;
    }

    /** Applies {@code change} to the record with {@code recordId}, if present. */
    public SnippetAnalysisHistory update(String recordId, java.util.function.UnaryOperator<SnippetAnalysisRecord> change) {
        SnippetAnalysisRecord record = find(recordId);
        return record == null ? this : replace(change.apply(record));
    }

    /**
     * Stamps every accepted run whose accepted text is exactly the saved snippet
     * ({@code savedSnippetSha256}) as saved at {@code at}: the intermediate state is then gone and
     * retention may trim the record again. Returns {@code this} when nothing needed a stamp.
     */
    public SnippetAnalysisHistory withAcceptedRunsSaved(String savedSnippetSha256, long at) {
        if (savedSnippetSha256 == null || savedSnippetSha256.isBlank()) {
            return this;
        }
        SnippetAnalysisHistory next = this;
        for (SnippetAnalysisRecord record : records) {
            if (needsSavedStamp(record, savedSnippetSha256)) {
                next = next.update(record.id(), r -> r.withApplyRuns(r.applyRuns().stream()
                    .map(run -> run.isAccepted() && run.savedToSnippetAt() <= 0
                        && savedSnippetSha256.equals(run.acceptedContentSha256())
                        ? run.withSavedToSnippetAt(at) : run)
                    .toList()));
            }
        }
        return next;
    }

    /** Whether some accepted run of {@code record} is exactly the saved snippet but not yet stamped. */
    public static boolean needsSavedStamp(SnippetAnalysisRecord record, String savedSnippetSha256) {
        return savedSnippetSha256 != null && !savedSnippetSha256.isBlank()
            && record.applyRuns().stream().anyMatch(run -> run.isAccepted() && run.savedToSnippetAt() <= 0
                && savedSnippetSha256.equals(run.acceptedContentSha256()));
    }

    /** Discard: removes one record. */
    public SnippetAnalysisHistory remove(String recordId) {
        List<SnippetAnalysisRecord> next = records.stream().filter(r -> !r.id().equals(recordId)).toList();
        return next.size() == records.size() ? this : withRecords(next);
    }

    public SnippetAnalysisHistory withRecords(List<SnippetAnalysisRecord> value) {
        return new SnippetAnalysisHistory(schemaVersion, snippetId, value, revision, updatedAt, blobs,
            lastWriteError, loadIssue);
    }

    /**
     * Makes a freshly loaded file safe to use: RUNNING runs become INTERRUPTED (the app quit or the
     * editor closed mid-run), duplicate record ids keep their first (newest) occurrence and every
     * record carries this history's snippet id.
     */
    public SnippetAnalysisHistory normalizeAfterLoad() {
        Set<String> seen = new HashSet<>();
        List<SnippetAnalysisRecord> next = new ArrayList<>(records.size());
        for (SnippetAnalysisRecord record : records) {
            if (record.id().isEmpty() || !seen.add(record.id())) {
                continue;
            }
            next.add(record.interruptRunning().withSnippetId(snippetId));
        }
        return withRecords(next);
    }

    // ---- Package-private store helpers ----

    SnippetAnalysisHistory withSnippetId(String value) {
        return new SnippetAnalysisHistory(schemaVersion, value,
            records.stream().map(r -> r.withSnippetId(value)).toList(), revision, updatedAt, blobs,
            lastWriteError, loadIssue);
    }

    SnippetAnalysisHistory withRevision(long value, long at) {
        return new SnippetAnalysisHistory(schemaVersion, snippetId, records, value, at, blobs, lastWriteError,
            loadIssue);
    }

    SnippetAnalysisHistory withTransientState(String writeError, LoadIssue issue) {
        return new SnippetAnalysisHistory(schemaVersion, snippetId, records, revision, updatedAt, blobs,
            writeError, issue);
    }

    SnippetAnalysisHistory withSchemaVersion(int value) {
        return new SnippetAnalysisHistory(value, snippetId, records, revision, updatedAt, blobs, lastWriteError,
            loadIssue);
    }

    /** Applies the per-record size caps. */
    SnippetAnalysisHistory compact() {
        return withRecords(records.stream().map(SnippetAnalysisRecord::compact).toList());
    }

    /** File form: content fields replaced by blob keys, the texts stored once in {@code blobs}. */
    SnippetAnalysisHistory externalizeContent() {
        Map<String, String> store = new LinkedHashMap<>();
        List<SnippetAnalysisRecord> mapped = records.stream()
            .map(record -> record.mapContent(text -> {
                if (text == null) {
                    return null;
                }
                String key = SnippetDiagramSupport.contentHash(text);
                store.putIfAbsent(key, text);
                return key;
            }))
            .toList();
        return new SnippetAnalysisHistory(schemaVersion, snippetId, mapped, revision, updatedAt, store, null, null);
    }

    /** Memory form: blob keys resolved back to text (a missing blob becomes {@code null}). */
    SnippetAnalysisHistory internalizeContent() {
        Map<String, String> store = blobs;
        List<SnippetAnalysisRecord> mapped = records.stream()
            .map(record -> record.mapContent(key -> key == null ? null : store.get(key)))
            .toList();
        return new SnippetAnalysisHistory(schemaVersion, snippetId, mapped, revision, updatedAt, Map.of(),
            lastWriteError, loadIssue);
    }
}
