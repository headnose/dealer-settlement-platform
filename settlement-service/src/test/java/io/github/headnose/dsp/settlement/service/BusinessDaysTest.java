package io.github.headnose.dsp.settlement.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDaysTest {

    @ParameterizedTest(name = "{2}: {0} -> {1}")
    @CsvSource({
            "2026-05-11, 2026-05-12, Monday -> Tuesday",
            "2026-05-14, 2026-05-15, Thursday -> Friday",
            "2026-05-15, 2026-05-18, Friday -> Monday",
            "2026-05-16, 2026-05-18, Saturday -> Monday",
            "2026-05-17, 2026-05-18, Sunday -> Monday",
            "2026-02-27, 2026-03-02, Friday across month end",
            "2026-12-31, 2027-01-01, Thursday across year end (holiday NOT skipped)"
    })
    void returnsNextWeekday(LocalDate date, LocalDate expected, String description) {
        assertThat(BusinessDays.nextBusinessDay(date)).isEqualTo(expected);
    }
}