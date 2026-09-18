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

    /** Part H backfill bookmark: one resume token per room. Lives in the same
     *  SQLite (not PREFS) so it dies with the store at logout — a re-login
     *  backfills from scratch, per SPEC §8. */
    private const val DDL_CURSOR =
        "CREATE TABLE IF NOT EXISTS ThreadRowCursor (" +
            "roomId TEXT NOT NULL PRIMARY KEY," +
            "batchBefore TEXT)"

    /** Create the store schema. Cheap (IF NOT EXISTS) — call it before every
     *  use, like ensureProjectionTable's callers do. */
    fun ensureTable(db: SupportSQLiteDatabase) {
        db.execSQL(DDL_THREADROW)
        db.execSQL(DDL_IDX_PAGE)
        db.execSQL(DDL_IDX_TARGET)
        db.execSQL(DDL_IDX_PENDING)
        db.execSQL(DDL_CURSOR)
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
     */
    suspend fun pendingRows(c: MatrixClient, limit: Int): List<ThreadRowValues> {
        val db = database(c) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                queryRows(
                    db.openHelper.writableDatabase,
                    "SELECT * FROM ThreadRow WHERE encrypted=1 " +
                        "ORDER BY roomId, ingestSeq LIMIT ?",
                    arrayOf<Any?>(limit),
                )
            }.getOrDefault(emptyList<ThreadRowValues>())
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

    /** Persist (or, with null, clear) a room's Part H backfill resume token. */
    suspend fun markBackfillCursor(c: MatrixClient, roomId: String, batchBefore: String?) {
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
                            "INSERT OR REPLACE INTO ThreadRowCursor(roomId,batchBefore) VALUES(?,?)",
                            arrayOf(roomId, batchBefore),
                        )
                    }
                    sq.setTransactionSuccessful()
                } finally {
                    sq.endTransaction()
                }
            }.onFailure { Log.w(TAG, "cursor write failed: ${it.message}") }
        }
    }

    /** Read a room's backfill resume token (null = no bookmark / room done).
     *  Synchronous — the caller runs it on Dispatchers.IO. Returns null when
     *  the schema isn't there yet (fresh store). */
    fun backfillCursor(db: SupportSQLiteDatabase, roomId: String): String? = runCatching {
        db.query(
            "SELECT batchBefore FROM ThreadRowCursor WHERE roomId=?",
            arrayOf(roomId),
        ).use { cur -> if (cur.moveToFirst() && !cur.isNull(0)) cur.getString(0) else null }
    }.getOrNull()

    // --- internals ----------------------------------------------------------

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
