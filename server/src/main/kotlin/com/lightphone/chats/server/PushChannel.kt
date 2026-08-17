package com.lightphone.chats.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.clientserverapi.model.push.PusherData
import net.folivo.trixnity.clientserverapi.model.push.SetPushers
import net.folivo.trixnity.client.MatrixClient
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Push-based wake-up for the companion (2026-08-16, chats/push/README.md).
 *
 * The sync loop is the delivery mechanism for notifications, but only as
 * often as it polls. Matrix HTTP pushers fix the latency: the homeserver
 * gets a pusher whose `data.url` is an endpoint that serves the exact path
 * `/_matrix/push/v1/notify` (both Synapse and Beeper validate the path
 * exactly). Two endpoint options: the user's own gateway
 * (chats/push/gateway.py), reachable from the homeserver over a public
 * tunnel — or ntfy.sh, whose Matrix Push Gateway serves that exact path
 * and routes to the topic named in the pusher's pushkey
 * (`https://ntfy.sh/<topic>`), which the SSE side then subscribes to (no
 * tunnel; the topic is a per-install bearer token). The homeserver POSTs a
 * small event-id-only payload on every new message; this object holds an
 * SSE subscription and turns each notification into one
 * [MatrixRepository.onPushDelivered] — a single syncOnce round while idle,
 * which the existing notification watcher turns into the local
 * notification.
 *
 * No tokens leave the phone: the payload carries no message content or keys —
 * push is a wake-up signal only.
 *
 * Two URLs are stored separately because one side sees the gateway from the
 * phone and the other from the homeserver: the notify URL must be public
 * (Beeper POSTs from their servers), while the SSE URL is whatever the phone
 * can reach (LAN IP, tunnel, or adb-reverse loopback in dev). With no config
 * a private ntfy.sh channel is auto-provisioned on first start (see [start]);
 * the `--es pushsse` / `--es pushnotify` / `--es pushkey` dev extras override
 * for a self-hosted gateway.
 */
object PushChannel {

    private const val PREFS = "chats_account"
    private const val KEY_SSE_URL = "push_sse_url"
    private const val KEY_NOTIFY_URL = "push_notify_url"
    private const val KEY_PUSHKEY = "push_key"
    private const val KEY_LAST_MSG_ID = "push_last_msg_id"
    private const val APP_ID = "com.lightphone.chats"

    private const val TAG = "PushChannel"

    /** Reconnect backoff for a dropped SSE stream (NAT, radio, gateway down). */
    private const val RECONNECT_BASE_MS = 5_000L
    private const val RECONNECT_MAX_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    /** The in-flight SSE call; cancelled to interrupt a blocked read on stop. */
    @Volatile
    private var currentCall: Call? = null

    /** True while an SSE stream is actually open — i.e. push is delivering right now. */
    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var pushkey: String? = null

    /** Last ntfy message id seen (resume point for `?since=`, see [resumeUrl]). */
    @Volatile
    private var lastMessageId: String? = null

    /** Application context for persisting the resume point (set in [start]). */
    @Volatile
    private var appContext: Context? = null

    /** The client the current channel was started for (re-login restarts it). */
    @Volatile
    private var startedFor: MatrixClient? = null

    // ---- lifecycle -----------------------------------------------------

    /**
     * Registers the pusher (idempotent) and holds the SSE subscription. Safe
     * to call repeatedly; synchronized because init runs from both the
     * Application and the dev MainActivity and two concurrent starts would
     * both pass the null-job guard and open two connections. With no
     * configured URLs a private ntfy.sh channel is auto-provisioned (one
     * random topic derives pushkey + SSE + notify URLs) — push works with
     * zero setup after login; the `--es pushsse/pushnotify/pushkey` extras
     * override for a self-hosted gateway instead.
     */
    @Synchronized
    fun start(context: Context, c: MatrixClient) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sseUrl = prefs.getString(KEY_SSE_URL, null)
        var notifyUrl = prefs.getString(KEY_NOTIFY_URL, null)
        var key = prefs.getString(KEY_PUSHKEY, null)
        if (sseUrl == null || notifyUrl == null || key == null) {
            // Auto-provision an ntfy.sh channel (2026-08-17): one random
            // topic derives the pushkey (ntfy routes by it), the SSE stream
            // the phone holds, and the notify URL (ntfy's Matrix gateway
            // serves the required path). The topic is a per-install bearer
            // token — unguessable, so the channel is private.
            val topic = "chats-" + java.util.UUID.randomUUID().toString().replace("-", "")
            key = "https://ntfy.sh/$topic"
            sseUrl = "https://ntfy.sh/$topic/json"
            notifyUrl = "https://ntfy.sh/_matrix/push/v1/notify"
            prefs.edit()
                .putString(KEY_SSE_URL, sseUrl)
                .putString(KEY_NOTIFY_URL, notifyUrl)
                .putString(KEY_PUSHKEY, key)
                .apply()
            Log.i(TAG, "auto-provisioned ntfy channel: $sseUrl")
        }
        if (job?.isActive == true && startedFor === c) return
        stop() // re-login under the same topic: re-register for the new session
        startedFor = c
        lastMessageId = prefs.getString(KEY_LAST_MSG_ID, null)
        appContext = context.applicationContext
        pushkey = key
        job = scope.launch {
            register(c, notifyUrl)
            connect(sseUrl)
        }
    }

    /** Stops the subscription and drops the socket (logout, sync pause, session expiry). */
    fun stop() {
        currentCall?.cancel()
        currentCall = null
        job?.cancel()
        job = null
    }

    /** Best-effort removal of the pusher from the account (logout). */
    suspend fun unregister(context: Context, c: MatrixClient) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_PUSHKEY, null) ?: return
        runCatching {
            c.api.push.setPushers(SetPushers.Request.Remove(APP_ID, key)).getOrThrow()
        }
            .onSuccess { Log.i(TAG, "pusher removed from account") }
            .onFailure { Log.w(TAG, "pusher removal failed (best-effort): ${it.message}") }
    }

    /**
     * Dev/cleanup hook (`--es pushclear 1`): stops the channel, removes the
     * pusher from the account, and forgets the config — leaves no dead
     * pusher behind after a test with a transient tunnel URL.
     */
    suspend fun clear(context: Context, c: MatrixClient) {
        stop()
        unregister(context, c)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SSE_URL).remove(KEY_NOTIFY_URL).remove(KEY_PUSHKEY)
            .remove(KEY_LAST_MSG_ID).apply()
        lastMessageId = null
        Log.i(TAG, "push config cleared")
    }

    // ---- internals -----------------------------------------------------

    /**
     * Registers/refreshes the HTTP pusher on the homeserver. Idempotent
     * (app_id + pushkey identify the pusher; re-registering replaces it), so
     * calling it on every start keeps a changed URL in sync.
     */
    private suspend fun register(c: MatrixClient, notifyUrl: String) {
        val data = PusherData(
            format = "event_id_only", // no content/keys on the wire — wake-up only
            url = notifyUrl,
            customFields = buildJsonObject {},
        )
        val set = SetPushers.Request.Set(
            appId = APP_ID,
            pushkey = pushkey!!,
            kind = "http",
            appDisplayName = "Chats",
            deviceDisplayName = "LP3",
            lang = "en",
            data = data,
            append = false,
            profileTag = "",
        )
        runCatching { c.api.push.setPushers(set).getOrThrow() }
            .onSuccess { Log.i(TAG, "pusher registered -> $notifyUrl") }
            .onFailure { Log.w(TAG, "pusher registration failed (retried next start): ${it.message}") }
    }

    /**
     * The SSE subscription loop. Reads `data:` lines off a streaming OkHttp
     * response; anything else (heartbeats, reconnects) is ignored. On drop,
     * reconnect with backoff — delivery is async on the homeserver side
     * (retries with backoff), so a gap here is not data loss.
     */
    private suspend fun CoroutineScope.connect(sseUrl: String) {
        val client = OkHttpClient.Builder()
            // Bounded read so a half-open socket (broken tunnel, dead NAT)
            // is detected instead of blocking forever: the dev gateway
            // heartbeats every 15s and ntfy's JSON stream keeps a ~45s
            // keepalive, so 90s of silence = dead. Forces a reconnect.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        var delayMs = RECONNECT_BASE_MS
        while (isActive) {
            val url = resumeUrl(sseUrl)
            val call = client.newCall(Request.Builder().url(url).build())
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("SSE HTTP ${resp.code}")
                    Log.i(TAG, "SSE connected: $url")
                    delayMs = RECONNECT_BASE_MS
                    val source = resp.body?.source() ?: throw IOException("SSE empty body")
                    while (isActive) {
                        val line = source.readUtf8Line() ?: break // EOF = server closed
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue // keepalive/heartbeat
                        // Both wire formats carry one event per line: the dev
                        // gateway's SSE frames ("data: {…}") and ntfy's raw
                        // JSON stream ("{…}").
                        val payload =
                            if (trimmed.startsWith("data: ")) trimmed.removePrefix("data: ").trim()
                            else trimmed
                        if (payload.startsWith("{")) onNotification(payload)
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "SSE stream dropped: ${e.message}")
            }
            currentCall = null
            if (!isActive) break
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    /**
     * ntfy's JSON stream resumes from the last seen message (`?since=<id>`,
     * or `all` on the first ever connect) so a reconnect replays pushes
     * published during the gap — the canonical ntfy client does the same
     * (docs/subscribe/api.md §since; the server caches ~12h precisely for
     * network interruptions). The dev gateway's `/events` stream has no
     * cache and no since support, so only ntfy URLs (ending in `/json`) get
     * the parameter.
     */
    private fun resumeUrl(sseUrl: String): String {
        if (!sseUrl.endsWith("/json")) return sseUrl
        val since = lastMessageId ?: "all"
        return if (sseUrl.contains("?")) "$sseUrl&since=$since" else "$sseUrl?since=$since"
    }

    /**
     * A push notification arrived over SSE. ntfy wraps the homeserver POST
     * in its own envelope (`data.message` = the JSON body as a string); the
     * dev gateway relays the notification verbatim. Either shape unwraps to
     * the Matrix notification. Payloads with room/event ids are messages —
     * always wake one sync. Counts-only payloads (Beeper's read-receipt /
     * unread-count updates) still wake, but [MatrixRepository.onPushDelivered]
     * rate-limits them — a group chat's read actions must not each run a full
     * ~30-50 s syncOnce (battery 2026-08-17 audit).
     */
    private suspend fun onNotification(json: String) {
        val outer = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject }
            .getOrNull() ?: run {
                Log.w(TAG, "push payload not JSON: ${json.take(120)}")
                return
            }
        // Remember the last ntfy message id (event=="message") so a reconnect
        // resumes the stream from here instead of missing the gap.
        if (outer["event"]?.jsonPrimitive?.contentOrNull == "message") {
            outer["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                if (id != lastMessageId) {
                    lastMessageId = id
                    appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_LAST_MSG_ID, id)?.apply()
                }
            }
        }
        // ntfy stream envelope events that carry no notification
        // (open / keepalive) must not wake a sync.
        if (outer["event"]?.jsonPrimitive?.contentOrNull in setOf("open", "keepalive")) return
        val notifJson = outer["notification"]?.toString()
            ?: outer["message"]?.jsonPrimitive?.contentOrNull
            ?: json // the dev gateway relays the notification object verbatim
        val notif = runCatching { Json.parseToJsonElement(notifJson).jsonObject }.getOrNull()
            ?: run { Log.w(TAG, "push payload without notification object: ${json.take(120)}"); return }
        // ntfy's "message" carries the matrix body, which wraps the
        // notification again under "notification" — unwrap for the ids.
        val n = notif["notification"]?.jsonObject ?: notif
        val eventId = n["event_id"]?.jsonPrimitive?.contentOrNull
        val roomId = n["room_id"]?.jsonPrimitive?.contentOrNull
        if (eventId != null || roomId != null) {
            Log.i(TAG, "push received: room=$roomId event=$eventId -> waking one sync")
        } else {
            // Beeper counts-only payload (no event/room ids) — still a wake.
            Log.i(TAG, "push received (counts-style, no ids) -> waking one sync")
        }
        MatrixRepository.onPushDelivered(countsOnly = eventId == null && roomId == null)
    }
}
