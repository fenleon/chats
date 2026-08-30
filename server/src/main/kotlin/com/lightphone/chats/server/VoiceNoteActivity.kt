package com.lightphone.chats.server

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thelightphone.sdk.rememberHapticsEnabled
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "record a voice note" screen (Phase 14). The tool runtime forbids
 * startActivity and the companion can't launch activities from the background,
 * so the tool calls `StartVoiceNoteSend` (which records the room) and then
 * starts this activity via `SimpleLightScreen.startServerActivity` — the same
 * pattern as [PhotoSendActivity]. Recording starts as soon as the panel opens
 * (feedback 2026-08-30: no idle "tap to record" step) — Opus in an ogg
 * container (the MSC3245 canonical voice-message format, ~2-3× smaller than
 * the old AAC/m4a at speech bitrates); tap to stop; the recording is
 * uploaded to Matrix as an m.audio message and sent in the recorded room.
 * RECORD_AUDIO is requested at
 * runtime (the manifest declares it; the launcher activity grants it on
 * install, but the companion's own install may not carry the grant).
 */
class VoiceNoteActivity : ComponentActivity() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var previewPlayer: android.media.MediaPlayer? = null
    /** Audio focus held while recording — pauses background playback (music,
     *  podcasts) for the take (feedback 2026-08-20). */
    private var recordFocusRequest: android.media.AudioFocusRequest? = null
    /** Audio focus held while the pre-send preview plays — the preview must
     *  pause background audio the same way the recording does (feedback
     *  2026-08-21: the recorder got transient focus but the preview player
     *  didn't). */
    private var previewFocusRequest: android.media.AudioFocusRequest? = null

    // Activity-level state so the recording functions can flip the UI (a
    // composable-local remember can't be reached from startRecording/stopAndSend).
    private var recording by mutableStateOf(false)
    private var previewing by mutableStateOf(false)
    private var playing by mutableStateOf(false)
    private var sending by mutableStateOf(false)
    /** Wall-clock start of the current take (elapsedRealtime) — MediaRecorder
     *  has no position query, so the m:ss ticker derives it from this. */
    private var recordingStartedAt: Long? = null
    /** Elapsed seconds of the current take, ticked once per second. */
    private var elapsedSeconds by mutableStateOf(0)
    /**
     * Recorded length (seconds) — the timer slot keeps showing it after the
     * take stops, and the preview ticks through it while playing (feedback
     * 2026-08-27: "show the final length where it was counting, count through
     * it on playback").
     */
    private var finalDurationSeconds by mutableStateOf(0)
    /** RECORD_AUDIO was denied — show why, with a retry (2026-08-19 feedback
     *  round: "the phone doesn't ask for the permission" — a denial must not
     *  silently drop the screen). */
    private var micDenied by mutableStateOf(false)
    /** The last send failed — keep the take + SEND so the user can retry
     *  instead of a silent drop (2026-08-19 feedback round). */
    private var sendFailed by mutableStateOf(false)
    /**
     * In-app volume panel state (null = hidden) — the shared LightOS replica
     * (feedback 2026-08-30): while a take exists to preview, the volume rocker
     * shows this panel and adjusts the media stream, instead of LightOS's
     * ringer-only panel.
     */
    private var volumePanel by mutableStateOf<VolumePanelState?>(null)

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                micDenied = false
                startRecording()
            } else {
                micDenied = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = takePendingRoomId()
        android.util.Log.d(TAG, "onCreate: pendingRoom=$roomId")
        if (roomId == null) {
            finish()
            return
        }
        // Start the take BEFORE the first frame renders, so the panel opens
        // already in the recording state — the idle mic icon must not flash
        // (feedback 2026-08-31). A missing RECORD_AUDIO grant asks first; the
        // launcher callback starts the recording (or shows the mic-denied
        // retry) once the user answers.
        ensureMicThenRecord()
        setContent {
            val themeColors by LightThemeController.colors.collectAsState()
            // Haptics on the recording screen follow the real LightOS setting,
            // like the main tool: LightActivity provides LocalHapticsEnabled
            // from GetUserPreferences, but this plain activity never did —
            // lightClickable silently skipped the vibration (feedback
            // 2026-08-22: "the recording panel does not have haptics").
            val hapticsEnabled by rememberHapticsEnabled().collectAsState()

            CompositionLocalProvider(LocalHapticsEnabled provides hapticsEnabled) {
                LightTheme(colors = themeColors) {
                // Height of the timer slot under the icon — also reserved
                // above the icon, so the icon (not the icon+timer block) sits
                // on the panel's vertical center with the timer hanging below
                // it (feedback 2026-08-30).
                val timerSlotHeight = 3f.gridUnitsAsDp()
                // m:ss ticker while recording (MediaRecorder has no position
                // query — the elapsed time comes from the wall clock).
                LaunchedEffect(recording) {
                    while (recording) {
                        elapsedSeconds = recordingStartedAt?.let {
                            ((SystemClock.elapsedRealtime() - it) / 1000).toInt()
                        } ?: 0
                        delay(1000)
                    }
                }
                // While the pre-send preview plays, the same slot counts
                // through the take (feedback 2026-08-27).
                LaunchedEffect(playing) {
                    while (playing) {
                        elapsedSeconds = (previewPlayer?.currentPosition?.let { (it / 1000).toInt() }
                            ?: finalDurationSeconds).coerceAtMost(finalDurationSeconds)
                        delay(1000)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    // No top bar / back button — the bottom bar's X dismisses
                    // (feedback 2026-08-27).
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // The timer slot's height is reserved above the
                            // icon too, so the ICON sits on the panel's
                            // vertical center — the timer hangs below it
                            // (feedback 2026-08-30: "the icons should be
                            // lower so that they are centred").
                            Spacer(modifier = Modifier.height(timerSlotHeight))
                            // Record → tap to stop → play the take back → SEND
                            // in the bottom bar (RETRY, X, SEND — feedback
                            // 2026-08-27). Icons carry the states (feedback
                            // 2026-08-30: no text labels for the control flow —
                            // mic to open, stop while recording, play when
                            // stopped); only the permission-denied and sending
                            // states keep words. A mic denial shows a message +
                            // ALLOW instead of a silent drop.
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .lightClickable(
                                        enabled = !sending,
                                        onClick = { onCenterTap() },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightIcon(
                                    icon = when {
                                        recording -> LightIcons.STOP
                                        previewing && playing -> LightIcons.PAUSE
                                        previewing -> LightIcons.PLAY
                                        // micDenied and idle both show the mic
                                        // (the message differs below).
                                        else -> LightIcons.MICROPHONE
                                    },
                                    size = 3f,
                                    contentDescription = when {
                                        micDenied -> "Allow microphone"
                                        recording -> "Stop recording"
                                        previewing -> if (playing) "Pause preview" else "Play preview"
                                        else -> "Record voice note"
                                    },
                                )
                            }
                            // Only the states that NEED words show any: the
                            // mic denial and the send progress. The control
                            // states are purely icon + timer (feedback
                            // 2026-08-30).
                            when {
                                micDenied -> LightText(
                                    text = "Microphone permission is needed to record.",
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
                                )
                                sending -> LightText(
                                    text = "Sending…",
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
                                )
                            }
                            // Fixed-height slot: the timer appears/disappears
                            // without shifting the icon above it (feedback
                            // 2026-08-27: "the icon and text move up when you
                            // press record"). 3 grid units — tall enough for
                            // the Fine line; the old 2-unit slot clipped the
                            // text vertically (feedback 2026-08-30).
                            Box(
                                modifier = Modifier.height(timerSlotHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                val timerText = when {
                                    recording -> DateUtils.formatElapsedTime(elapsedSeconds.toLong())
                                    previewing && playing -> DateUtils.formatElapsedTime(elapsedSeconds.toLong())
                                    previewing -> DateUtils.formatElapsedTime(finalDurationSeconds.toLong())
                                    else -> null
                                }
                                if (timerText != null) {
                                    LightText(
                                        text = timerText,
                                        // One step up from the old Superfine —
                                        // the counting time reads bigger under
                                        // the icon (feedback 2026-08-30).
                                        variant = LightTextVariant.Fine,
                                        // Solid white like the thread's
                                        // timestamps (feedback 2026-08-27).
                                    )
                                }
                            }
                            if (sendFailed) {
                                LightText(
                                    text = "Couldn't send — tap SEND to retry.",
                                    variant = LightTextVariant.Superfine,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }
                    LightBottomBar(
                        modifier = Modifier.navigationBarsPadding(),
                        items = listOf(
                            // RETRY: delete the take and start a fresh
                            // recording (feedback 2026-08-30: no tap-to-record
                            // step).
                            if ((previewing || sendFailed) && !sending) {
                                LightBarButton.Text(
                                    text = "RETRY",
                                    onClick = { retryRecording() },
                                )
                            } else {
                                null
                            },
                            // X in the middle dismisses the panel — no top-bar
                            // back (feedback 2026-08-27). finish() only, so a
                            // recorded take never flashes the idle panel on the
                            // way out (feedback 2026-08-27); onStop cleans up.
                            LightBarButton.LightIcon(
                                icon = LightIcons.CLOSE,
                                onClick = { finish() },
                                contentDescription = "Discard voice note",
                            ),
                            if ((previewing || sendFailed) && !sending) {
                                LightBarButton.Text(
                                    text = "SEND",
                                    onClick = { sendCurrent(roomId) },
                                )
                            } else {
                                null
                            },
                        ),
                    )
                }
                // The in-app volume panel replica (feedback 2026-08-30): the
                // volume rocker shows it over the panel while a take exists.
                VolumePanelOverlay(
                    state = volumePanel,
                    onDismiss = { volumePanel = null },
                )
                }
            }
        }
    }

    /**
     * Relay hardware keys to LightOS so the recording panel behaves like the
     * main tool: volume rocker → LightOS's volume panel, scroll wheel →
     * brightness, camera button etc. The tool's screens get this via the SDK
     * server's onDeviceKeyEvent (LightOS forwards to the tool), but this plain
     * activity sits outside that path (feedback 2026-08-22: "the recording
     * panel does not have volume / brightness").
     *
     * While a take exists ([previewing]) the volume rocker is consumed here
     * instead: it adjusts the media stream (the take plays over it) and shows
     * the in-app volume panel replica — the native LightOS panel is ringer-only
     * for third-party tools (feedback 2026-08-30).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeKey && previewing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                adjustMediaVolume(event.keyCode)
            }
            // Both the DOWN and its UP are consumed — a stray UP relayed to
            // LightOS would be a half-gesture.
            return true
        }
        PlatformRelay.sendDeviceKeyEvent(
            LightServiceMethod.DeviceKeyEvent.Request(
                keyCode = event.keyCode,
                repeatCount = event.repeatCount,
                action = event.action,
                characters = event.characters?.toString(),
                unicodeChar = event.unicodeChar,
                componentToRelaunch = null,
            ),
        )
        return super.dispatchKeyEvent(event)
    }

    /** One media-stream step per press + the in-app volume panel mirror. */
    private fun adjustMediaVolume(keyCode: Int) {
        val audio = getSystemService(android.content.Context.AUDIO_SERVICE)
            as android.media.AudioManager
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> audio.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_RAISE,
                0,
            )
            KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                0,
            )
        }
        volumePanel = VolumePanelState.Media(
            audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
        )
    }

    private fun ensureMicThenRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
    }

    /** Shared center-tap handler (the icon AND the label — feedback 2026-08-27). */
    private fun onCenterTap() {
        when {
            micDenied -> {
                micDenied = false
                ensureMicThenRecord()
            }
            recording -> stopRecording()
            previewing -> togglePreviewPlayback()
            else -> ensureMicThenRecord()
        }
    }

    /**
     * RETRY (feedback 2026-08-27): deletes the take and starts a fresh
     * recording — there is no idle "tap to record" step anymore (feedback
     * 2026-08-30), so the new take begins immediately.
     */
    private fun retryRecording() {
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        abandonPreviewFocus()
        outputFile?.delete()
        outputFile = null
        previewing = false
        playing = false
        sendFailed = false
        finalDurationSeconds = 0
        ensureMicThenRecord()
    }

    private fun startRecording() {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        val r = runCatching {
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // Opus in an ogg container (MSC3245 voice messages). Opus
                // native sample rates are 8/12/16/24/48 kHz — the old 44.1 kHz
                // is not valid; 48 kHz is the default. 32 kbps is transparent
                // for speech and ~2× smaller than the old 64 kbps AAC.
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(48_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse { e ->
            android.util.Log.w(TAG, "startRecording failed", e)
            finish()
            return
        }
        recorder = r
        outputFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        elapsedSeconds = 0
        // Pause whatever is playing in the background (music, a podcast, a
        // voice note) for the take — transient focus, released on stop
        // (feedback 2026-08-20: "background audio should pause").
        recordFocus()
        recording = true
    }

    /** Transient media focus while recording — background audio pauses. */
    private fun recordFocus() {
        if (recordFocusRequest != null) return
        val request = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        runCatching {
            (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                .requestAudioFocus(request)
        }
        recordFocusRequest = request
    }

    private fun abandonRecordFocus() {
        recordFocusRequest?.let { request ->
            runCatching {
                (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .abandonAudioFocusRequest(request)
            }
            recordFocusRequest = null
        }
    }

    /** Transient media focus while the pre-send preview plays (same pattern
     *  as [recordFocus] — feedback 2026-08-21). */
    private fun previewFocus() {
        if (previewFocusRequest != null) return
        val request = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        runCatching {
            (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                .requestAudioFocus(request)
        }
        previewFocusRequest = request
    }

    private fun abandonPreviewFocus() {
        previewFocusRequest?.let { request ->
            runCatching {
                (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .abandonAudioFocusRequest(request)
            }
            previewFocusRequest = null
        }
    }

    /** Stops + releases the recorder (idempotent). */
    private fun releaseRecorder() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun stopRecording() {
        releaseRecorder()
        recordingStartedAt = null
        // Keep the recorded length in the timer slot (feedback 2026-08-27:
        // "show you the final length where it was counting").
        finalDurationSeconds = elapsedSeconds
        recording = false
        abandonRecordFocus()
        // Only enter the preview state if there's a take to preview.
        previewing = outputFile != null
    }

    /** Toggles playback of the recorded take before it's sent. */
    private fun togglePreviewPlayback() {
        val player = previewPlayer
        when {
            player != null && player.isPlaying -> {
                player.pause()
                playing = false
                abandonPreviewFocus()
            }
            player != null -> {
                player.seekTo(0)
                player.start()
                playing = true
                previewFocus()
            }
            else -> {
                val file = outputFile ?: return
                runCatching {
                    android.media.MediaPlayer().apply {
                        // Media/speech classification so the hardware volume
                        // buttons control the preview (feedback 2026-08-14).
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        setOnCompletionListener { playing = false; abandonPreviewFocus() }
                        setOnErrorListener { _, _, _ -> playing = false; abandonPreviewFocus(); true }
                        setDataSource(file.absolutePath)
                        prepare()
                        start()
                    }.also { previewPlayer = it }
                    playing = true
                    previewFocus()
                }
            }
        }
    }

    private fun sendCurrent(roomId: String) {
        val file = outputFile ?: run {
            finish()
            return
        }
        previewing = false
        sendFailed = false
        sending = true
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        abandonPreviewFocus()
        val bytes = file.length()
        if (bytes <= 0) {
            file.delete()
            sending = false
            finish()
            return
        }
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                runCatching { MatrixRepository.sendVoiceNote(roomId, file) }.getOrDefault(false)
            }
            if (sent) {
                android.util.Log.d(TAG, "VoiceNote: sent $bytes bytes to room $roomId")
                file.delete()
                finish()
            } else {
                // Keep the take + SEND so the failure is visible and retryable
                // (feedback 2026-08-19: a failed send vanished silently).
                android.util.Log.w(TAG, "VoiceNote: failed to send to room $roomId")
                outputFile = file
                sending = false
                previewing = true
                sendFailed = true
            }
        }
    }

    private fun discardRecording() {
        releaseRecorder()
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        abandonRecordFocus()
        abandonPreviewFocus()
        outputFile?.delete()
        outputFile = null
        recordingStartedAt = null
        elapsedSeconds = 0
        finalDurationSeconds = 0
        recording = false
        previewing = false
        playing = false
        sending = false
        sendFailed = false
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen mid-recording (home/back) discards the take.
        discardRecording()
    }

    companion object {
        private const val TAG = "VoiceNoteActivity"

        /** The room awaiting a voice note (one at a time — see [PhotoSendActivity]). */
        @Volatile
        private var pendingRoom: String? = null

        /** Records the room a voice-note send should land in (called by StartVoiceNoteSend). */
        fun register(roomId: String) {
            pendingRoom = roomId
        }

        private fun takePendingRoomId(): String? =
            pendingRoom.also { pendingRoom = null }
    }
}
