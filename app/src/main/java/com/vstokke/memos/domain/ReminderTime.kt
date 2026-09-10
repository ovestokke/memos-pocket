package com.vstokke.memos.domain

import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object InstanceUrl {
    fun normalize(input: String): String? {
        val value = input.trim().trimEnd('/')
        if (value.isEmpty() || value.any(Char::isWhitespace)) return null
        return try {
            val uri = URI(value)
            if (
                !uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null
            ) {
                null
            } else {
                URI(
                    "https",
                    null,
                    uri.host.lowercase(),
                    uri.port,
                    uri.path.ifBlank { null },
                    null,
                    null,
                ).toASCIIString().trimEnd('/')
            }
        } catch (_: Exception) {
            null
        }
    }
}

object ReminderTime {
    val quickHours = listOf(7, 12, 16, 20)

    fun nextLocalHour(hour: Int, now: Instant, zone: ZoneId): Instant {
        require(hour in 0..23)
        val localNow = now.atZone(zone)
        var date = localNow.toLocalDate()
        var candidate = strictLocal(date, LocalTime.of(hour, 0), zone)
        if (candidate == null || !candidate.toInstant().isAfter(now)) {
            date = date.plusDays(1)
            candidate = strictLocal(date, LocalTime.of(hour, 0), zone)
                ?: ZonedDateTime.of(date, LocalTime.of(hour, 0), zone)
        }
        return candidate.toInstant()
    }

    fun exactLocal(date: LocalDate, time: LocalTime, now: Instant, zone: ZoneId): Instant? {
        val candidate = strictLocal(date, time.withSecond(0).withNano(0), zone) ?: return null
        return candidate.toInstant().takeIf { it.isAfter(now) }
    }

    fun parseServer(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }

    fun toServer(value: Instant): String = DateTimeFormatter.ISO_INSTANT.format(value)

    private fun strictLocal(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime? {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return null
        return ZonedDateTime.ofStrict(local, offsets.first(), zone)
    }
}
