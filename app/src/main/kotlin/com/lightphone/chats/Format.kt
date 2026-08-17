package com.lightphone.chats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Relative timestamp for room rows: time of day for today, "Yest." for the
 * previous day, the short weekday name within the last week (Mon, Tue, Wed,
 * Thu, Fri, Sat, Sun), "Aug 12" (month abbreviation retained) for anything
 * older. (Feedback 2026-08-17: the row time went back to the short hand.)
 */
fun formatRelativeTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> dateTime.toLocalTime().format(TIME_FORMAT)
        date == today.minusDays(1) -> "Yest."
        date.isAfter(today.minusDays(7)) -> SHORT_DAY_NAMES[date.dayOfWeek.value - 1]
        else -> date.format(MONTH_DAY_FORMAT)
    }
}

/** Full capitalized weekday names (ISO: Monday=1 … Sunday=7), for thread day dividers. */
private val DAY_NAMES = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** Short capitalized weekday names (ISO: Monday=1 … Sunday=7), for room rows. */
private val SHORT_DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Time of day only, for message rows in the thread (Phase 9). */
fun formatMessageTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    return Instant.ofEpochMilli(timestampMs)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(TIME_FORMAT)
}

/** The local date of a timestamp, for the thread's per-day dividers. */
fun dayOf(timestampMs: Long): LocalDate =
    Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Divider label for a thread day, matching the chat list's delineation:
 * "Today", "Yesterday", the full weekday within the last week, or "Mon XX"
 * ("Aug 12", "Dec 01") for anything older.
 */
fun dayDividerLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> DAY_NAMES[date.dayOfWeek.value - 1]
        else -> date.format(MONTH_DAY_FORMAT)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
/** "Aug 12" / "Dec 01" — month abbreviation + zero-padded day. */
private val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd")
