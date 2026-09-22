package com.lightphone.chats.server

import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin SQL wrapper around Trixnity's SQLite for the ingest-materialized
 * thread store (docs/THREAD-STORE-SPEC.md §1) — the same raw side-table
 * pattern as `RoomProjection` (ensureProjectionTable / recomputeProjectionRows
 * in MatrixRepository.kt). Room ignores these tables; logout wipes them with
 * the store's `deleteDatabase`.
 *
 * Pure row rules live in [ThreadRowLogic] (Task 1, kotlin-test covered); this
 * file only maps rows onto the `ThreadRow` table. Queries are exercised
 * on-emulator (SPEC §11) — no JVM SQLite rig exists.
 */
object ThreadRowStore {

    private const val TAG = "ThreadRowStore"

    /** SPEC §1 DDL, verbatim (plus the Part H cursor bookmark table below). */
    private const val DDL_THREADROW =
        "CREATE TABLE IF NOT EXISTS ThreadRow (" +
            "roomId        TEXT NOT NULL," +
            "eventId       TEXT NOT NULL," +
            "kind          TEXT NOT NULL," +
            "sender        TEXT," +
            "timestampMs   INTEGER," +
            "ingestSeq     INTEGER NOT NULL," +
            "body          TEXT," +
            "formattedHtml TEXT," +
            "contentType   TEXT," +
            "replyToId     TEXT," +
            "mediaMeta     TEXT," +
            "sendStatus    TEXT," +
            "encrypted     INTEGER NOT NULL DEFAULT 0," +
            "prevEventId   TEXT," +
            "batchBefore   TEXT," +
            "targetEventId TEXT," +
            "payload       TEXT," +
            "reactionSummary TEXT," +
            "PRIMARY KEY (roomId, eventId))"
    private const val DDL_IDX_PAGE =
        "CREATE INDEX IF NOT EXISTS idx_threadrow_page " +
            "ON ThreadRow (roomId, kind, timestampMs, ingestSeq)"
    private const val DDL_IDX_TARGET =
        "CREATE INDEX IF NOT EXISTS idx_threadrow_target " +
            "ON ThreadRow (roomId, targetEventId)"

    /** Partial index for the recheck's placeholder scan (pendingRows) — the
     *  full-table `encrypted=1` scan ran every 30 s otherwise. */
    private const val DDL_IDX_PENDING =
        "CREATE INDEX IF NOT EXISTS idx_threadrow_pending " +
            "ON ThreadRow(roomId, ingestSeq) WHERE encrypted=1"

    /** Part H backfill bookmark: one resume token + fetched counter per
     *  room. Lives in the same SQLite (not PREFS) so it dies with the store
     *  at logout — a re-login backfills from scratch, per SPEC §8. (The
     *  table is new on this branch — no migration.) */
    private const val DDL_CURSOR =
        "CREATE TABLE IF NOT EXISTS ThreadRowCursor (" +
            "roomId TEXT NOT NULL PRIMARY KEY," +
            "batchBefore TEXT," +
            "fetchedCount INTEGER NOT NULL DEFAULT 0)"

    /** Create the store schema. Cheap (IF NOT EXISTS) — call it before every
     *  use, like ensureProjectionTable's callers do. */
    fun ensureTable(db: SupportSQLiteDatabase) {
        db.execSQL(DDL_THREADROW)
        db.execSQL(DDL_IDX_PAGE)
        db.execSQL(DDL_IDX_TARGET)
        db.execSQL(DDL_IDX_PENDING)
        db.execSQL(DDL_CURSOR)
        ensureCursorCountColumn(db)
    }

    /** In-branch upgrade fallback: installs from before the fetchedCount
     *  commit created `ThreadRowCursor` without the column. The ALTER fails
     *  harmlessly (duplicate column) on a table that already has it; the
     *  once-per-process flag keeps the failed attempt off the hot ingest
     *  path. */
    @Volatile
    private var cursorCountColumnChecked = false

    private fun ensureCursorCountColumn(db: SupportSQLiteDatabase) {
        if (cursorCountColumnChecked) return
        runCatching {
            db.execSQL("ALTER TABLE ThreadRowCursor ADD COLUMN fetchedCount INTEGER NOT NULL DEFAULT 0")
        }
        cursorCountColumnChecked = true
    }

    /**
     * One ingest write (SPEC §1/§2): all rows in one transaction on
     * Dispatchers.IO, `INSERT OR REPLACE`, with the per-room `ingestSeq`
     * rebased inside the transaction (`SELECT COALESCE(MAX(ingestSeq),0)+1`)
     * so concurrent writers can't collide. Input order is preserved — the
     * rebased seqs stay monotonic over the list. Rows come from one room
     * ([ThreadRowLogic.buildRows] contract).
     *
     * Re-delivery idempotency (Task 3 review ruling): an eventId already in
     * the table is SKIPPED — a re-synced round must not churn the page
     * order — with one exception: a decrypt placeholder (encrypted=1) that
     * receives its decrypted body updates IN PLACE, keeping the original
     * `ingestSeq`. The fold below then re-applies the side rows onto the
     * updated target, recomputing its cached columns (reactionSummary
     * included).
     *
     * Side rows (reaction / edit / redaction / send-status) are stored as
     * rows — the only truth — and folded into their target message row here:
     * targets present in this batch, targets of this batch's side rows, and
     * (transitively, one level) the message behind a redacted reaction. All
     * side rows of a target re-apply in ingest order on every write, so the
     * cached columns converge (and pending side rows from earlier rounds —
     * whose target only now arrived — apply at the same time, SPEC §1).
     *
     * Returns the number of rows actually inserted — skips and in-place
     * placeholder updates excluded, side rows included — the caller's
     * "did this batch add anything" signal (any kind counted).
     */
    suspend fun writeRows(c: MatrixClient, rows: List<ThreadRowValues>): Int {
        if (rows.isEmpty()) return 0
        val db = database(c) ?: return 0
        return withContext(Dispatchers.IO) {
            runCatching {
                var inserted = 0
                val sq = db.openHelper.writableDatabase
                sq.beginTransaction()
                try {
                    ensureTable(sq)
                    for (row in rows) {
                        val existing = queryFirst(
                            sq,
                            "SELECT * FROM ThreadRow WHERE roomId=? AND eventId=?",
                            arrayOf(row.roomId, row.eventId),
                        )
                        if (existing != null) {
                            if (existing.encrypted == 1 && row.encrypted == 0 &&
                                existing.kind == RowKind.MESSAGE.wire &&
                                row.kind == RowKind.MESSAGE.wire &&
                                row.body != null
                            ) {
                                sq.execSQL(
                                    "UPDATE ThreadRow SET sender=?,timestampMs=?,body=?," +
                                        "formattedHtml=?,contentType=?,replyToId=?,mediaMeta=?," +
                                        "sendStatus=?,encrypted=0,prevEventId=?,batchBefore=? " +
                                        "WHERE roomId=? AND eventId=?",
                                    arrayOf<Any?>(
                                        row.sender,
                                        row.timestampMs,
                                        row.body,
                                        row.formattedHtml,
                                        row.contentType,
                                        row.replyToId,
                                        row.mediaMeta,
                                        row.sendStatus,
                                        row.prevEventId,
                                        row.batchBefore,
                                        row.roomId,
                                        row.eventId,
                                    ),
                                )
                            } else if (existing.prevEventId == null && row.prevEventId != null) {
                                // Link self-heal — LEGACY-STORE COMPAT (the
                                // seed routes through the canonical builder
                                // since the 2026-09-19 unification, so new
                                // rows always carry links): rows written by
                                // pre-unification seeds carry null
                                // prevEventId; when a chain walk re-covers
                                // them, patch in place — hasMore at the
                                // store's bottom depends on it. Converges:
                                // once patched the branch never fires again.
                                sq.execSQL(
                                    "UPDATE ThreadRow SET prevEventId=?,batchBefore=? " +
                                        "WHERE roomId=? AND eventId=?",
                                    arrayOf<Any?>(
                                        row.prevEventId,
                                        row.batchBefore ?: existing.batchBefore,
                                        row.roomId,
                                        row.eventId,
                                    ),
                                )
                            }
                            continue
                        }
                        val seq = sq.query(
                            "SELECT COALESCE(MAX(ingestSeq),0)+1 FROM ThreadRow WHERE roomId=?",
                            arrayOf(row.roomId),
                        ).use { cur ->
                            check(cur.moveToFirst())
                            cur.getInt(0)
                        }
                        insert(sq, row.copy(ingestSeq = seq))
                        inserted++
                    }
                    foldTargets(sq, rows)
                    sq.setTransactionSuccessful()
                } finally {
                    sq.endTransaction()
                }
                inserted
            }.onFailure { Log.w(TAG, "write failed: ${it.message}") }.getOrDefault(0)
        }
    }

    /**
     * Placeholder rows (encrypted=1) for the ingest writer's 30 s decrypt
     * recheck (SPEC §2/§4), oldest room+ingest first, bounded per pass.
     * [offset] rotates the scan start — a fixed head let permanently-stuck
     * rooms occupy the recheck window forever and starve every room after
     * them ([pendingCount] wraps the rotation).
     */
    suspend fun pendingRows(c: MatrixClient, limit: Int, offset: Int = 0): List<ThreadRowValues> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                queryRows(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE encrypted=1 " +
                        "ORDER BY roomId, ingestSeq LIMIT ? OFFSET ?",
                    arrayOf<Any?>(limit, offset),
                )
            }.getOrDefault(emptyList<ThreadRowValues>())
        }
    }

    /** Total placeholder rows — the wrap bound for the recheck's rotating
     *  scan start ([pendingRows]). */
    suspend fun pendingCount(c: MatrixClient): Int {
        val db = database(c) ?: return 0
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM ThreadRow WHERE encrypted=1",
                    arrayOf<String>(),
                ).use { cur ->
                    check(cur.moveToFirst())
                    cur.getInt(0)
                }
            }.getOrDefault(0)
        }
    }

    /**
     * Remove one row — the decrypt recheck drops a placeholder that decrypted
     * to something the page would not render (the same rule the read path
     * applies to such events).
     */
    suspend fun deleteRow(c: MatrixClient, roomId: String, eventId: String) {
        val db = database(c) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM ThreadRow WHERE roomId=? AND eventId=?",
                    arrayOf(roomId, eventId),
                )
            }
        }
    }

    /**
     * Pseudo seeded reaction retirement (Task 5 review ruling): delete every
     * `seed:`-prefixed reaction row ([ThreadRowLogic.SEED_ROW_PREFIX]) that
     * targets one of [targetEventIds] and recompute each affected target's
     * cached `reactionSummary` from the reaction rows still standing — the
     * fold's exact query (unredacted). One transaction; returns the number of
     * rows deleted (0 = nothing to retire). The caller (the ingest writer's
     * reaction-write hook) runs this before [writeRows], whose fold then
     * re-applies the batch's real side rows onto the recomputed base.
     */
    suspend fun retireSeedReactions(
        c: MatrixClient,
        roomId: String,
        targetEventIds: Collection<String>,
    ): Int {
        if (targetEventIds.isEmpty()) return 0
        val db = database(c) ?: return 0
        return withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                sq.beginTransaction()
                try {
                    ensureTable(sq)
                    var removed = 0
                    for (target in targetEventIds) {
                        val seeds = queryRows(
                            sq,
                            "SELECT * FROM ThreadRow WHERE roomId=? AND kind='reaction' " +
                                "AND targetEventId=?",
                            arrayOf(roomId, target),
                        ).filter { it.eventId.startsWith(ThreadRowLogic.SEED_ROW_PREFIX) }
                        if (seeds.isEmpty()) continue
                        for (seed in seeds) {
                            sq.execSQL(
                                "DELETE FROM ThreadRow WHERE roomId=? AND eventId=?",
                                arrayOf(roomId, seed.eventId),
                            )
                            removed++
                        }
                        val remaining = queryRows(
                            sq,
                            // Reactions still standing after the retirement —
                            // the same redaction-guarded query the fold's
                            // summary recompute applies.
                            "SELECT * FROM ThreadRow WHERE roomId=? AND kind='reaction' " +
                                "AND targetEventId=? AND NOT EXISTS (SELECT 1 FROM ThreadRow rd " +
                                "WHERE rd.roomId=ThreadRow.roomId AND rd.kind='redaction' " +
                                "AND rd.targetEventId=ThreadRow.eventId)",
                            arrayOf(roomId, target),
                        )
                        sq.execSQL(
                            "UPDATE ThreadRow SET reactionSummary=? WHERE roomId=? AND eventId=?",
                            arrayOf<Any?>(
                                ThreadRowLogic.reactionSummaryOf(remaining),
                                roomId,
                                target,
                            ),
                        )
                    }
                    sq.setTransactionSuccessful()
                    removed
                } finally {
                    sq.endTransaction()
                }
            }.getOrDefault(0)
        }
    }

    /** Newest page (SPEC §6): message rows only, newest first. */
    suspend fun newestPage(c: MatrixClient, roomId: String, limit: Int): List<ThreadRowValues> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                queryRows(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND kind='message' " +
                        "ORDER BY timestampMs DESC, ingestSeq DESC LIMIT ?",
                    arrayOf<Any?>(roomId, limit),
                )
            }.getOrDefault(emptyList<ThreadRowValues>())
        }
    }

    /** Older page (SPEC §6): strict keyset before `(timestampMs, ingestSeq)` —
     *  no OFFSET, bridged inserts don't shift it. */
    suspend fun olderPage(
        c: MatrixClient,
        roomId: String,
        before: Pair<Long, Int>,
        limit: Int,
    ): List<ThreadRowValues> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                queryRows(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND kind='message' " +
                        "AND (timestampMs < ? OR (timestampMs = ? AND ingestSeq < ?)) " +
                        "ORDER BY timestampMs DESC, ingestSeq DESC LIMIT ?",
                    arrayOf<Any?>(roomId, before.first, before.first, before.second, limit),
                )
            }.getOrDefault(emptyList<ThreadRowValues>())
        }
    }

    /** Seed/fallback trigger (SPEC §7): does the room have message rows? */
    suspend fun rowCount(c: MatrixClient, roomId: String): Int {
        val db = database(c) ?: return 0
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM ThreadRow WHERE roomId=? AND kind='message'",
                    arrayOf(roomId),
                ).use { cur ->
                    check(cur.moveToFirst())
                    cur.getInt(0)
                }
            }.getOrDefault(0)
        }
    }

    /** hasMore (SPEC §6): the deepest served row still has chain
     *  (`prevEventId`) or gap-token (`batchBefore`) links — no walk. */
    suspend fun hasMoreFrom(c: MatrixClient, roomId: String, deepestEventId: String): Boolean {
        val db = database(c) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase.query(
                    "SELECT prevEventId, batchBefore FROM ThreadRow WHERE roomId=? AND eventId=?",
                    arrayOf(roomId, deepestEventId),
                ).use { cur ->
                    cur.moveToFirst() && (!cur.isNull(0) || !cur.isNull(1))
                }
            }.getOrDefault(false)
        }
    }

    /** The room's oldest message row — the scroll-up resume point (SPEC §6):
     *  its chain/backfill links tell whether deeper history exists and where
     *  the read path's legacy fallback continues from. */
    suspend fun deepestRow(c: MatrixClient, roomId: String): ThreadRowValues? {
        val db = database(c) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                queryFirst(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND kind='message' " +
                        "ORDER BY timestampMs ASC, ingestSeq ASC LIMIT 1",
                    arrayOf(roomId),
                )
            }.getOrNull()
        }
    }

    /** The row holding [eventId] as its own event — the legacy-cursor mapping:
     *  a compute-engine event-id cursor rides the keyset serve path when the
     *  store holds the event ([MatrixRepository.getMessages]). */
    suspend fun rowForEvent(c: MatrixClient, roomId: String, eventId: String): ThreadRowValues? {
        val db = database(c) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                queryFirst(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND eventId=? LIMIT 1",
                    arrayOf(roomId, eventId),
                )
            }.getOrNull()
        }
    }

    /** The row whose gap-resume link (`batchBefore`) is [token] — a bridge
     *  batch token (Instagram's `e-…`) is a resume link, never a chain event
     *  id: "older than the row carrying it" is the request it encodes. */
    suspend fun rowForBatchBefore(c: MatrixClient, roomId: String, token: String): ThreadRowValues? {
        val db = database(c) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                queryFirst(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND batchBefore=? LIMIT 1",
                    arrayOf(roomId, token),
                )
            }.getOrNull()
        }
    }

    /** All edit side rows, any room — the media-edit heal's scan input. */
    suspend fun editRows(c: MatrixClient): List<ThreadRowValues> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                queryRows(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE kind='edit' ORDER BY roomId, ingestSeq",
                    arrayOf<String>(),
                )
            }.getOrDefault(emptyList())
        }
    }

    /**
     * One media-edit heal write ([MatrixRepository.repairStoreMediaEdits]):
     * correct the stored edit side row (payload/type/mediaMeta) and
     * reclassify its message target in place. The target is skipped when it
     * already carries a media classification, is redacted, or is an
     * undecrypted placeholder (the recheck re-folds it once resolved).
     * Returns true when either write changed a row.
     */
    suspend fun applyMediaEditHeal(
        c: MatrixClient,
        roomId: String,
        editEventId: String,
        targetEventId: String,
        body: String,
        contentType: String,
    ): Boolean {
        val db = database(c) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                var changed = false
                val side = queryFirst(
                    sq,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND eventId=? AND kind='edit' LIMIT 1",
                    arrayOf(roomId, editEventId),
                )
                if (side != null && (side.payload != body || side.contentType != contentType)) {
                    sq.execSQL(
                        "UPDATE ThreadRow SET payload=?,contentType=?,mediaMeta=NULL " +
                            "WHERE roomId=? AND eventId=? AND kind='edit'",
                        arrayOf<Any?>(body, contentType, roomId, editEventId),
                    )
                    changed = true
                }
                val target = queryFirst(
                    sq,
                    "SELECT * FROM ThreadRow WHERE roomId=? AND eventId=? AND kind='message' LIMIT 1",
                    arrayOf(roomId, targetEventId),
                )
                val mediaType = target?.contentType in
                    listOf("image", "video", "audio", ThreadRowLogic.CONTENT_REDACTED)
                if (target != null && target.encrypted == 0 && !mediaType &&
                    (target.body != body || target.contentType != contentType)
                ) {
                    sq.execSQL(
                        "UPDATE ThreadRow SET body=?,contentType=? " +
                            "WHERE roomId=? AND eventId=? AND kind='message'",
                        arrayOf<Any?>(body, contentType, roomId, targetEventId),
                    )
                    changed = true
                }
                changed
            }.getOrDefault(false)
        }
    }

    /** Fresh-login probe (SPEC §8 trigger): does the store hold ANY rows?
     *  The probe is taken at client attach — before the first sync round can
     *  write — so an empty store means a fresh login, not an unwritten one. */
    suspend fun anyRows(c: MatrixClient): Boolean {
        val db = database(c) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                ensureTable(sq)
                sq.query("SELECT 1 FROM ThreadRow LIMIT 1", arrayOf<String>())
                    .use { it.moveToFirst() }
            }.getOrDefault(false)
        }
    }

    /** Interrupted-pass probe (SPEC §8 resume): any Part H resume TOKENS?
     *  Only the backfill worker writes this table, so any row means a pass
     *  died mid-walk — except cap sentinels (NULL token): a capped room is
     *  done for this login and must NOT re-trigger passes. */
    suspend fun hasBackfillBookmarks(c: MatrixClient): Boolean {
        val db = database(c) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                ensureTable(sq)
                sq.query(
                    "SELECT 1 FROM ThreadRowCursor WHERE batchBefore IS NOT NULL LIMIT 1",
                    arrayOf<String>(),
                ).use { it.moveToFirst() }
            }.getOrDefault(false)
        }
    }

    /** Side rows of [kind] targeting any of [targetEventIds] — the page's rows
     *  joined against the target index in memory. With [excludeRedacted],
     *  rows an unsend (a redaction row targeting the side row) removed are
     *  dropped — the same rule the fold's summary recompute applies. */
    suspend fun rowsForTargets(
        c: MatrixClient,
        roomId: String,
        kind: String,
        targetEventIds: Collection<String>,
        excludeRedacted: Boolean,
    ): List<ThreadRowValues> {
        if (targetEventIds.isEmpty()) return emptyList()
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val redactGuard = if (excludeRedacted) {
                    " AND NOT EXISTS (SELECT 1 FROM ThreadRow rd " +
                        "WHERE rd.roomId=ThreadRow.roomId AND rd.kind='redaction' " +
                        "AND rd.targetEventId=ThreadRow.eventId)"
                } else ""
                val sq = db.openHelper.writableDatabase
                val out = ArrayList<ThreadRowValues>()
                targetEventIds.chunked(100).forEach { chunk ->
                    val holes = chunk.joinToString(",") { "?" }
                    out += queryRows(
                        sq,
                        "SELECT * FROM ThreadRow WHERE roomId=? AND kind=? " +
                            "AND targetEventId IN ($holes)$redactGuard ORDER BY ingestSeq",
                        arrayOf<Any?>(roomId, kind, *chunk.toTypedArray()),
                    )
                }
                out
            }.getOrDefault(emptyList<ThreadRowValues>())
        }
    }

    /** Persist (or, with null, clear) a room's Part H backfill bookmark:
     *  the walk's resume token plus [fetchedCount], the events already
     *  fetched toward this login's cap — a bookmark restart must NOT reset
     *  the counter (the walk resumes the budget, it does not restart it). */
    suspend fun markBackfillCursor(
        c: MatrixClient,
        roomId: String,
        batchBefore: String?,
        fetchedCount: Int = 0,
    ) {
        val db = database(c) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                sq.beginTransaction()
                try {
                    ensureTable(sq)
                    if (batchBefore == null) {
                        sq.execSQL("DELETE FROM ThreadRowCursor WHERE roomId=?", arrayOf(roomId))
                    } else {
                        sq.execSQL(
                            "INSERT OR REPLACE INTO ThreadRowCursor(roomId,batchBefore,fetchedCount) " +
                                "VALUES(?,?,?)",
                            arrayOf<Any?>(roomId, batchBefore, fetchedCount),
                        )
                    }
                    sq.setTransactionSuccessful()
                } finally {
                    sq.endTransaction()
                }
            }.onFailure { Log.w(TAG, "cursor write failed: ${it.message}") }
        }
    }

    /** Read a room's backfill resume token (null = no bookmark / room done /
     *  cap sentinel). Synchronous — the caller runs it on Dispatchers.IO.
     *  Returns null when the schema isn't there yet (fresh store). */
    fun backfillCursor(db: SupportSQLiteDatabase, roomId: String): String? = runCatching {
        db.query(
            "SELECT batchBefore FROM ThreadRowCursor WHERE roomId=?",
            arrayOf(roomId),
        ).use { cur -> if (cur.moveToFirst() && !cur.isNull(0)) cur.getString(0) else null }
    }.getOrNull()

    /** A room's Part H bookmark as the worker consumes it. [batchBefore]
     *  null = the CAP SENTINEL (capped this login — [fetchedCount] reached
     *  the cap; token readers ignore it and resumed passes skip the room).
     *  A null [BackfillBookmark] return = no row at all (room complete via
     *  chain end, never visited, or fresh store). */
    data class BackfillBookmark(val batchBefore: String?, val fetchedCount: Int)

    /** A room's Part H bookmark, or null (no row / fresh store). */
    suspend fun backfillBookmark(c: MatrixClient, roomId: String): BackfillBookmark? {
        val db = database(c) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                ensureTable(sq)
                sq.query(
                    "SELECT batchBefore, fetchedCount FROM ThreadRowCursor WHERE roomId=?",
                    arrayOf(roomId),
                ).use { cur ->
                    if (cur.moveToFirst()) {
                        BackfillBookmark(
                            batchBefore = nullableString(cur, "batchBefore"),
                            fetchedCount = cur.getInt(cur.getColumnIndexOrThrow("fetchedCount")),
                        )
                    } else null
                }
            }.getOrNull()
        }
    }

    /** The cap sentinel (fix round 2): a row with a NULL token and the
     *  accumulated count. Distinguishes "capped this login" (skip on
     *  resumed passes) from "never visited"; token readers and the pass
     *  trigger ([hasBackfillBookmarks]) ignore NULL-token rows, so the
     *  sentinel neither re-triggers passes nor feeds a walk. */
    suspend fun markBackfillCapped(c: MatrixClient, roomId: String, fetchedCount: Int) {
        val db = database(c) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val sq = db.openHelper.writableDatabase
                sq.beginTransaction()
                try {
                    ensureTable(sq)
                    sq.execSQL(
                        "INSERT OR REPLACE INTO ThreadRowCursor(roomId,batchBefore,fetchedCount) " +
                            "VALUES(?,NULL,?)",
                        arrayOf<Any?>(roomId, fetchedCount),
                    )
                    sq.setTransactionSuccessful()
                } finally {
                    sq.endTransaction()
                }
            }.onFailure { Log.w(TAG, "cursor write failed: ${it.message}") }
        }
    }

    /** Upgrade guard (SPEC §8 trigger): does Trixnity's OWN timeline store
     *  hold events? A genuine fresh login has an empty database entirely
     *  (logout's deleteDatabase wipes it); an upgrade to this build has a
     *  populated `TimelineEvent` table with a brand-new, EMPTY `ThreadRow`
     *  table — that combination must NOT read as "fresh login" and fire a
     *  network bulk backfill (§7 rejected it; §8 excludes upgrades — Part
     *  G's lazy seed covers them, no network). */
    suspend fun timelineStoreHasEvents(c: MatrixClient): Boolean {
        val db = database(c) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase
                    .query("SELECT 1 FROM TimelineEvent LIMIT 1", arrayOf<String>())
                    .use { it.moveToFirst() }
            }.getOrDefault(false)
        }
    }

    /** Room ids from RoomProjection, most recently active first. */
    suspend fun recentProjectionRoomIds(c: MatrixClient, limit: Int): List<String> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase
                    .query(
                        "SELECT roomId FROM RoomProjection ORDER BY lastRealTs DESC LIMIT ?",
                        arrayOf(limit.toString()),
                    ).use { cur ->
                        buildList {
                            while (cur.moveToNext()) add(cur.getString(0))
                        }
                    }
            }.getOrDefault(emptyList())
        }
    }

    /** Newest [window] TimelineEvent ids for [roomId] (store insertion order)
     *  that have NO ThreadRow — the re-ingest repair candidates. */
    suspend fun missingRecentEventIds(c: MatrixClient, roomId: String, window: Int): List<String> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase
                    .query(
                        "SELECT te.eventId FROM TimelineEvent te " +
                            "LEFT JOIN ThreadRow r ON r.roomId = te.roomId AND r.eventId = te.eventId " +
                            "WHERE te.roomId = ? AND r.eventId IS NULL AND $ROW_ELIGIBLE_SQL " +
                            "ORDER BY te.rowid DESC LIMIT ?",
                        arrayOf(roomId, window.toString()),
                    ).use { cur ->
                        buildList {
                            while (cur.moveToNext()) add(cur.getString(0))
                        }
                    }
            }.getOrDefault(emptyList())
        }
    }

    /** Rooms holding row-eligible TimelineEvents with NO ThreadRow at any
     *  depth — the deep-repair rotation candidates. The recent-activity repair
     *  only reaches the newest window; deep history (pre-store installs,
     *  fresh-login backfill leftovers) never converges otherwise. Events that
     *  can never gain a row (state events) are excluded — unfiltered, the
     *  room set never empties and the deep repair's done-set freezes rooms
     *  marked done before their keys landed (see [ROW_ELIGIBLE_SQL] and
     *  ThreadRowLogic.ROW_ELIGIBLE_EVENT_TYPES). */
    suspend fun roomIdsWithMissingEvents(c: MatrixClient): List<String> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase
                    .query(
                        "SELECT DISTINCT te.roomId FROM TimelineEvent te " +
                            "LEFT JOIN ThreadRow r ON r.roomId = te.roomId AND r.eventId = te.eventId " +
                            "WHERE r.eventId IS NULL AND $ROW_ELIGIBLE_SQL",
                        arrayOf<String>(),
                    ).use { cur ->
                        buildList {
                            while (cur.moveToNext()) add(cur.getString(0))
                        }
                    }
            }.getOrDefault(emptyList())
        }
    }

    /** Page of TimelineEvent ids for [roomId] with NO ThreadRow, strictly
     *  older than [beforeRowid] (start with [Long.MAX_VALUE]), newest-first;
     *  pairs the ids with the smallest rowid reached — the next cursor, null
     *  when the room's missing history is exhausted. The cursor walks
     *  monotonically downward, so events the writer skips (own undecrypted
     *  echoes, non-renderable) can't loop the repair pass. */
    suspend fun missingEventIdsPage(
        c: MatrixClient,
        roomId: String,
        beforeRowid: Long,
        limit: Int,
    ): Pair<List<String>, Long?> {
        val db = database(c) ?: return emptyList<String>() to null
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase
                    .query(
                        "SELECT te.eventId, te.rowid FROM TimelineEvent te " +
                            "LEFT JOIN ThreadRow r ON r.roomId = te.roomId AND r.eventId = te.eventId " +
                            "WHERE te.roomId = ? AND r.eventId IS NULL AND te.rowid < ? AND $ROW_ELIGIBLE_SQL " +
                            "ORDER BY te.rowid DESC LIMIT ?",
                        arrayOf(roomId, beforeRowid.toString(), limit.toString()),
                    ).use { cur ->
                        val ids = ArrayList<String>()
                        var oldest: Long? = null
                        while (cur.moveToNext()) {
                            ids += cur.getString(0)
                            val rowid = cur.getLong(1)
                            oldest = minOf(oldest ?: rowid, rowid)
                        }
                        ids to oldest
                    }
            }.getOrDefault(emptyList<String>() to null)
        }
    }

    /** Rooms whose RoomProjection row holds `lastRealTs=0` (the projection
     *  updater never completed a pass) while the ThreadRow store holds message
     *  rows — the ts-0 list guard hides exactly these. See
     *  [MatrixRepository.reconcileTs0Projections]. RoomProjection may not exist
     *  yet (created lazily by the repository) — empty list then. */
    suspend fun ts0ProjectionRoomsWithRows(c: MatrixClient): List<String> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase
                    .query(
                        "SELECT p.roomId FROM RoomProjection p WHERE p.lastRealTs = 0 AND EXISTS (" +
                            "SELECT 1 FROM ThreadRow r WHERE r.roomId = p.roomId AND r.kind = 'message')",
                        arrayOf<String>(),
                    ).use { cur ->
                        buildList {
                            while (cur.moveToNext()) add(cur.getString(0))
                        }
                    }
            }.getOrDefault(emptyList())
        }
    }

    // --- internals ----------------------------------------------------------

    /** Missing-page filter: only events [ThreadRowLogic.buildRows] can ever
     *  give a row to (message class / redaction / send-status, or anything
     *  carrying an m.relates_to). Matches the serialized event's
     *  `"type":"…"` — Android's framework SQLite ships WITHOUT JSON1 (the
     *  standalone `sqlite3` binary has it; SQLiteDatabase does not — the
     *  json_extract variant silently failed via runCatching), and Trixnity
     *  serializes compact JSON, so the substring match is exact. False
     *  positives only cost the writer's skip; there are no false negatives
     *  for the eligible types. */
    private val ROW_ELIGIBLE_SQL =
        "(" +
            ThreadRowLogic.ROW_ELIGIBLE_EVENT_TYPES.joinToString(" OR ") {
                "te.value LIKE '%\"type\":\"$it\"%'"
            } +
            " OR te.value LIKE '%\"m.relates_to\"%')"

    private fun database(c: MatrixClient): TrixnityRoomDatabase? =
        runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()

    private fun insert(sq: SupportSQLiteDatabase, row: ThreadRowValues) {
        sq.execSQL(
            "INSERT OR REPLACE INTO ThreadRow(" +
                "roomId,eventId,kind,sender,timestampMs,ingestSeq,body,formattedHtml," +
                "contentType,replyToId,mediaMeta,sendStatus,encrypted,prevEventId," +
                "batchBefore,targetEventId,payload,reactionSummary) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                row.roomId,
                row.eventId,
                row.kind,
                row.sender,
                row.timestampMs,
                row.ingestSeq,
                row.body,
                row.formattedHtml,
                row.contentType,
                row.replyToId,
                row.mediaMeta,
                row.sendStatus,
                row.encrypted,
                row.prevEventId,
                row.batchBefore,
                row.targetEventId,
                row.payload,
                row.reactionSummary,
            ),
        )
    }

    /** Fold the side rows waiting for every message row this batch touches:
     *  batch message rows, targets of batch side rows, and — when a batch side
     *  row targets a reaction row (a reaction unsend) — that reaction's
     *  message, whose `reactionSummary` must refresh (SPEC §1). */
    private fun foldTargets(sq: SupportSQLiteDatabase, batch: List<ThreadRowValues>) {
        val queue = ArrayDeque<Pair<String, String>>() // roomId to eventId
        val seen = HashSet<Pair<String, String>>()
        for (row in batch) {
            if (row.kind == RowKind.MESSAGE.wire) queue += row.roomId to row.eventId
            row.targetEventId?.let { queue += row.roomId to it }
        }
        while (queue.isNotEmpty()) {
            val (roomId, eventId) = queue.removeFirst()
            if (!seen.add(roomId to eventId)) continue
            val target = queryFirst(
                sq,
                "SELECT * FROM ThreadRow WHERE roomId=? AND eventId=?",
                arrayOf(roomId, eventId),
            ) ?: continue // side row waits — applied when its target arrives
            when (target.kind) {
                RowKind.MESSAGE.wire -> foldTarget(sq, target)
                RowKind.REACTION.wire ->
                    target.targetEventId?.let { queue += target.roomId to it }
            }
        }
    }

    /** Re-apply ALL side rows of [target] in ingest order onto the stored
     *  values and write the folded cache columns back. Re-applying the full
     *  set keeps the fold convergent (idempotent) across writes, self-heals a
     *  target whose side rows arrived first, and rebuilds the summary cache
     *  from the reaction rows alone. A redaction is terminal: once applied,
     *  later edits must not resurrect the body. */
    private fun foldTarget(sq: SupportSQLiteDatabase, target: ThreadRowValues) {
        val sideRows = queryRows(
            sq,
            "SELECT * FROM ThreadRow WHERE roomId=? AND targetEventId=? " +
                "AND kind!='message' ORDER BY ingestSeq",
            arrayOf(target.roomId, target.eventId),
        )
        if (sideRows.isEmpty()) return
        val reactions = queryRows(
            sq,
            // Reactions still standing: a redaction row targeting the reaction
            // row (an unsend) removes it from the summary, not the ledger.
            "SELECT * FROM ThreadRow WHERE roomId=? AND kind='reaction' AND targetEventId=? " +
                "AND NOT EXISTS (SELECT 1 FROM ThreadRow rd " +
                "WHERE rd.roomId=ThreadRow.roomId AND rd.kind='redaction' " +
                "AND rd.targetEventId=ThreadRow.eventId) ORDER BY ingestSeq",
            arrayOf(target.roomId, target.eventId),
        )
        var out = target
        var redacted = target.contentType == ThreadRowLogic.CONTENT_REDACTED
        for (side in sideRows) {
            if (redacted && side.kind == RowKind.EDIT.wire) continue
            out = ThreadRowLogic.applySideRow(out, side, reactions)
            if (side.kind == RowKind.REDACTION.wire && side.targetEventId == target.eventId) {
                redacted = true
            }
        }
        if (out == target) return
        sq.execSQL(
            "UPDATE ThreadRow SET body=?,formattedHtml=?,contentType=?,sendStatus=?," +
                "reactionSummary=? WHERE roomId=? AND eventId=?",
            arrayOf<Any?>(
                out.body,
                out.formattedHtml,
                out.contentType,
                out.sendStatus,
                out.reactionSummary,
                out.roomId,
                out.eventId,
            ),
        )
    }

    private fun queryRows(
        sq: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?>,
    ): List<ThreadRowValues> = sq.query(sql, args).use { cur ->
        val rows = ArrayList<ThreadRowValues>(cur.count)
        while (cur.moveToNext()) rows += rowOf(cur)
        rows
    }

    private fun queryFirst(
        sq: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?>,
    ): ThreadRowValues? = sq.query(sql, args).use { cur ->
        if (cur.moveToFirst()) rowOf(cur) else null
    }

    private fun rowOf(cur: Cursor): ThreadRowValues = ThreadRowValues(
        roomId = cur.getString(cur.getColumnIndexOrThrow("roomId")),
        eventId = cur.getString(cur.getColumnIndexOrThrow("eventId")),
        kind = cur.getString(cur.getColumnIndexOrThrow("kind")),
        sender = nullableString(cur, "sender"),
        timestampMs = nullableLong(cur, "timestampMs") ?: 0L,
        ingestSeq = cur.getInt(cur.getColumnIndexOrThrow("ingestSeq")),
        body = nullableString(cur, "body"),
        formattedHtml = nullableString(cur, "formattedHtml"),
        contentType = nullableString(cur, "contentType"),
        replyToId = nullableString(cur, "replyToId"),
        mediaMeta = nullableString(cur, "mediaMeta"),
        sendStatus = nullableString(cur, "sendStatus"),
        encrypted = cur.getInt(cur.getColumnIndexOrThrow("encrypted")),
        prevEventId = nullableString(cur, "prevEventId"),
        batchBefore = nullableString(cur, "batchBefore"),
        targetEventId = nullableString(cur, "targetEventId"),
        payload = nullableString(cur, "payload"),
        reactionSummary = nullableString(cur, "reactionSummary"),
    )

    private fun nullableString(cur: Cursor, column: String): String? {
        val i = cur.getColumnIndexOrThrow(column)
        return if (cur.isNull(i)) null else cur.getString(i)
    }

    private fun nullableLong(cur: Cursor, column: String): Long? {
        val i = cur.getColumnIndexOrThrow(column)
        return if (cur.isNull(i)) null else cur.getLong(i)
    }
}
