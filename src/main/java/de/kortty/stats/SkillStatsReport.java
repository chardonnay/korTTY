package de.kortty.stats;

import de.kortty.model.AiSkill;
import de.kortty.model.AiTokenizerType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the statistics dialog shows, computed from a copy of the data: the periods of the range, the stacked series
 * (the top skills by automatic uses and the rest), the headline figures, one row per skill, the size history of each
 * row and the hints. Pure: it reads the data and the current skills, and writes nothing.
 *
 * @param periods  the period keys in order: {@code yyyy-MM-dd}, {@code yyyy-MM} or {@code yyyy}
 * @param hasUsage whether anything was ever recorded, whatever the range
 */
public record SkillStatsReport(
    Range range,
    List<String> periods,
    List<Series> series,
    Kpis kpis,
    List<Row> rows,
    List<Hint> hints,
    boolean hasUsage) {

    /** How many skills the chart shows by name; the rest are summed into one "others" series. */
    public static final int TOP_SERIES = 8;
    /** A skill that was not used for this many days is hinted as unused. */
    public static final int UNUSED_DAYS = 90;
    /** A skill that more than doubled within this many days is hinted as growing. */
    public static final int GROWTH_WINDOW_DAYS = 30;
    /** A skill above this many tokens is hinted as large. */
    public static final int LARGE_SKILL_TOKENS = 1000;

    private static final int DEFAULT_DAYS = 30;
    private static final int DEFAULT_MONTHS = 12;
    private static final double DAYS_PER_MONTH = 30.44d;
    /**
     * The shortest span a per-month growth is extrapolated from. One day of edits must not be read as thirty days of
     * growth; the percentage needs no such span and is kept.
     */
    static final int MIN_GROWTH_SPAN_DAYS = 7;

    /** The unit of a period. */
    public enum Granularity {
        DAY,
        MONTH,
        YEAR
    }

    /**
     * The periods shown: the last {@code periods} days or months, or the last {@code periods} years; with
     * {@link #ALL} the years from the first recorded one to this year.
     */
    public record Range(Granularity granularity, int periods) {

        public static final int ALL = 0;

        public Range {
            granularity = granularity != null ? granularity : Granularity.DAY;
            periods = Math.max(0, periods);
        }
    }

    /** One stacked series. A null {@code skillId} is the "others" series; its name is left to the caller. */
    public record Series(String skillId, String name, List<Long> automatic, List<Long> pinned) {

        public Series {
            automatic = List.copyOf(automatic);
            pinned = List.copyOf(pinned);
        }
    }

    /**
     * The headline figures. {@code averageSizeTokens} and {@code growthTokensPerMonth} cover the enabled skills; both
     * are null when there is nothing to average or no snapshot to grow from.
     */
    public record Kpis(long automaticUses, long sentTokens, Double averageSizeTokens, Double growthTokensPerMonth) {
    }

    /** A size snapshot on its date. */
    public record Point(LocalDate date, int tokens) {
    }

    /**
     * One skill. {@code current} is false for a skill that was recorded but is no longer configured. Sizes are null
     * when the skill is not configured or has no usage to average.
     */
    public record Row(
        String skillId,
        String name,
        boolean current,
        boolean enabled,
        long automatic,
        long pinned,
        long sentTokens,
        Double averageTokens,
        Integer currentTokens,
        Double growthTokensPerMonth,
        Double growthPercent,
        LocalDate lastUsed,
        int learnedRules,
        List<Point> history) {

        public Row {
            history = List.copyOf(history);
        }
    }

    public enum HintKind {
        UNUSED_90_DAYS,
        GREW_TWICE_IN_30_DAYS,
        OVER_1000_TOKENS
    }

    /** A suggestion about one skill; the dialog only shows it, nothing is changed. */
    public record Hint(String skillId, String name, HintKind kind, int tokens) {
    }

    /**
     * Builds the report. {@code activeSkills} are the effective skills configured now; {@code tokenizer} counts their
     * live sizes.
     */
    static SkillStatsReport build(
        SkillStatsData data,
        Collection<AiSkill> activeSkills,
        Range requested,
        LocalDate today,
        AiTokenizerType tokenizer) {

        Range range = requested != null ? requested : new Range(Granularity.DAY, DEFAULT_DAYS);
        List<String> periods = periodKeys(range, data, today);
        Set<String> periodSet = new HashSet<>(periods);
        LocalDate windowStart = periodStart(periods.get(0), range.granularity());
        LocalDate unusedCutoff = today.minusDays(UNUSED_DAYS);
        LocalDate growthBaselineCutoff = today.minusDays(GROWTH_WINDOW_DAYS);

        Map<String, AiSkill> current = new LinkedHashMap<>();
        for (AiSkill skill : activeSkills != null ? activeSkills : List.<AiSkill>of()) {
            if (skill != null && skill.getId() != null) {
                current.putIfAbsent(skill.getId(), skill);
            }
        }
        Set<String> ids = new TreeSet<>(data.skills.keySet());
        ids.addAll(current.keySet());

        Map<String, Map<String, SkillStatsData.Bucket>> perSkill = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        List<Hint> hints = new ArrayList<>();
        long automaticUses = 0;
        long sentTokens = 0;
        double sizeSum = 0;
        int sizeCount = 0;
        double growthSum = 0;
        int growthCount = 0;
        for (String id : ids) {
            SkillStatsData.SkillRecord record = data.skills.get(id);
            Map<String, SkillStatsData.Bucket> buckets = periodBuckets(record, range.granularity(), periodSet);
            perSkill.put(id, buckets);
            SkillStatsData.Bucket total = new SkillStatsData.Bucket();
            buckets.values().forEach(total::add);

            AiSkill skill = current.get(id);
            Integer currentTokens = skill != null ? SkillSize.of(skill, tokenizer).tokens() : null;
            List<Point> history = record != null ? points(record.snapshots, tokenizer) : List.of();
            LocalDate lastUsed = record != null ? SkillStatsRollup.parseDate(record.lastUsed) : null;
            Growth growth = currentTokens != null ? growth(history, currentTokens, windowStart, today) : Growth.NONE;
            int uses = total.uses();
            Double averageTokens = uses > 0 ? (double) total.tokens / uses : null;
            boolean enabled = skill != null && skill.isEnabled();
            Row row = new Row(id, skill != null ? skill.getName() : (record != null ? record.name : null),
                skill != null, enabled, total.automatic, total.pinned, total.tokens, averageTokens,
                currentTokens, growth.perMonth(), growth.percent(), lastUsed,
                skill != null ? learnedRuleCount(skill) : 0, history);
            rows.add(row);

            automaticUses += total.automatic;
            sentTokens += total.tokens;
            if (enabled && currentTokens != null) {
                sizeSum += currentTokens;
                sizeCount++;
                if (growth.perMonth() != null) {
                    growthSum += growth.perMonth();
                    growthCount++;
                }
            }
            if (skill != null) {
                hints.addAll(hintsFor(row, history, lastUsed, currentTokens, unusedCutoff, growthBaselineCutoff));
            }
        }
        Kpis kpis = new Kpis(automaticUses, sentTokens,
            sizeCount > 0 ? sizeSum / sizeCount : null,
            growthCount > 0 ? growthSum : null);
        Map<String, String> names = new HashMap<>();
        for (Row row : rows) {
            names.put(row.skillId(), row.name() != null ? row.name() : row.skillId());
        }
        return new SkillStatsReport(range, periods, series(ids, perSkill, names, periods), kpis, rows, hints,
            hasAnyUsage(data));
    }

    /** The period keys of {@code range}, oldest first. */
    static List<String> periodKeys(Range range, SkillStatsData data, LocalDate today) {
        List<String> keys = new ArrayList<>();
        switch (range.granularity()) {
            case DAY -> {
                int days = range.periods() > 0 ? range.periods() : DEFAULT_DAYS;
                for (int back = days - 1; back >= 0; back--) {
                    keys.add(today.minusDays(back).toString());
                }
            }
            case MONTH -> {
                int months = range.periods() > 0 ? range.periods() : DEFAULT_MONTHS;
                YearMonth month = YearMonth.from(today);
                for (int back = months - 1; back >= 0; back--) {
                    keys.add(month.minusMonths(back).toString());
                }
            }
            case YEAR -> {
                int first = range.periods() > 0
                    ? today.getYear() - range.periods() + 1
                    : earliestYear(data, today.getYear());
                for (int year = Math.min(first, today.getYear()); year <= today.getYear(); year++) {
                    keys.add(String.valueOf(year));
                }
            }
            default -> throw new IllegalStateException("Unknown granularity: " + range.granularity());
        }
        return keys;
    }

    private static int earliestYear(SkillStatsData data, int thisYear) {
        int earliest = thisYear;
        for (SkillStatsData.SkillRecord record : data.skills.values()) {
            for (Map<String, SkillStatsData.Bucket> level : List.of(record.daily, record.monthly, record.yearly)) {
                for (String key : level.keySet()) {
                    if (key != null && key.length() >= 4) {
                        try {
                            earliest = Math.min(earliest, Integer.parseInt(key.substring(0, 4)));
                        } catch (NumberFormatException ignored) {
                            // A key that is not a date is skipped, as the roll-up does.
                        }
                    }
                }
            }
        }
        return earliest;
    }

    private static LocalDate periodStart(String key, Granularity granularity) {
        return switch (granularity) {
            case DAY -> LocalDate.parse(key);
            case MONTH -> YearMonth.parse(key).atDay(1);
            case YEAR -> LocalDate.of(Integer.parseInt(key), 1, 1);
        };
    }

    /** The buckets of one skill folded into the requested periods; coarser buckets cannot be split into finer ones. */
    private static Map<String, SkillStatsData.Bucket> periodBuckets(
        SkillStatsData.SkillRecord record, Granularity granularity, Set<String> periods) {

        Map<String, SkillStatsData.Bucket> result = new HashMap<>();
        if (record == null) {
            return result;
        }
        addPeriods(result, record.daily, 0, granularity, periods);
        addPeriods(result, record.monthly, 1, granularity, periods);
        addPeriods(result, record.yearly, 2, granularity, periods);
        return result;
    }

    private static void addPeriods(
        Map<String, SkillStatsData.Bucket> result,
        Map<String, SkillStatsData.Bucket> buckets,
        int level,
        Granularity granularity,
        Set<String> periods) {

        if (buckets == null || level > granularity.ordinal()) {
            return;
        }
        for (Map.Entry<String, SkillStatsData.Bucket> entry : buckets.entrySet()) {
            String period = periodOf(entry.getKey(), granularity);
            if (period != null && periods.contains(period)) {
                result.computeIfAbsent(period, key -> new SkillStatsData.Bucket()).add(entry.getValue());
            }
        }
    }

    private static String periodOf(String key, Granularity granularity) {
        if (key == null) {
            return null;
        }
        return switch (granularity) {
            case DAY -> key;
            case MONTH -> key.length() >= 7 ? key.substring(0, 7) : null;
            case YEAR -> key.length() >= 4 ? key.substring(0, 4) : null;
        };
    }

    private static List<Series> series(
        Set<String> ids,
        Map<String, Map<String, SkillStatsData.Bucket>> perSkill,
        Map<String, String> names,
        List<String> periods) {

        List<String> ranked = new ArrayList<>();
        for (String id : ids) {
            if (total(perSkill.get(id)).automatic > 0 || total(perSkill.get(id)).pinned > 0) {
                ranked.add(id);
            }
        }
        ranked.sort(Comparator.comparingLong((String id) -> total(perSkill.get(id)).automatic).reversed()
            .thenComparing(Comparator.comparingLong((String id) -> total(perSkill.get(id)).pinned).reversed())
            .thenComparing(id -> id));
        List<Series> series = new ArrayList<>();
        List<String> top = ranked.subList(0, Math.min(TOP_SERIES, ranked.size()));
        for (String id : top) {
            series.add(seriesOf(id, names.getOrDefault(id, id), perSkill.get(id), periods));
        }
        if (ranked.size() > TOP_SERIES) {
            Map<String, SkillStatsData.Bucket> others = new HashMap<>();
            for (String id : ranked.subList(TOP_SERIES, ranked.size())) {
                perSkill.get(id).forEach((period, bucket) ->
                    others.computeIfAbsent(period, key -> new SkillStatsData.Bucket()).add(bucket));
            }
            series.add(seriesOf(null, null, others, periods));
        }
        return series;
    }

    private static Series seriesOf(
        String skillId, String name, Map<String, SkillStatsData.Bucket> buckets, List<String> periods) {

        List<Long> automatic = new ArrayList<>();
        List<Long> pinned = new ArrayList<>();
        for (String period : periods) {
            SkillStatsData.Bucket bucket = buckets.get(period);
            automatic.add(bucket != null ? bucket.automatic : 0L);
            pinned.add(bucket != null ? (long) bucket.pinned : 0L);
        }
        return new Series(skillId, name, automatic, pinned);
    }

    private static SkillStatsData.Bucket total(Map<String, SkillStatsData.Bucket> buckets) {
        SkillStatsData.Bucket total = new SkillStatsData.Bucket();
        if (buckets != null) {
            buckets.values().forEach(total::add);
        }
        return total;
    }

    private static boolean hasAnyUsage(SkillStatsData data) {
        for (SkillStatsData.SkillRecord record : data.skills.values()) {
            for (Map<String, SkillStatsData.Bucket> level : List.of(record.daily, record.monthly, record.yearly)) {
                for (SkillStatsData.Bucket bucket : level.values()) {
                    if (bucket.uses() > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The size history in the report's tokenizer, one point per day: the last snapshot of a day. Snapshots counted
     * with another tokenizer are left out, since their sizes are not comparable with the live size.
     */
    private static List<Point> points(List<SkillStatsData.Snapshot> snapshots, AiTokenizerType tokenizer) {
        Map<LocalDate, Point> byDay = new TreeMap<>();
        for (SkillStatsData.Snapshot snapshot : snapshots) {
            LocalDate date = SkillStatsRollup.parseDate(snapshot.date);
            String counter = snapshot.tokenizer != null ? snapshot.tokenizer : AiTokenizerType.ESTIMATE.name();
            if (date != null && counter.equals(tokenizer.name())) {
                byDay.put(date, new Point(date, snapshot.tokens));
            }
        }
        return new ArrayList<>(byDay.values());
    }

    private record Growth(Double perMonth, Double percent) {
        static final Growth NONE = new Growth(null, null);
    }

    /**
     * The change from the first snapshot inside the window, or from the last one before it, to the current size, spread
     * over the months since that snapshot. Null when there is no snapshot to start from.
     */
    private static Growth growth(List<Point> points, int current, LocalDate windowStart, LocalDate today) {
        Point baseline = null;
        for (Point point : points) {
            if (!point.date().isBefore(windowStart)) {
                baseline = point;
                break;
            }
        }
        if (baseline == null) {
            for (Point point : points) {
                if (point.date().isBefore(windowStart)) {
                    baseline = point;
                }
            }
        }
        if (baseline == null) {
            return Growth.NONE;
        }
        long days = ChronoUnit.DAYS.between(baseline.date(), today);
        double delta = current - baseline.tokens();
        Double percent = baseline.tokens() > 0 ? delta * 100.0 / baseline.tokens() : null;
        Double perMonth = days >= MIN_GROWTH_SPAN_DAYS ? delta / (days / DAYS_PER_MONTH) : null;
        return new Growth(perMonth, percent);
    }

    private static List<Hint> hintsFor(
        Row row, List<Point> history, LocalDate lastUsed, Integer currentTokens,
        LocalDate unusedCutoff, LocalDate growthBaselineCutoff) {

        List<Hint> hints = new ArrayList<>();
        LocalDate knownSince = history.isEmpty() ? null : history.get(0).date();
        LocalDate unusedSince = lastUsed != null ? lastUsed : knownSince;
        if (unusedSince != null && !unusedSince.isAfter(unusedCutoff)) {
            hints.add(new Hint(row.skillId(), row.name(), HintKind.UNUSED_90_DAYS,
                currentTokens != null ? currentTokens : 0));
        }
        if (currentTokens != null) {
            Point before = null;
            for (Point point : history) {
                if (!point.date().isAfter(growthBaselineCutoff)) {
                    before = point;
                }
            }
            if (before != null && before.tokens() > 0 && currentTokens > 2L * before.tokens()) {
                hints.add(new Hint(row.skillId(), row.name(), HintKind.GREW_TWICE_IN_30_DAYS, currentTokens));
            }
            if (currentTokens > LARGE_SKILL_TOKENS) {
                hints.add(new Hint(row.skillId(), row.name(), HintKind.OVER_1000_TOKENS, currentTokens));
            }
        }
        return hints;
    }

    private static int learnedRuleCount(AiSkill skill) {
        int count = 0;
        for (var rule : skill.getLearnedRules()) {
            if (rule != null && rule.getText() != null && !rule.getText().isBlank()) {
                count++;
            }
        }
        return count;
    }
}
