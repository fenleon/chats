package com.lightphone.chats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Compact timestamp for room rows and messages: time of day for today,
 *  month/day for anything older. */
fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
    return if (dateTime.toLocalDate() == LocalDate.now(zone)) {
        dateTime.toLocalTime().format(TIME_FORMAT)
    } else {
        dateTime.toLocalDate().format(DATE_FORMAT)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
