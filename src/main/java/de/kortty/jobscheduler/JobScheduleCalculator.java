package de.kortty.jobscheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JobScheduleCalculator {

    private static final int SEARCH_DAYS = 370;

    public Optional<ZonedDateTime> nextRunAfter(JobSchedule schedule, ZonedDateTime after) {
        if (schedule == null || !schedule.isEnabled() || after == null) {
            return Optional.empty();
        }

        LocalDate fromDate = parseDate(schedule.getActiveFromDate()).orElse(null);
        LocalDate untilDate = parseDate(schedule.getActiveUntilDate()).orElse(null);
        Set<DayOfWeek> allowedDays = parseWeekdays(schedule.getWeekdays());
        List<LocalTime> fixedTimes = parseTimes(schedule.getFixedTimes());
        LocalTime windowStart = parseTime(schedule.getWindowStartTime()).orElse(LocalTime.MIN);
        LocalTime windowEnd = parseTime(schedule.getWindowEndTime()).orElse(LocalTime.of(23, 59));
        Integer intervalMinutes = schedule.getIntervalMinutes();

        List<ZonedDateTime> candidates = new ArrayList<>();
        LocalDate startDay = after.toLocalDate();
        for (int dayOffset = 0; dayOffset < SEARCH_DAYS; dayOffset++) {
            LocalDate day = startDay.plusDays(dayOffset);
            if (!isAllowedDate(day, fromDate, untilDate, allowedDays)) {
                continue;
            }
            collectFixedTimeCandidates(candidates, after, day, fixedTimes, windowStart, windowEnd);
            collectIntervalCandidates(candidates, after, day, intervalMinutes, windowStart, windowEnd);
            if (fixedTimes.isEmpty() && intervalMinutes == null) {
                ZonedDateTime candidate = LocalDateTime.of(day, windowStart).atZone(after.getZone());
                if (candidate.isAfter(after)) {
                    candidates.add(candidate);
                }
            }
            Optional<ZonedDateTime> first = candidates.stream()
                .filter(candidate -> candidate.isAfter(after))
                .min(Comparator.naturalOrder());
            if (first.isPresent()) {
                return first;
            }
        }
        return Optional.empty();
    }

    private void collectFixedTimeCandidates(
        List<ZonedDateTime> candidates,
        ZonedDateTime after,
        LocalDate day,
        List<LocalTime> fixedTimes,
        LocalTime windowStart,
        LocalTime windowEnd) {

        for (LocalTime time : fixedTimes) {
            if (!isInsideWindow(time, windowStart, windowEnd)) {
                continue;
            }
            ZonedDateTime candidate = LocalDateTime.of(day, time).atZone(after.getZone());
            if (candidate.isAfter(after)) {
                candidates.add(candidate);
            }
        }
    }

    private void collectIntervalCandidates(
        List<ZonedDateTime> candidates,
        ZonedDateTime after,
        LocalDate day,
        Integer intervalMinutes,
        LocalTime windowStart,
        LocalTime windowEnd) {

        if (intervalMinutes == null || intervalMinutes <= 0) {
            return;
        }
        LocalDateTime cursor = LocalDateTime.of(day, windowStart);
        LocalDateTime end = LocalDateTime.of(day, windowEnd);
        if (windowEnd.isBefore(windowStart)) {
            end = end.plusDays(1);
        }
        while (!cursor.isAfter(end)) {
            ZonedDateTime candidate = cursor.atZone(after.getZone());
            if (candidate.isAfter(after)) {
                candidates.add(candidate);
            }
            cursor = cursor.plusMinutes(intervalMinutes);
        }
    }

    private boolean isAllowedDate(LocalDate day, LocalDate fromDate, LocalDate untilDate, Set<DayOfWeek> allowedDays) {
        if (fromDate != null && day.isBefore(fromDate)) {
            return false;
        }
        if (untilDate != null && day.isAfter(untilDate)) {
            return false;
        }
        return allowedDays.isEmpty() || allowedDays.contains(day.getDayOfWeek());
    }

    private boolean isInsideWindow(LocalTime time, LocalTime windowStart, LocalTime windowEnd) {
        if (windowEnd.isBefore(windowStart)) {
            return !time.isBefore(windowStart) || !time.isAfter(windowEnd);
        }
        return !time.isBefore(windowStart) && !time.isAfter(windowEnd);
    }

    private Set<DayOfWeek> parseWeekdays(List<String> weekdays) {
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        if (weekdays == null) {
            return result;
        }
        for (String value : weekdays) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                result.add(DayOfWeek.valueOf(value.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Invalid persisted values are ignored so one bad value does not disable the job.
            }
        }
        return result;
    }

    private List<LocalTime> parseTimes(List<String> times) {
        if (times == null) {
            return List.of();
        }
        List<LocalTime> result = new ArrayList<>();
        for (String value : times) {
            parseTime(value).ifPresent(result::add);
        }
        return result.stream().distinct().sorted().toList();
    }

    private Optional<LocalDate> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private Optional<LocalTime> parseTime(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(value.trim()).withSecond(0).withNano(0));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
