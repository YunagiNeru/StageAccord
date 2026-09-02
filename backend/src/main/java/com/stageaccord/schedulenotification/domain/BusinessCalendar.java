package com.stageaccord.schedulenotification.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

public record BusinessCalendar(ZoneId zone, Set<DayOfWeek> workdays, Set<LocalDate> holidays,
        LocalTime cutoff) {
    public BusinessCalendar {
        workdays = Set.copyOf(workdays);
        holidays = Set.copyOf(holidays);
        if (workdays.isEmpty()) throw new IllegalArgumentException("workdays must not be empty");
    }

    public Instant addBusinessDays(Instant acceptedAt, int businessDays) {
        if (businessDays < 0) throw new IllegalArgumentException("businessDays must not be negative");
        ZonedDateTime local = acceptedAt.atZone(zone);
        LocalDate date = local.toLocalDate();
        int remaining = businessDays;
        if (!isBusinessDay(date) || local.toLocalTime().isAfter(cutoff)) {
            date = nextBusinessDay(date);
            remaining = Math.max(0, remaining - 1);
        }
        for (; remaining > 0; remaining--) date = nextBusinessDay(date);
        return ZonedDateTime.of(date, cutoff, zone).toInstant();
    }

    private LocalDate nextBusinessDay(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (!isBusinessDay(candidate)) candidate = candidate.plusDays(1);
        return candidate;
    }

    private boolean isBusinessDay(LocalDate date) {
        return workdays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }
}
