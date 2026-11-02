package de.kortty.stats;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The persisted statistics document. Field names are the JSON keys of {@code skill-usage.json}, so they stay stable.
 * Each skill keeps daily buckets, which {@link SkillStatsRollup} folds into monthly and yearly ones, and its size
 * snapshots.
 */
final class SkillStatsData {

    static final int VERSION = 1;

    int version = VERSION;
    /** The tokenizer the most recent sizes were counted with; the live sizes of the report use it too. */
    String tokenizer;
    Map<String, SkillRecord> skills = new TreeMap<>();

    /** Gives every collection a value after reading a file that may lack some of them. */
    void normalize() {
        if (skills == null) {
            skills = new TreeMap<>();
        }
        Iterator<Map.Entry<String, SkillRecord>> iterator = skills.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SkillRecord> entry = iterator.next();
            if (entry.getKey() == null || entry.getValue() == null) {
                iterator.remove();
                continue;
            }
            entry.getValue().normalize();
        }
    }

    /** The usage and size history of one skill, keyed by its id. */
    static final class SkillRecord {
        String name;
        /** ISO date of the last successful request that sent the skill. */
        String lastUsed;
        /** The fingerprint and tokenizer of the last size observation ("fingerprint|tokenizer"); a change takes a new snapshot. */
        String lastFingerprint;
        Map<String, Bucket> daily = new TreeMap<>();
        Map<String, Bucket> monthly = new TreeMap<>();
        Map<String, Bucket> yearly = new TreeMap<>();
        List<Snapshot> snapshots = new ArrayList<>();

        void normalize() {
            if (daily == null) {
                daily = new TreeMap<>();
            }
            if (monthly == null) {
                monthly = new TreeMap<>();
            }
            if (yearly == null) {
                yearly = new TreeMap<>();
            }
            if (snapshots == null) {
                snapshots = new ArrayList<>();
            }
            snapshots.removeIf(snapshot -> snapshot == null || snapshot.date == null);
            daily.values().removeIf(bucket -> bucket == null);
            monthly.values().removeIf(bucket -> bucket == null);
            yearly.values().removeIf(bucket -> bucket == null);
        }
    }

    /** The counts of one period: uses sent automatically or pinned, the tokens they sent, and the uses per surface. */
    static final class Bucket {
        int automatic;
        int pinned;
        long tokens;
        Map<String, Integer> surfaces = new TreeMap<>();

        void add(Bucket other) {
            automatic += other.automatic;
            pinned += other.pinned;
            tokens += other.tokens;
            if (other.surfaces != null) {
                other.surfaces.forEach((surface, count) -> surfaces.merge(surface, count, Integer::sum));
            }
        }

        int uses() {
            return automatic + pinned;
        }
    }

    /** The size of a skill on one day, counted with {@link #tokenizer}. */
    static final class Snapshot {
        String date;
        int tokens;
        String tokenizer;
        String fingerprint;

        Snapshot() {
        }

        Snapshot(String date, int tokens, String tokenizer, String fingerprint) {
            this.date = date;
            this.tokens = tokens;
            this.tokenizer = tokenizer;
            this.fingerprint = fingerprint;
        }
    }
}
