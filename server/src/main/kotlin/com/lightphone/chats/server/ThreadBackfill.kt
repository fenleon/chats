package com.lightphone.chats.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Part H — fresh-login bounded ThreadRow backfill
 * (docs/THREAD-STORE-SPEC.md §8, user decision 2026-09-18).
 *
 * Two halves:
 * - [ThreadBackfill] (this file's pure object): the cursor state machine —
 *   fresh-login detection, per-room cap/chain-end decisions, resume-from-
 *   bookmark, room-done transition. No Android, no I/O; kotlin-test covered
 *   in `ThreadBackfillCursorTest.kt`, same split as [ThreadRowLogic].
 * - [ThreadBackfill.start]: the thin worker that drives the machine against
 *   the real APIs through a [ThreadBackfill.Deps] seam (MatrixRepository
 *   implements it — the chain walk, the gap `/messages` fill and the Task 5
 *   write seam are all private there).
 *
 * Decisions implemented verbatim from the spec (do not re-litigate): fresh
 * login ONLY (empty store; an existing install is Part G's lazy seed's
 * business), automatic (no charging/wifi gate, no toggle, no global
 * budget), rooms in the EXISTING room-list order, sequential one room at a
 * time at a trickle, per-room stop at [THREAD_BACKFILL_MAX_EVENTS] fetched
 * or the room's creation (chain end), resumable via the per-room
 * `ThreadRowCursor` bookmark (the `batchBefore` gap-token semantics),
 * progress surfaced only through the existing Settings synced-rooms line,
 * media never fetched. Each fetched batch lands through the Task 5
 * write-through seam, so seed retirement and the writer's fold semantics
 * apply for free — and the walk heals `limited`-sync gaps as it passes.
 */
object ThreadBackfill {

    /** Per-room depth cap (SPEC §8): stop after this many events fetched in
     *  one pass, or at the room's creation (chain end), whichever first.
     *  The spec's single tuning knob. */
    const val THREAD_BACKFILL_MAX_EVENTS = 1000

    /** Trickle pacing (SPEC §8): a short pause between fetch rounds inside
     *  one room, a longer one between rooms — the walk must not crowd the
     *  homeserver or the sync loop. */
    const val ROUND_DELAY_MS = 500L
    const val ROOM_DELAY_MS = 3_000L

    /** Room-list wait when the pass starts before the room map surfaced
     *  (the restore path starts the worker before the initial sync lands —
     *  the same silent-return kill `backfillProjection` hit on the LP3). */
    const val ROOM_LIST_ATTEMPTS = 5
    const val ROOM_LIST_RETRY_MS = 30_000L

    enum class Phase { IDLE, FRESH, RUNNING, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Room currently being walked (null before the first / after the last). */
        val roomId: String? = null,
        /** Events fetched for the current room this pass — the cap's counter. */
        val fetched: Int = 0,
        /** Rooms still to visit, in the existing room-list order. */
        val remaining: List<String> = emptyList(),
        /** The current room's resume token (the persisted bookmark, or the
         *  last round's `batchBefore`). Null = no bookmark / fresh room. */
        val batchBefore: String? = null,
    )

    /**
     * The pass trigger (SPEC §8): an EMPTY ThreadRow store is a fresh login
     * → backfill; resume bookmarks from an interrupted pass (process death,
     * rate limit, reboot) → resume from them; anything else (an existing
     * install with no bookmarks — Part G's lazy seed covers it; a pass
     * already finished) → no pass. The empty-store probe is taken at client
     * attach, BEFORE the first sync round can write rows.
     */
    fun onTriggerProbed(state: State, storeEmpty: Boolean, hasBookmarks: Boolean): State {
        if (state.phase != Phase.IDLE) return state
        return if (storeEmpty || hasBookmarks) state.copy(phase = Phase.FRESH)
        else state.copy(phase = Phase.DONE)
    }

    /** Seed the pass with the rooms in their existing room-list order. */
    fun startPass(state: State, rooms: List<String>): State {
        if (state.phase != Phase.FRESH) return state
        return state.copy(phase = Phase.RUNNING, remaining = rooms)
    }

    /**
     * Advance to the next room in list order, counters reset; [Phase.DONE]
     * when the list is exhausted. The caller seeds the room's resume token
     * from the store with [resume].
     */
    fun nextRoom(state: State): State {
        if (state.phase != Phase.RUNNING) return state
        val next = state.remaining.firstOrNull()
            ?: return state.copy(
                phase = Phase.DONE,
                roomId = null,
                remaining = emptyList(),
                fetched = 0,
                batchBefore = null,
            )
        return state.copy(
            roomId = next,
            remaining = state.remaining - next,
            fetched = 0,
            batchBefore = null,
        )
    }

    /**
     * Continue the current room from its persisted bookmark (SPEC §8
     * resumable): the walk resumes where the bookmark left off — it does
     * not restart — and the cap counts events fetched from here on (a
     * resumed room gets a fresh per-pass budget). No bookmark → no-op.
     */
    fun resume(state: State, roomId: String, bookmark: String?): State {
        if (state.phase != Phase.RUNNING || state.roomId != roomId || bookmark == null) return state
        return state.copy(batchBefore = bookmark)
    }

    /**
     * One fetched round's decision. [fetchedN] events arrived for [roomId]
     * and [batchBefore] is the round's new resume token — null means the
     * chain ended (the room's creation: nothing more server-side).
     * `roomDone` stops the room; `persistCursor` is what the caller must
     * persist: the advanced token mid-room and at the cap (a capped room
     * KEEPS its bookmark so a later pass resumes deeper), null at chain end
     * (the bookmark is cleared — the room is complete). A round for any
     * room other than the current one is ignored.
     */
    data class Advance(val state: State, val persistCursor: String?, val roomDone: Boolean)

    fun advance(state: State, roomId: String, fetchedN: Int, batchBefore: String?): Advance {
        if (state.phase != Phase.RUNNING || state.roomId != roomId) {
            return Advance(state, persistCursor = null, roomDone = false)
        }
        val fetched = state.fetched + fetchedN
        val roomDone = batchBefore == null || fetched >= THREAD_BACKFILL_MAX_EVENTS
        return Advance(
            state = state.copy(fetched = fetched, batchBefore = batchBefore),
            persistCursor = batchBefore,
            roomDone = roomDone,
        )
    }

    /** One fetch round's outcome, mapped by [advance]. */
    data class RoomStep(
        /** Rows the round actually inserted (any kind) — already-stored
         *  events re-walked on resume don't double-count against the cap. */
        val fetched: Int,
        /** The round's new resume token; null = chain end (room complete). */
        val batchBefore: String?,
    )

    /** What the worker needs from the real world — implemented by
     *  MatrixRepository, which owns the walk/fill/write internals. */
    interface Deps {
        /** Joined rooms in the existing room-list order, or null while the
         *  list is not known yet (initial sync in flight). */
        suspend fun roomIds(): List<String>?

        /** The fresh-login probe taken at client attach (null = probe not
         *  landed yet — treated as not-fresh, the conservative side). */
        suspend fun storeEmpty(): Boolean

        /** Any `ThreadRowCursor` bookmarks — an interrupted pass to resume. */
        suspend fun hasBackfillBookmarks(): Boolean

        suspend fun backfillCursor(roomId: String): String?
        suspend fun markBackfillCursor(roomId: String, batchBefore: String?)

        /**
         * One fetch round for [roomId]: chain walk → one gap `/messages`
         * window when the walk ends on a gap marker → write through the
         * Task 5 seam. Returns null when the round could not advance
         * (walk unavailable, fill failed, nothing new written) — the caller
         * keeps the bookmark and the room resumes on a later pass.
         */
        suspend fun stepRoom(roomId: String): RoomStep?

        /** Opt-in diagnostics — counts and room suffixes only, never
         *  tokens or bodies. */
        fun log(message: String)
    }

    /** Launch the worker on [scope]. Fire-and-forget: the trigger probe and
     *  pacing keep it harmless, and logout cancels it with the session. */
    fun start(scope: CoroutineScope, deps: Deps): Job = scope.launch {
        runCatching { runWorker(deps) }
            .onFailure { deps.log("backfill: pass ended: ${it.message}") }
    }

    private suspend fun runWorker(deps: Deps) {
        val probed = onTriggerProbed(
            State(), deps.storeEmpty(), deps.hasBackfillBookmarks(),
        )
        if (probed.phase != Phase.FRESH) return
        // The room list often isn't surfaced yet on a cold login (the same
        // kill backfillProjection's retry guards against) — wait for it.
        var rooms: List<String>? = null
        repeat(ROOM_LIST_ATTEMPTS) {
            if (rooms != null) return@repeat
            rooms = deps.roomIds()
            if (rooms == null) delay(ROOM_LIST_RETRY_MS)
        }
        val list = rooms
        if (list == null) {
            deps.log("backfill: room list never surfaced — pass skipped")
            return
        }
        var state = startPass(probed, list)
        deps.log("backfill: pass starting (${list.size} rooms, cap $THREAD_BACKFILL_MAX_EVENTS/room)")
        while (state.phase == Phase.RUNNING) {
            val roomId = state.roomId ?: break
            state = resume(state, roomId, deps.backfillCursor(roomId))
            while (true) {
                val step = deps.stepRoom(roomId)
                if (step == null) {
                    deps.log("backfill: ${roomId.takeLast(12)} stalled — resumes next pass")
                    break
                }
                val a = advance(state, roomId, step.fetched, step.batchBefore)
                deps.markBackfillCursor(roomId, a.persistCursor)
                state = a.state
                if (a.roomDone) {
                    deps.log(
                        "backfill: ${roomId.takeLast(12)} done — ${state.fetched} row(s) this pass",
                    )
                    break
                }
                delay(ROUND_DELAY_MS)
            }
            val more = state.remaining.isNotEmpty()
            state = nextRoom(state)
            if (more) delay(ROOM_DELAY_MS)
        }
        deps.log("backfill: pass complete")
    }
}
