package com.gathin.tvhome;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

public class PrayerTest {
    private static void near(String[] expected, LocalTime[] actual) {
        for (int i = 0; i < expected.length; i++) {
            long off = Math.abs(Duration.between(LocalTime.parse(expected[i]), actual[i]).toMinutes());
            assertTrue(expected[i] + " vs " + actual[i], off <= 1);
        }
    }

    /** Aladhan, method 20 (Kementerian Agama RI), for Garut. */
    @Test public void garutMatchesKemenagWithinAMinute() {
        near(new String[]{"04:04", "11:33", "14:44", "17:42", "18:53"},
                Prayer.times(-7.2279, 107.9087, LocalDate.of(2026, 10, 24), 7));
        near(new String[]{"04:10", "11:52", "15:18", "18:08", "19:24"},
                Prayer.times(-7.2279, 107.9087, LocalDate.of(2026, 1, 1), 7));
        near(new String[]{"04:34", "11:49", "15:10", "17:40", "18:55"},
                Prayer.times(-7.2279, 107.9087, LocalDate.of(2026, 6, 15), 7));
    }
}
