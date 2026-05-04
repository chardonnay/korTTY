package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class JobScheduleCalculatorTest {

    private final JobScheduleCalculator calculator = new JobScheduleCalculator();

    @Test
    void findsNextFixedWeekdayTimeInsideActiveWindow() {
        JobSchedule schedule = new JobSchedule();
        schedule.setWeekdaysFromDays(List.of(DayOfWeek.MONDAY));
        schedule.setFixedTimes(List.of("10:30"));
        schedule.setWindowStartTime("09:00");
        schedule.setWindowEndTime("12:00");

        ZonedDateTime after = ZonedDateTime.of(2026, 5, 4, 9, 0, 0, 0, ZoneId.of("Europe/Berlin"));

        assertThat(calculator.nextRunAfter(schedule, after).orElseThrow())
            .isEqualTo(ZonedDateTime.of(2026, 5, 4, 10, 30, 0, 0, ZoneId.of("Europe/Berlin")));
    }

    @Test
    void skipsFixedTimeOutsideWindow() {
        JobSchedule schedule = new JobSchedule();
        schedule.setWeekdaysFromDays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY));
        schedule.setFixedTimes(List.of("18:00"));
        schedule.setWindowStartTime("09:00");
        schedule.setWindowEndTime("12:00");

        ZonedDateTime after = ZonedDateTime.of(2026, 5, 4, 8, 0, 0, 0, ZoneId.of("Europe/Berlin"));

        assertThat(calculator.nextRunAfter(schedule, after)).isEmpty();
    }

    @Test
    void usesIntervalWithinWindow() {
        JobSchedule schedule = new JobSchedule();
        schedule.setIntervalMinutes(15);
        schedule.setWindowStartTime("09:00");
        schedule.setWindowEndTime("10:00");

        ZonedDateTime after = ZonedDateTime.of(2026, 5, 4, 9, 20, 0, 0, ZoneId.of("Europe/Berlin"));

        assertThat(calculator.nextRunAfter(schedule, after).orElseThrow())
            .isEqualTo(ZonedDateTime.of(2026, 5, 4, 9, 30, 0, 0, ZoneId.of("Europe/Berlin")));
    }
}
