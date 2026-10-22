package com.lightphone.chats.server

/**
 * Dev-line diagnostics, stubbed on `main`: the user-facing diagnostics tool
 * was removed from the Light release (dev-only feature), so the log can never
 * be enabled here and [record] is a no-op. The object and its call sites stay
 * so dev→main ports don't conflict — the real implementation lives on `dev`.
 */
object Diagnostics {

    /** No-op on main; appends one sanitized line on the dev line. */
    fun record(msg: String) {
    }

    /** Ids truncated to their first 12 chars (privacy: no full room/event/user ids). */
    fun short(id: String?): String = id?.take(12) ?: "?"

    /** Exception text for the log — message or exception string, truncated to 200 chars. */
    fun err(e: Throwable): String =
        (e.message ?: e.toString().substringBefore(':')).take(200)
}
