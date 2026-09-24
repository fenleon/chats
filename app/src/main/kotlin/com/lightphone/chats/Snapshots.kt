package com.lightphone.chats

import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.lightJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Last-known-state disk snapshots behind "instant paint": the room list and
 * the most recently opened threads' newest page persist as small JSON files
 * in the tool's private filesDir (the SDK's SealedLightContext.filesDir,
 * captured once by the list screen's createViewModel), so a cold start paints
 * the last known rooms and a room reopen after a process death paints the
 * last known page on the first frame — the normal fetches (with their retry
 * loops) then refresh in place through the existing flows.
 *
 * Writes are serialized on [Dispatchers.IO], deduped by content signature (a
 * quiet open rewrites nothing) and atomic (tmp + rename) — a torn file reads
 * as no snapshot, never as garbage. Everything is wrapped in runCatching: a
 * missing/corrupt snapshot is invisible, never a crash. Snapshot contents are
 * never logged.
 *
 * Account scoping: files carry the owning account's userId; the list's
 * refresh reconciles the tag against the live account (a process killed
 * between an account switch and the clear drops the stale rows at the first
 * account state), and every account transition clears both files (see
 * [clearAll] callers in ChatClient).
 */
object Snapshots {

    private const val ROOMS_FILE = "rooms-snapshot.json"
    private const val THREADS_FILE = "thread-snapshots.json"
    private const val MAX_ROOMS = 50
    private const val MAX_THREAD_ROWS = 60
    private const val MAX_THREAD_ROOMS = 20

    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dir: File? = null

    /** One-time capture of the tool's filesDir (idempotent — every screen's
     *  createViewModel calls this; the list screen is first either way). */
    fun init(filesDir: File) {
        if (dir == null) dir = filesDir
    }

    // ---------- rooms ----------

    @Serializable
    private data class RoomsFile(
        val userId: String? = null,
        val rooms: List<LightServiceMethod.GetRooms.Room> = emptyList(),
    )

    /** The account the currently seeded rooms belong to (from the file's tag;
     *  null = nothing seeded this process). */
    @Volatile
    var seededRoomsUserId: String? = null
        private set

    @Volatile
    private var roomsSignature: Int = 0

    /**
     * The last-known room list (repository order, ≤[MAX_ROOMS]); empty when
     * no usable snapshot exists. Called synchronously at ViewModel creation —
     * the read is a small file, and the first frame must already carry it.
     */
    fun seedRooms(): List<LightServiceMethod.GetRooms.Room> {
        val d = dir ?: return emptyList()
        val file = File(d, ROOMS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val parsed = lightJson.decodeFromString(RoomsFile.serializer(), file.readText())
            seededRoomsUserId = parsed.userId
            parsed.rooms
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Persists a successful non-empty refresh (newest ≤[MAX_ROOMS] rooms in
     * repository order, tagged with the account userId). Content-deduped: a
     * quiet refresh that changed nothing rewrites nothing.
     */
    fun saveRooms(userId: String?, rooms: List<LightServiceMethod.GetRooms.Room>) {
        val d = dir ?: return
        if (rooms.isEmpty()) return
        val kept = rooms.take(MAX_ROOMS)
        val signature = kept.hashCode()
        if (signature == roomsSignature) return
        roomsSignature = signature
        writes.launch {
            runCatching {
                writeAtomic(File(d, ROOMS_FILE), lightJson.encodeToString(RoomsFile.serializer(), RoomsFile(userId, kept)))
            }
        }
    }

    // ---------- threads ----------

    @Serializable
    private data class ThreadRoom(
        val roomId: String,
        val lastOpenAtMs: Long,
        val messages: List<LightServiceMethod.GetMessages.Message> = emptyList(),
    )

    @Serializable
    private data class ThreadsFile(
        val userId: String? = null,
        val rooms: List<ThreadRoom> = emptyList(),
    )

    private val threadSignatures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The saved newest page for [roomId] (oldest-first), or empty. */
    fun seedThread(roomId: String): List<LightServiceMethod.GetMessages.Message> {
        val d = dir ?: return emptyList()
        val file = File(d, THREADS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            lightJson.decodeFromString(ThreadsFile.serializer(), file.readText())
                .rooms.firstOrNull { it.roomId == roomId }?.messages.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Persists [messages]'s newest [MAX_THREAD_ROWS] rows for [roomId],
     * keeping the [MAX_THREAD_ROOMS] most recently saved rooms (LRU by save
     * time — a room re-enters the file on its next successful serve).
     * Content-deduped per room: an open whose newest row didn't change
     * rewrites nothing.
     */
    fun saveThread(userId: String?, roomId: String, messages: List<LightServiceMethod.GetMessages.Message>) {
        val d = dir ?: return
        if (messages.isEmpty()) return
        val kept = messages.takeLast(MAX_THREAD_ROWS)
        val signature = kept.hashCode()
        if (threadSignatures.put(roomId, signature) == signature) return
        if (threadSignatures.size > MAX_THREAD_ROOMS * 2) threadSignatures.clear()
        writes.launch {
            runCatching {
                val file = File(d, THREADS_FILE)
                val current = if (file.exists()) {
                    try {
                        lightJson.decodeFromString(ThreadsFile.serializer(), file.readText())
                    } catch (_: Exception) {
                        ThreadsFile()
                    }
                } else ThreadsFile()
                val entry = ThreadRoom(roomId, System.currentTimeMillis(), kept)
                val rooms = (current.rooms.filterNot { it.roomId == roomId } + entry)
                    .sortedByDescending { it.lastOpenAtMs }
                    .take(MAX_THREAD_ROOMS)
                writeAtomic(file, lightJson.encodeToString(ThreadsFile.serializer(), ThreadsFile(userId, rooms)))
            }
        }
    }

    // ---------- lifecycle ----------

    /** Drops both snapshots — called on every account transition (logout,
     *  setAccount, Beeper login), alongside the in-memory cache clears. */
    fun clearAll() {
        val d = dir ?: return
        seededRoomsUserId = null
        roomsSignature = 0
        threadSignatures.clear()
        writes.launch {
            runCatching {
                File(d, ROOMS_FILE).delete()
                File(d, THREADS_FILE).delete()
            }
        }
    }

    /** tasks' TaskRepository idiom: write a sibling tmp file, rename over the
     *  target — a crash mid-write leaves the previous snapshot intact. */
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
