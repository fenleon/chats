package com.lightphone.chats.server

import com.thelightphone.sdk.SealedLightContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy-safe, shareable diagnostics log (test-user feedback 2026-09: send
 * delays, notifications without room-list updates, sync dropouts). One flat
 * text file in `filesDir` with short sanitized status/timing lines — written
 * only while [enabled] (off by default), exported to Movies/Chats via
 * MediaStore so it shows over MTP.
 *
 * PRIVACY CONTRACT (hard): no message bodies, room/user names or ids (ids are
 * truncated to 12 chars via [short]), no URLs with query strings (access
 * tokens live in query params), exceptions truncated to 200 chars. Every line
 * is a status/timing string only.
 */
object Diagnostics {

    private const val PREFS = "chats_account"
    private const val KEY_ENABLED = "diagnostics_logging"
    private const val FILE_NAME = "chats-diagnostics.log"

    /** Append to the log only above this size; above it the file is dropped. */
    private const val MAX_FILE_BYTES = 1_000_000L

    /** Exception messages are truncated to this many chars. */
    private const val MAX_ERROR_CHARS = 200

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var lightContext: SealedLightContext? = null

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val exportNameFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)

    /** Reads the persisted toggle and captures the screen context. Called once
     *  from [MatrixRepository.init] (the bootstrap path). */
    fun init(slc: SealedLightContext) {
        lightContext = slc
        enabled = slc.androidContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    /** Persists the toggle and records the change (the tool calls this
     *  in-process; the context captured at [init] is used). */
    fun setEnabled(value: Boolean) {
        val slc = lightContext ?: return
        slc.androidContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
        record(if (value) "diagnostics logging on" else "diagnostics logging off")
    }

    /** Appends one sanitized line when logging is on. Safe from any thread. */
    fun record(msg: String) {
        if (!enabled) return
        val slc = lightContext ?: return
        synchronized(this) {
            runCatching {
                val file = File(slc.androidContext.filesDir, FILE_NAME)
                if (file.length() > MAX_FILE_BYTES) {
                    // ponytail: 1 MB ceiling is plenty for days of status
                    // lines; on overflow just delete and start fresh — a
                    // rename-to-.old rotation is the upgrade path if the
                    // pre-overflow tail is ever missed.
                    file.delete()
                }
                FileOutputStream(file, true).use { out ->
                    out.write("${timeFormat.format(Date())} $msg\n".toByteArray())
                }
            }
        }
    }

    /** Ids truncated to their first 12 chars (privacy: no full room/event/user ids). */
    fun short(id: String?): String = id?.take(12) ?: "?"

    /** Exception text for the log — message or exception string, truncated to 200 chars. */
    fun err(e: Throwable): String =
        (e.message ?: e.toString().substringBefore(':')).take(MAX_ERROR_CHARS)

    /** True when a non-empty log file exists (the "No log yet" UI case). */
    fun hasLog(): Boolean {
        val file = lightContext?.let { File(it.androidContext.filesDir, FILE_NAME) } ?: return false
        return file.exists() && file.length() > 0
    }

    /**
     * Copies the log into the media store so it is reachable over MTP — the
     * LP3's MTP surface only exposes Pictures/Movies (measured, root
     * AGENTS.md), hence the Video collection for a .log file.
     *
     * @return the display name of the exported file, or null when there is no
     *  log yet or the media-store write failed (reason recorded).
     */
    fun export(): String? {
        val slc = lightContext ?: return null
        val file = File(slc.androidContext.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            record("export skipped — no log")
            return null
        }
        return runCatching {
            // The Video collection with a video/* MIME: MediaProvider rejects
            // any other MIME there ("expected MIME type under video/*", LP3
            // 2026-09-21 — the export always failed with "Save failed"), and
            // the Files collection only allows Download/Documents, which the
            // LP3's MTP surface does not expose (root AGENTS.md: MTP shows
            // only Pictures/Movies). The extension stays .log; MediaProvider
            // may append ".mp4" when the display name's extension doesn't
            // match the video/* MIME — the SDK's save returns the ACTUAL
            // stored name (it is what the Settings row shows and what MTP
            // serves).
            slc.saveToMediaStore(
                collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                displayName = "chats-diagnostics-${exportNameFormat.format(Date())}.log",
                mimeType = "video/mp4",
                relativePath = "Movies/Chats",
                bytes = file.readBytes(),
            ) ?: error("media store save returned null")
        }.onFailure {
            android.util.Log.w("Diagnostics", "diagnostics export failed: ${it.message}")
            record("export failed: ${err(it)}")
        }.getOrNull()
    }
}
