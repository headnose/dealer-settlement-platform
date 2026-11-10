package io.github.headnose.dsp.settlement.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Business-day arithmetic. Weekends are skipped; public holidays are NOT
 * (no holiday calendar yet, see README known limitations).
 */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** Returns the first weekday strictly after {@code date}. */
    public static LocalDate nextBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");

        LocalDate candidate = date.plusDays(1);
        while (isWeekend(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}