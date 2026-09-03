package com.lightphone.chats

import android.telephony.PhoneNumberUtils
import com.thelightphone.sdk.shared.LightServiceMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Relative timestamp for room rows: 24-hour time of day for today ("14:02" —
 * feedback 2026-08-21: the AM/PM label was too big for the rows), "Yest" for
 * the previous day (feedback 2026-09-03: the period dropped — the label reads
 * like the weekday names, no punctuation), the short weekday name within
 * the last week (Mon, Tue,
 * Wed, Thu, Fri, Sat, Sun), "Aug 12" (month abbreviation retained) for
 * anything older — with the month + year ("Aug 2025", no day) when the message
 * predates the current year (feedback 2026-08-30: the day was dropped from
 * previous-year rows). (Feedback 2026-08-17: the row time went back to the
 * short hand.)
 */
fun formatRelativeTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> dateTime.toLocalTime().format(ROW_TIME_FORMAT)
        date == today.minusDays(1) -> "Yest"
        date.isAfter(today.minusDays(7)) -> SHORT_DAY_NAMES[date.dayOfWeek.value - 1]
        date.year == today.year -> date.format(MONTH_DAY_FORMAT)
        else -> date.format(MONTH_YEAR_FORMAT)
    }
}

/** Full capitalized weekday names (ISO: Monday=1 … Sunday=7), for thread day tags. */
private val DAY_NAMES = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** Short capitalized weekday names (ISO: Monday=1 … Sunday=7), for room rows. */
private val SHORT_DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Timestamp for a message row in the thread: the time plus a day tag when the
 * message isn't from today — "3:24 PM", "Yesterday 3:24 PM", "Monday 3:24 PM",
 * "Aug 12 3:24 PM" (feedback 2026-08-21: the day tags replace the centered
 * day dividers; "Today" is never tagged). A previous-year message appends the
 * year to the date tag — "Dec 10, 2025 3:24 PM" (feedback 2026-08-21: the
 * year disambiguates an old message; same-year messages stay "Aug 12 3:24 PM").
 * 12-hour with AM/PM — the thread keeps the long hand (feedback 2026-08-21).
 */
fun formatMessageTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    val time = dateTime.toLocalTime().format(MESSAGE_TIME_FORMAT)
    val tag = when {
        date == today -> null
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> DAY_NAMES[date.dayOfWeek.value - 1]
        date.year == today.year -> date.format(MONTH_DAY_FORMAT)
        else -> date.format(MONTH_DAY_YEAR_FORMAT)
    }
    return if (tag == null) time else "$tag $time"
}

/** The local date of a timestamp, for the thread's per-day grouping. */
fun dayOf(timestampMs: Long): LocalDate =
    Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Formats a bridge phone number the way Beeper's WhatsApp contact view does:
 * "4915129093984" → "+49 151 29093984" — a plus, the country code, then the
 * national number with a space after the mobile prefix (feedback 2026-08-22).
 * The country code is inferred by what leaves a plausible national number:
 * 1 (US/Canada, 1 + 10), 2 (Germany/France/…, 2 + 9..11), else 3. Non-phone
 * strings pass through unchanged.
 */
fun formatBridgePhone(raw: String): String {
    val digits = raw.trim().removePrefix("+").filter { it.isDigit() }
    if (digits.length < 10) return raw
    val ccLen = when {
        digits.startsWith("1") && digits.length - 1 in 9..11 -> 1
        digits.length - 2 in 9..11 -> 2
        digits.length - 3 in 8..10 -> 3
        else -> 2
    }
    val cc = digits.take(ccLen)
    val national = digits.drop(ccLen)
    val body = if (national.length > 8) {
        "${national.take(3)} ${national.drop(3)}"
    } else {
        national
    }
    return "+$cc $body"
}

/**
 * The room's other participant for 1:1s — the single non-bot hero (Beeper
 * bridged DMs list the contact; bridge bots like @whatsappbot are excluded).
 * Null for groups. Drives the contact overlay's phone/username line (chats,
 * feedback 2026-08-21; shared by the thread and the room list's long-press
 * panel 2026-08-29). The companion resolves the real identifier via the
 * bridge's contact API (WhatsApp numbers incl. LID, Instagram usernames) and
 * rides it in [LightServiceMethod.GetRooms.Room.contactPhone] — that value
 * wins here. The heuristic below is the fallback when the bridge doesn't
 * serve one. NOTE: the m.bridge channel's `fi.mau.receiver` is the USER'S
 * OWN number, not the contact's (verified 2026-08-22 across many LID DMs) —
 * the contact's number is only present for `whatsapp_<number>` heroes; LID
 * heroes (`whatsapp_lid-…`, the WhatsApp privacy migration) carry no number
 * in the room data at all (Beeper resolves LIDs server-side).
 *
 * Non-phone ids render with a leading '@' — "@karin3na", never a bare
 * username (feedback 2026-09-01); phone ids stay as-is.
 */
fun contactIdentifier(contactId: String?, displayName: String, resolved: String? = null): String? {
    val raw = when {
        !resolved.isNullOrBlank() ->
            if (PhoneNumberUtils.isGlobalPhoneNumber(resolved)) formatBridgePhone(resolved) else resolved
        else -> {
            val localpart = contactId?.substringAfter("@")?.substringBefore(":")
            if (localpart != null) {
                val rest = localpart.removePrefix("whatsapp_")
                if (rest != localpart) { // a WhatsApp bridged ID
                    if (rest.startsWith("lid-")) {
                        return displayName.takeIf { PhoneNumberUtils.isGlobalPhoneNumber(it) }
                    }
                    formatBridgePhone(rest)
                } else {
                    localpart
                }
            } else {
                displayName.takeIf { PhoneNumberUtils.isGlobalPhoneNumber(it) }
            }
        }
    }
    return raw?.takeIf { it.isNotBlank() }?.let { if (it.isPhoneLike()) it else "@$it" }
}

/** Whether a display id is a phone number: an optional leading + and 7-15
 *  digits, ignoring the spaces [formatBridgePhone] adds. Everything else
 *  (usernames, ghost localparts) is a user id and renders with '@'. */
private fun String.isPhoneLike(): Boolean {
    val digits = filter { it.isDigit() }
    return digits.length in 7..15 && (startsWith("+") || all { it.isDigit() })
}

/**
 * Whether a room row matches a search query: its name, its resolved contact
 * identifier (number/username — the companion fills
 * [LightServiceMethod.GetRooms.Room.contactPhone] from the bridge), or a
 * digit-only partial match of that identifier (a phone typed with
 * spaces/plus). Shared by the Search + Contacts screens (2026-09-01).
 */
fun roomMatchesQuery(room: LightServiceMethod.GetRooms.Room, q: String): Boolean {
    if (room.name.contains(q, ignoreCase = true)) return true
    val id = room.contactPhone
    if (id?.contains(q, ignoreCase = true) == true) return true
    val qDigits = q.filter { it.isDigit() }
    return qDigits.length >= 3 && id?.filter { it.isDigit() }?.contains(qDigits) == true
}

/** 24-hour time for the room list rows — "14:02" (feedback 2026-08-21: the
 *  AM/PM label was "too big" in the rows). */
private val ROW_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 12-hour time with AM/PM — "3:24 PM" — for the THREAD message times
 *  (feedback 2026-08-20: was 24-hour; the user kept 12h in threads when the
 *  rows went back to 24h, 2026-08-21). */
private val MESSAGE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/** "Aug 12" / "Dec 01" — month abbreviation + zero-padded day. */
private val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd")

/** "Aug 2025" — month abbreviation + year for previous-year room rows
 *  (feedback 2026-08-30: the day dropped, the year stays). */
private val MONTH_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

/** "Dec 10, 2025" — month abbreviation + day + year, for previous-year
 *  timestamps in the THREAD's message times (feedback 2026-08-21). */
private val MONTH_DAY_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
