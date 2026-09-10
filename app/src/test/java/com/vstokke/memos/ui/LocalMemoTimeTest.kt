package com.vstokke.memos.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class LocalMemoTimeTest {
    @Test fun usesLocalSummerAndWinterTimeWithoutZoneLabels() {
        val oslo = ZoneId.of("Europe/Oslo")
        val nb = Locale.forLanguageTag("nb-NO")
        val summer = formatLocalMemoTime(Instant.parse("2026-07-01T12:00:00Z"), oslo, nb)
        val winter = formatLocalMemoTime(Instant.parse("2026-01-01T12:00:00Z"), oslo, nb)
        assertTrue(summer.contains("14:00"))
        assertTrue(winter.contains("13:00"))
        for (text in listOf(summer, winter)) {
            for (zone in listOf("UTC", "GMT", "CEST", "CET", "+02:00", "+01:00")) assertFalse(text.contains(zone))
        }
    }
}
