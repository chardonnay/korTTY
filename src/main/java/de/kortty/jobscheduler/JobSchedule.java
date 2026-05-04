package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "schedule")
@XmlAccessorType(XmlAccessType.FIELD)
public class JobSchedule {

    @XmlElement
    private boolean enabled = true;

    @XmlElementWrapper(name = "weekdays")
    @XmlElement(name = "weekday")
    private List<String> weekdays = new ArrayList<>();

    @XmlElementWrapper(name = "fixedTimes")
    @XmlElement(name = "time")
    private List<String> fixedTimes = new ArrayList<>();

    @XmlElement
    private String activeFromDate;

    @XmlElement
    private String activeUntilDate;

    @XmlElement
    private String windowStartTime;

    @XmlElement
    private String windowEndTime;

    @XmlElement
    private Integer intervalMinutes;

    public static JobSchedule dailyInterval(int minutes) {
        JobSchedule schedule = new JobSchedule();
        schedule.setIntervalMinutes(minutes);
        schedule.setWindowStartTime("00:00");
        schedule.setWindowEndTime("23:59");
        return schedule;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getWeekdays() {
        if (weekdays == null) {
            weekdays = new ArrayList<>();
        }
        return weekdays;
    }

    public void setWeekdays(List<String> weekdays) {
        this.weekdays = weekdays != null ? new ArrayList<>(weekdays) : new ArrayList<>();
    }

    public List<String> getFixedTimes() {
        if (fixedTimes == null) {
            fixedTimes = new ArrayList<>();
        }
        return fixedTimes;
    }

    public void setFixedTimes(List<String> fixedTimes) {
        this.fixedTimes = fixedTimes != null ? new ArrayList<>(fixedTimes) : new ArrayList<>();
    }

    public String getActiveFromDate() {
        return activeFromDate;
    }

    public void setActiveFromDate(String activeFromDate) {
        this.activeFromDate = trimToNull(activeFromDate);
    }

    public String getActiveUntilDate() {
        return activeUntilDate;
    }

    public void setActiveUntilDate(String activeUntilDate) {
        this.activeUntilDate = trimToNull(activeUntilDate);
    }

    public String getWindowStartTime() {
        return windowStartTime;
    }

    public void setWindowStartTime(String windowStartTime) {
        this.windowStartTime = trimToNull(windowStartTime);
    }

    public String getWindowEndTime() {
        return windowEndTime;
    }

    public void setWindowEndTime(String windowEndTime) {
        this.windowEndTime = trimToNull(windowEndTime);
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes != null && intervalMinutes > 0 ? intervalMinutes : null;
    }

    public void setWeekdaysFromDays(List<DayOfWeek> days) {
        if (days == null) {
            setWeekdays(List.of());
            return;
        }
        setWeekdays(days.stream().map(DayOfWeek::name).toList());
    }

    public void setFixedTimesFromLocalTimes(List<LocalTime> times) {
        if (times == null) {
            setFixedTimes(List.of());
            return;
        }
        setFixedTimes(times.stream().map(time -> time.withSecond(0).withNano(0).toString()).toList());
    }

    private String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }
}
