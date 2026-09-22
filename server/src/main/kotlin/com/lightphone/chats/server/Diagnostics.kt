package com.lightphone.chats.server

import android.content.Context
import android.provider.MediaStore
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
    private var appContext: Context? = null

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val exportNameFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)

    /** Reads the persisted toggle and captures the app context. Called once from [MatrixRepository.init]. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    /** Persists the toggle and records the change. [context] may be null —
     *  the appContext captured at [init] is used then (the tool calls this
     *  in-process, NO-SEAM, without a Context). */
    fun setEnabled(context: Context? = null, value: Boolean) {
        val app = context?.applicationContext ?: appContext ?: return
        appContext = app
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
        record(if (value) "diagnostics logging on" else "diagnostics logging off")
    }

    /** Appends one sanitized line when logging is on. Safe from any thread. */
    fun record(msg: String) {
        if (!enabled) return
        val ctx = appContext ?: return
        synchronized(this) {
            runCatching {
                val file = File(ctx.filesDir, FILE_NAME)
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

    /** Exception text for the log — message or class name, truncated to 200 chars. */
    fun err(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).take(MAX_ERROR_CHARS)

    /** True when a non-empty log file exists (the "No log yet" UI case). */
    fun hasLog(): Boolean {
        val file = appContext?.let { File(it.filesDir, FILE_NAME) } ?: return false
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
    fun export(context: Context? = null): String? {
        val ctx = context?.applicationContext ?: appContext ?: return null
        val file = File(ctx.filesDir, FILE_NAME)
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
            // only Pictures/Movies). The extension stays .log; the post-commit
            // scan corrects the row's MIME from the extension.
            val name = "chats-diagnostics-${exportNameFormat.format(Date())}.log"
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Chats")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("media store insert returned null")
            resolver.openOutputStream(uri)?.use { out ->
                out.write(file.readBytes())
            } ?: error("no output stream for $uri")
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            // MediaProvider appends ".mp4" when the display name's extension
            // doesn't match the video/* MIME — report the ACTUAL stored name
            // (it is what the Settings row shows and what MTP serves).
            resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
            )?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null } ?: name
        }.onFailure {
            android.util.Log.w("Diagnostics", "diagnostics export failed: ${it.message}")
            record("export failed: ${err(it)}")
        }.getOrNull()
    }
}

