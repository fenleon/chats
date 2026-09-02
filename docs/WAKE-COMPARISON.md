# Message-wake comparison — molly-light vs Beeper vs Chats

How three LP3-relevant messengers receive and process messages when the app is
foregrounded, backgrounded, or closed, and what Chats can borrow. Written
2026-09-01.

Sources:

- **molly-light** (LP3 reskin of Molly/Signal): source clone at
  `/tmp/molly-light` (single commit `7cf99c8`). The fork is a pure UI reskin —
  the entire messaging stack is untouched upstream Molly/Signal.
- **Beeper 4.55.1** (`com.beeper.android`, versionCode 1744154584): jadx
  decompile at `reference/beeper/jadx/` (dev-only, gitignored), summary in
  `reference/beeper/notes/NOTES-ARCHITECTURE.md`. R8-minified; class names
  below are the decompiled ones.
- **Chats**: current code in this repo (`server/src/main/kotlin/...`).

## The three models at a glance

| | molly-light | Beeper | Chats (current) |
|---|---|---|---|
| Realtime channel | in-process websocket to Signal | in-process Go SDK connection | Trixnity long-poll in-process |
| Closed-app delivery | persistent FGS + websocket (LP3 default, no FCM) OR MollySocket→UnifiedPush wake | FCM push → WorkManager → SDK catch-up sync | ntfy SSE push → one `syncOnce`; FGS holds process |
| Process protection | FGS (`remoteMessaging`) + 2× partial wakelock | FGS/JobService/worker ladder, **no wakelocks** | FGS (`START_STICKY`), no wakelocks |
| Failure handling | drain timeout → JobScheduler retry + "may have messages" notif | retry worker (max 3), network-constrained, then give up | log only; 5/15-min fallback rounds |
| Reboot recovery | BootReceiver → MessageFetchJob + re-arm alarm | none (FCM redelivers) | **none** |

## molly-light wake sequence

Decision function `IncomingMessageObserver.isConnectionNecessary()`
(`messages/IncomingMessageObserver.kt:214`):

```
registered && !unauthorized && (foreground || idle < 2min || !pushAvailable || forceWebsocket) && hasNetwork
```

- **Foreground**: keepalive token held, 30 s heartbeat, `MessageRetrievalThread`
  reads envelope batches (30/batch, 1 min timeout), decrypts, applies in one DB
  transaction, acks each envelope, enqueues `PushProcessMessageJob`s.
- **Background with push**: socket lingers 2 min (10 s censored), then hands off.
- **Background without push** (LP3 default — no GMS → no FCM token):
  `pushAvailable == false` → socket stays up forever + `ForegroundService`
  (`remoteMessaging`, "Ready to receive messages", PRIORITY_MIN) keeps the
  process alive. Retry timer switches to alarm-based (`AlarmSleepTimer`,
  doze-safe), exponential backoff from 30 s.
- **Push wake** (UnifiedPush): receiver checks `KeyCachingService.isLocked()` —
  if locked, skips the fetch entirely (keys unavailable, only an urgent-message
  info notif). Else `FcmFetchManager` → `WebSocketDrainer`:
  1. start `FcmFetchForegroundService` ("Checking for messages" + wakelock),
  2. register keepalive token on the websocket,
  3. wait until `decryptionDrained` (read the queue to `hasMore == false`),
  4. wait until every job queue that received work drains (MarkerJob per queue,
     30 s timeout) — the process is only allowed to sleep once messages are
     **persisted and processed**, not when the network read finishes,
  5. release everything in `finally`.
- **Failure**: drain timeout → "May have messages" notification + reschedule on
  JobScheduler (`FcmJobService`) or JobManager `MessageFetchJob`.
- **Routine**: repeating `ELAPSED_REALTIME_WAKEUP` alarm, default 6 h
  (remote-config), skips when foregrounded, runs `MessageFetchJob` with a
  dataSync FGS.
- **Boot**: `BootReceiver` → `MessageFetchJob` + re-arms the 6 h alarm.

## Beeper wake sequence

The Matrix stack runs in Go (`libgojni.so`); the Java shell only orchestrates.

- **Foreground**: the Go SDK holds a persistent sync connection in-process. Sync
  work runs under `SdkLifecycleUtilKt.runForegroundWithSdkLifecycle`, which
  picks a keep-alive from `SdkKeepAliveStrategy` (`services/SdkKeepAliveStrategy.java`):
  `TRANSFER_SERVICE` (JobService) | `FOREGROUND_SERVICE` | `KEEP_ALIVE_WORKER`.
  A watchdog (`sdk/d0.java`) flags workers dead > 10 s / stale > 600 s.
- **Background/closed — push-driven only.** No periodic workers, no wakelocks,
  boot receiver is a no-op. Every wake is one-shot:
  1. FCM data push → `BooperFirebaseMessagingService` → `PushHandler.handle`
     (`push/c.java`).
  2. **Dedupe**: per-bridge WorkManager unique-name query — pending
     not-yet-started `NotificationWorker`s for that bridge are cancelled, then
     one new worker is enqueued. Push bursts collapse to one worker.
  3. `NotificationWorker` (`core/work/NotificationWorker.java`): a
     `CoroutineWorker` with a silent foreground-service notification for
     modern-Android expedited runs; max 3 attempts; `requireNetwork` flag in
     the payload.
  4. `handleSyncForPushWithSdkCatchup` (`core/work/h.java:71-118`): read delay
     pref → wait → hand the event id to the Go SDK → SDK does catch-up sync →
     **verify caught up** against sync timestamps (`SDK finished handling push
     … up-to-date: …`) → if not caught up, throw `NotCaughtUpYetException`.
     Special case: "bridge is already running" (SDK already syncing — i.e. app
     active) → skip.
  5. `NotCaughtUpYetException` → reschedule the same worker **with a network
     constraint** (bounded by the 3-attempt cap). Success/failure + delay
     analytics.
- **Keep-alive services**: `SyncForegroundService` (dataSync FGS, silent
  notification id 100000004, `START_REDELIVER_INTENT`, AtomicReference dedupe)
  and `SyncTransferService` (JobService) — both route into the same
  keep-alive orchestrator, used to hold the SDK alive while sync work runs.
- **Scheduled sends are server-side**: a `content_pushers` API call; the local
  `MessageAlarmReceiver` only fires that call. Nothing local about it.

Key design point: **the phone holds no connection while closed.** WorkManager +
FGS keep the *wake* (a short-lived sync burst) alive, and the catch-up check
turns a lost race ("push arrived but sync hasn't reached that event yet") into
a rescheduled retry instead of a dropped notification.

## Chats (current) wake sequence

- **Foreground & background are the same**: `ChatSyncService`
  (`server/.../ChatSyncService.kt`) is a `START_STICKY` FGS holding a Trixnity
  long-poll 24/7 while sync is enabled. Screen-state cadence
  (`MatrixRepository.applySyncModeForScreenState`): screen on → active
  long-poll; screen off → `syncOnce` rounds every 5 min (15 min lazy when the
  SSE push channel is connected). Watchdog restarts a long-poll stuck
  > 120 s (screen-on only).
- **Push wake** (`MatrixRepository.onPushDelivered`, :918): only acts in slow
  (screen-off) mode. Counts-only pushes collapse to one sync per 5 min; real
  message pushes debounce on a 1 s window (last wins), then one `timedSyncOnce`
  and the fallback rounds restart if still dark. Push arrives via the ntfy SSE
  channel (`PushChannel.kt`) — the FGS keeps the process (and the SSE socket)
  alive, so wakes never cross process death.
- **Push-wake caught-up verification** (2026-09-02): the wake's syncOnce is
  verified against the Room store — the pushed `event_id` must be present
  after the sync (and a wake whose event a round already delivered skips the
  sync entirely). Not caught up → up to 2 retries with backoff, then a
  low-key "Checking for messages failed — will retry" notification, cleared on
  the next successful sync or when the app comes to the foreground.
- **Failure**: push-wake sync failure is logged and the 5/15-min rounds
  continue. No WorkManager, no reschedule-with-constraints; the bounded
  in-process retry (the wake is already in-process) covers the lost-race case.
- **Reboot**: `BootReceiver` restarts `ChatSyncService` on `BOOT_COMPLETED`
  (after first unlock); the existing screen-state-aware init re-engages the
  slow-sync rounds. (`START_STICKY` covers process death, not reboots.)
- No WorkManager; no wakelocks.

## Cross-check — what each side does well

| Concern | molly-light | Beeper | Chats |
|---|---|---|---|
| Never miss a message | FGS + always-on socket (costly) | caught-up verify + constrained retry | 5-min fallback rounds + caught-up verify + bounded retry |
| Don't do useless work | locked → skip fetch; alarm skips when foregrounded | "bridge already running" → skip; delay pref | push wakes skipped when active mode or event already stored; debounce/collapse |
| Survive process death | FGS (remoteMessaging) + START_STICKY-ish | WorkManager (owned by the system) | FGS `START_STICKY` + in-FGS timers |
| Survive reboot | BootReceiver + re-armed alarm | none (FCM redelivers) | BootReceiver → ChatSyncService |
| Tell the user something's pending | "may have messages" notification | (FCM path) | "checking for messages failed" notification |
| Battery while closed | worst (unthrottled on LP3) | best (push-only) | middle (long-poll + FGS, screen-off gated) |

## What Chats can implement (ranked)

1. **Boot receiver — DONE (2026-09-02).** `BOOT_COMPLETED` receiver
   (`BootReceiver.kt`) → start `ChatSyncService`; the existing screen-aware
   init re-engages the slow-sync rounds. A rebooted LP3 no longer stays silent
   until opened.
2. **Caught-up verification + bounded retry on push wake — DONE (2026-09-02).**
   After `timedSyncOnce`, the wake verifies the pushed event landed in the
   Room store; not caught up → up to 2 retries with backoff, then the
   fallback rounds take over. In-process (the FGS holds the process), so no
   WorkManager.
3. **"Messages pending" user signal — DONE (2026-09-02).** A push-wake sync
   that exhausts its retries posts a low-key "Checking for messages failed —
   will retry" notification (molly's "may have messages" equivalent), cleared
   on the next successful sync or foreground.
4. **Skip-when-useless — DONE (2026-09-02).** `onPushDelivered` already
   collapses when active; a wake whose event a round already delivered now
   skips the sync entirely (store pre-check, follows from #2).
5. **WorkManager for the push-wake sync (defer; LP3-specific reasoning).**
   Beeper's model needs WorkManager because the process may be dead at push
   time. On the LP3 the FGS keeps the process alive, so WorkManager's
   guaranteed-execution value is minimal. Revisit only if the always-on FGS is
   ever dropped in favor of push-only wakes.

Deliberately **not** borrowed: molly's persistent-connection FGS as a *default*
(chats already runs one, but it is screen-off gated), Beeper's FCM dependence
(no GMS on LP3), Beeper's server-side scheduled sends (proprietary
`content_pushers` API; standard Matrix has no equivalent), wakelocks (molly's
double-wakelock dance is an artifact of websocket drains; the LP3 ships with
doze disabled, so they buy little and complicate shutdown).

## Sources (dev-only paths)

- molly-light: `/tmp/molly-light` — `messages/IncomingMessageObserver.kt`,
  `messages/WebSocketDrainer.kt`, `gcm/FcmFetchManager.kt`, `gcm/FcmFetchForegroundService.kt`,
  `messageprocessingalarm/RoutineMessageFetchReceiver.java`, `jobs/MessageFetchJob.java`,
  `service/BootReceiver.java`, `im/molly/unifiedpush/`.
- Beeper: `reference/beeper/jadx/sources/com/beeper/chat/booper/` —
  `push/c.java`, `push/BooperFirebaseMessagingService.java`,
  `core/work/NotificationWorker.java`, `core/work/h.java` (catch-up),
  `core/work/NotCaughtUpYetException.java`, `services/SyncForegroundService.java`,
  `services/SyncTransferService.java`, `services/SdkKeepAliveStrategy.java`,
  `core/KeepAliveWorker.java`, `sdk/d0.java` (watchdog).
- Chats: `server/src/main/kotlin/com/lightphone/chats/server/` —
  `ChatSyncService.kt`, `MatrixRepository.kt` (`onPushDelivered` :918,
  `runPushWake` :975, `isEventStored` :947), `PushChannel.kt`,
  `BootReceiver.kt`, `ChatNotifier.kt` (`notifySyncPending`).
