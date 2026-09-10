package com.vstokke.memos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderTimeTest {
    @Test
    fun `quick choice at exact time moves to next calendar day`() {
        val zone = ZoneId.of("Europe/Oslo")
        val now = Instant.parse("2026-06-12T05:00:00Z")

        val result = ReminderTime.nextLocalHour(7, now, zone).atZone(zone)

        assertEquals(LocalDate.of(2026, 6, 13), result.toLocalDate())
        assertEquals(LocalTime.of(7, 0), result.toLocalTime())
    }

    @Test
    fun `quick choice keeps local hour across daylight saving transition`() {
        val zone = ZoneId.of("Europe/Oslo")
        val now = Instant.parse("2026-03-28T20:00:00Z")

        val result = ReminderTime.nextLocalHour(7, now, zone).atZone(zone)

        assertEquals(LocalDate.of(2026, 3, 29), result.toLocalDate())
        assertEquals(7, result.hour)
    }

    @Test
    fun `nonexistent local time is rejected`() {
        val zone = ZoneId.of("Europe/Oslo")
        val result = ReminderTime.exactLocal(
            LocalDate.of(2026, 3, 29),
            LocalTime.of(2, 30),
            Instant.parse("2026-03-28T00:00:00Z"),
            zone,
        )
        assertNull(result)
    }

    @Test
    fun `offset timestamp is normalized to instant`() {
        assertEquals(
            Instant.parse("2026-10-01T20:00:00.123Z"),
            ReminderTime.parseServer("2026-10-01T22:00:00.123+02:00"),
        )
    }

    @Test
    fun `instance URL requires clean HTTPS address and preserves prefix`() {
        assertEquals("https://memos.example.com/prefix", InstanceUrl.normalize(" HTTPS://Memos.Example.com/prefix/ "))
        assertNull(InstanceUrl.normalize("http://memos.example.com"))
        assertNull(InstanceUrl.normalize("https://token@memos.example.com"))
        assertNull(InstanceUrl.normalize("https://memos.example.com/?token=secret"))
        assertTrue(InstanceUrl.normalize("https://memos.example.com:8443")!!.endsWith(":8443"))
    }
}
