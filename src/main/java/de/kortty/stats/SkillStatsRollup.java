package de.kortty.stats;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the retention rules: daily buckets older than 400 days fold into monthly ones, monthly buckets older than
 * five years fold into yearly ones, and size snapshots older than 90 days keep one per ISO week. Yearly buckets are
 * kept forever. The roll-up is idempotent, so it runs on every write.
 */
final class SkillStatsRollup {

    static final int DAILY_RETENTION_DAYS = 400;
    static final int MONTHLY_RETENTION_MONTHS = 60;
    static final int FULL_SNAPSHOT_DAYS = 90;

    private SkillStatsRollup() {
    }

    static void apply(SkillStatsData data, LocalDate today) {
        LocalDate dailyCutoff = today.minusDays(DAILY_RETENTION_DAYS);
        YearMonth monthlyCutoff = YearMonth.from(today).minusMonths(MONTHLY_RETENTION_MONTHS);
        LocalDate snapshotCutoff = today.minusDays(FULL_SNAPSHOT_DAYS);
        for (SkillStatsData.SkillRecord record : data.skills.values()) {
            foldDaily(record, dailyCutoff);
            foldMonthly(record, monthlyCutoff);
            thinSnapshots(record, snapshotCutoff);
        }
    }

    /** Days on or before {@code cutoff} become monthly buckets. */
    private static void foldDaily(SkillStatsData.SkillRecord record, LocalDate cutoff) {
        Iterator<Map.Entry<String, SkillStatsData.Bucket>> iterator = record.daily.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SkillStatsData.Bucket> entry = iterator.next();
            LocalDate date = parseDate(entry.getKey());
            if (date == null) {
                iterator.remove();
            } else if (!date.isAfter(cutoff)) {
                String month = YearMonth.from(date).toString();
                record.monthly.computeIfAbsent(month, key -> new SkillStatsData.Bucket()).add(entry.getValue());
                iterator.remove();
            }
        }
    }

    /** Months on or before {@code cutoff} become yearly buckets. */
    private static void foldMonthly(SkillStatsData.SkillRecord record, YearMonth cutoff) {
        Iterator<Map.Entry<String, SkillStatsData.Bucket>> iterator = record.monthly.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SkillStatsData.Bucket> entry = iterator.next();
            YearMonth month = parseMonth(entry.getKey());
            if (month == null) {
                iterator.remove();
            } else if (!month.isAfter(cutoff)) {
                String year = String.valueOf(month.getYear());
                record.yearly.computeIfAbsent(year, key -> new SkillStatsData.Bucket()).add(entry.getValue());
                iterator.remove();
            }
        }
    }

    /** Snapshots older than the cutoff keep only the last one of each ISO week. */
    private static void thinSnapshots(SkillStatsData.SkillRecord record, LocalDate cutoff) {
        List<SkillStatsData.Snapshot> sorted = new ArrayList<>(record.snapshots);
        sorted.sort(Comparator.comparing(snapshot -> snapshot.date));
        Map<String, SkillStatsData.Snapshot> lastOfWeek = new LinkedHashMap<>();
        List<SkillStatsData.Snapshot> recent = new ArrayList<>();
        for (SkillStatsData.Snapshot snapshot : sorted) {
            LocalDate date = parseDate(snapshot.date);
            if (date == null) {
                continue;
            }
            if (date.isAfter(cutoff)) {
                recent.add(snapshot);
            } else {
                lastOfWeek.put(isoWeek(date), snapshot);
            }
        }
        List<SkillStatsData.Snapshot> kept = new ArrayList<>(lastOfWeek.values());
        kept.addAll(recent);
        kept.sort(Comparator.comparing(snapshot -> snapshot.date));
        record.snapshots = kept;
    }

    static String isoWeek(LocalDate date) {
        return date.get(IsoFields.WEEK_BASED_YEAR) + "-W" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static YearMonth parseMonth(String value) {
        if (value == null) {
            return null;
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
