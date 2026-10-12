package com.lightphone.chats.screens

import com.lightphone.chats.ChatsMethods
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.text.format.DateUtils
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.PermissionRequestLauncher
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "record a voice note" screen — the scan-legal LightScreen port of dev's
 * VoiceNoteActivity (activities can't exist in the scanned tool module, so
 * recording lives in the ViewModel and the mic permission goes through the
 * SDK's server-routed launcher instead of ActivityResultContracts).
 *
 * Recording starts as soon as the panel opens — Opus in an ogg container (the
 * MSC3245 canonical voice-message format, ~2-3× smaller than AAC/m4a at
 * speech bitrates); tap to stop; the take plays back; SEND uploads it as an
 * m.audio message in [roomId]. A mic denial shows why, with a retry — never a
 * silent drop.
 */
class RecordVoiceNoteScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
) : LightScreen<Unit, RecordVoiceNoteViewModel>(sealedActivity) {

    override val viewModelClass: Class<RecordVoiceNoteViewModel>
        get() = RecordVoiceNoteViewModel::class.java

    override fun createViewModel(): RecordVoiceNoteViewModel =
        RecordVoiceNoteViewModel(roomId, lightContext)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val micLauncher = rememberPermissionRequestLauncher(
            "android.permission.RECORD_AUDIO",
        )
        LaunchedEffect(Unit) { viewModel.begin(micLauncher) }
        // m:ss ticker while recording (MediaRecorder has no position query —
        // the elapsed time comes from the wall clock) and while the pre-send
        // preview plays (feedback 2026-08-27). The VM state is Compose
        // MutableState — reading .value here subscribes the composition.
        val recording = viewModel.recording.value
        val playing = viewModel.playing.value
        val elapsedSeconds = viewModel.elapsedSeconds.value
        val finalDurationSeconds = viewModel.finalDurationSeconds.value
        val previewing = viewModel.previewing.value
        val sending = viewModel.sending.value
        val micDenied = viewModel.micDenied.value
        val sendFailed = viewModel.sendFailed.value
        LaunchedEffect(recording, playing) {
            while (recording || playing) {
                viewModel.tick()
                delay(1000)
            }
        }

        LightTheme(colors = themeColors) {
            // Height of the timer slot under the icon — also reserved above
            // it, so the ICON (not the icon+timer block) sits on the panel's
            // vertical center with the timer hanging below.
            val timerSlotHeight = 3f.gridUnitsAsDp()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(timerSlotHeight))
                        // Record → tap to stop → play the take back → SEND in
                        // the bottom bar (RETRY, X, SEND).
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .lightClickable(
                                    enabled = !sending,
                                    onClick = { viewModel.onCenterTap() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightIcon(
                                icon = when {
                                    recording -> LightIcons.STOP
                                    previewing && playing -> LightIcons.PAUSE
                                    previewing -> LightIcons.PLAY
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
                        // Only the states that NEED words show any: the mic
                        // denial and the send progress. Control states are
                        // purely icon + timer.
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
                        Box(
                            modifier = Modifier.height(timerSlotHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            val timerText = when {
                                recording -> DateUtils.formatElapsedTime(elapsedSeconds.toLong())
                                previewing && playing ->
                                    DateUtils.formatElapsedTime(elapsedSeconds.toLong())
                                previewing ->
                                    DateUtils.formatElapsedTime(finalDurationSeconds.toLong())
                                else -> null
                            }
                            if (timerText != null) {
                                LightText(
                                    text = timerText,
                                    variant = LightTextVariant.Fine,
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
                        if ((previewing || sendFailed) && !sending) {
                            LightBarButton.Text(
                                text = "RETRY",
                                onClick = { viewModel.retryRecording() },
                            )
                        } else {
                            null
                        },
                        // X in the middle dismisses — no top-bar back. A
                        // recorded take is discarded on hide (onScreenHide).
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = { goBack() },
                            contentDescription = "Discard voice note",
                        ),
                        if ((previewing || sendFailed) && !sending) {
                            LightBarButton.Text(
                                text = "SEND",
                                onClick = { viewModel.sendCurrent { goBack() } },
                            )
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }
}

class RecordVoiceNoteViewModel(
    private val roomId: String,
    private val lightContext: SealedLightContext,
) : LightViewModel<Unit>() {

    val recording = mutableStateOf(false)
    val previewing = mutableStateOf(false)
    val playing = mutableStateOf(false)
    val sending = mutableStateOf(false)
    val micDenied = mutableStateOf(false)
    val sendFailed = mutableStateOf(false)
    val elapsedSeconds = mutableStateOf(0)
    val finalDurationSeconds = mutableStateOf(0)

    private var recordingStartedAt: Long? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var previewPlayer: MediaPlayer? = null
    private var recordFocusRequest: AudioFocusRequest? = null
    private var previewFocusRequest: AudioFocusRequest? = null
    /** True between a fired permission launch and the screen's return. */
    private var awaitingMicPermission = false
    private var micLauncher: PermissionRequestLauncher? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!awaitingMicPermission) return
        awaitingMicPermission = false
        // Back from the permission panel — re-check through the server, then
        // start (or show the denial).
        checkMicThenStart()
    }

    /**
     * First composition: start the take immediately (the idle mic icon must
     * not flash) when the grant exists; otherwise fire the permission launch
     * (the re-check lands in [onScreenShow]). No launcher seam = broken SDK —
     * show the denial instead of a silent drop.
     */
    fun begin(launcher: PermissionRequestLauncher?) {
        micLauncher = launcher
        checkMicThenStart()
    }

    private fun checkMicThenStart() {
        viewModelScope.launch {
            val granted = checkPermission(PERMISSION).getOrNull()
                ?.permissionResult == LightServiceMethod.GetPermission.Result.Granted
            if (granted) {
                startRecording()
            } else if (micLauncher != null) {
                micLauncher?.launch()
                awaitingMicPermission = true
            } else {
                micDenied.value = true
            }
        }
    }

    /** Shared center-tap handler (the icon AND the label — feedback 2026-08-27). */
    fun onCenterTap() {
        when {
            micDenied.value -> {
                micDenied.value = false
                checkMicThenStart()
            }
            recording.value -> stopRecording()
            previewing.value -> togglePreviewPlayback()
            else -> checkMicThenStart()
        }
    }

    private fun startRecording() {
        val cacheDir = File(lightContext.filesDir, "cache").apply { mkdirs() }
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        val r = runCatching {
            // No-arg constructor (deprecated on API 31+): avoids needing a
            // Context — the SDK only exposes filesDir/dataStore to tools.
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // Opus in an ogg container (MSC3245 voice messages). Opus
                // native sample rates are 8/12/16/24/48 kHz; 48 kHz is the
                // default. 32 kbps is transparent for speech.
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
            micDenied.value = false
            sendFailed.value = true
            return
        }
        recorder = r
        outputFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        elapsedSeconds.value = 0
        // Pause whatever is playing in the background (music, a podcast, a
        // voice note) for the take — transient focus, released on stop.
        recordFocus()
        recording.value = true
    }

    /** Transient media focus while recording — background audio pauses. */
    private fun recordFocus() {
        if (recordFocusRequest != null) return
        recordFocusRequest = requestTransientSpeechFocus()
    }

    /** Builds + requests transient media focus with speech content (the
     *  shared shape of the recorder and pre-send-preview blocks). */
    private fun requestTransientSpeechFocus(): AudioFocusRequest {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        runCatching { lightContext.audioManager.requestAudioFocus(request) }
        return request
    }

    private fun abandonFocus(request: AudioFocusRequest?) {
        request ?: return
        runCatching { lightContext.audioManager.abandonAudioFocusRequest(request) }
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
        finalDurationSeconds.value = elapsedSeconds.value
        recording.value = false
        abandonFocus(recordFocusRequest)
        recordFocusRequest = null
        // Only enter the preview state if there's a take to preview.
        previewing.value = outputFile != null
    }

    /** Toggles playback of the recorded take before it's sent. */
    private fun togglePreviewPlayback() {
        val player = previewPlayer
        when {
            player != null && player.isPlaying -> {
                player.pause()
                playing.value = false
                abandonFocus(previewFocusRequest)
                previewFocusRequest = null
            }
            player != null -> {
                player.seekTo(0)
                player.start()
                playing.value = true
                previewFocus()
            }
            else -> {
                val file = outputFile ?: return
                runCatching {
                    MediaPlayer().apply {
                        // Media/speech classification so hardware volume
                        // buttons control the preview (feedback 2026-08-14).
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        setOnCompletionListener {
                            playing.value = false
                            abandonFocus(previewFocusRequest)
                            previewFocusRequest = null
                        }
                        setOnErrorListener { _, _, _ ->
                            playing.value = false
                            abandonFocus(previewFocusRequest)
                            previewFocusRequest = null
                            true
                        }
                        setDataSource(file.absolutePath)
                        prepare()
                        start()
                    }.also { previewPlayer = it }
                    playing.value = true
                    previewFocus()
                }
            }
        }
    }

    /** Transient media focus while the pre-send preview plays. */
    private fun previewFocus() {
        if (previewFocusRequest != null) return
        previewFocusRequest = requestTransientSpeechFocus()
    }

    /**
     * RETRY: deletes the take and starts a fresh recording — there is no
     * idle "tap to record" step, so the new take begins immediately.
     */
    fun retryRecording() {
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        abandonFocus(previewFocusRequest)
        previewFocusRequest = null
        outputFile?.delete()
        outputFile = null
        previewing.value = false
        playing.value = false
        sendFailed.value = false
        finalDurationSeconds.value = 0
        checkMicThenStart()
    }

    /** Once-per-second UI tick (recording wall clock / preview position). */
    fun tick() {
        elapsedSeconds.value = when {
            recording.value -> recordingStartedAt
                ?.let { ((SystemClock.elapsedRealtime() - it) / 1000).toInt() } ?: 0
            playing.value -> (previewPlayer?.currentPosition?.let { (it / 1000).toInt() }
                ?: finalDurationSeconds.value).coerceAtMost(finalDurationSeconds.value)
            else -> elapsedSeconds.value
        }
    }

    /** SEND: uploads the take; a failure keeps it + SEND for a retry
     *  (feedback 2026-08-19: a failed send must not vanish silently). */
    fun sendCurrent(onDone: () -> Unit) {
        val file = outputFile ?: run { onDone(); return }
        previewing.value = false
        sendFailed.value = false
        sending.value = true
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        abandonFocus(previewFocusRequest)
        previewFocusRequest = null
        if (file.length() <= 0) {
            file.delete()
            sending.value = false
            onDone()
            return
        }
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                ChatClient.sendVoiceNote(roomId, file)
            }
            if (sent) {
                file.delete()
                onDone()
            } else {
                android.util.Log.w(TAG, "VoiceNote: failed to send to room $roomId")
                outputFile = file
                sending.value = false
                previewing.value = true
                sendFailed.value = true
            }
        }
    }

    /** Leaving the screen mid-recording discards the take (dev's onStop rule). */
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        discardRecording()
    }

    private fun discardRecording() {
        releaseRecorder()
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        abandonFocus(recordFocusRequest)
        recordFocusRequest = null
        abandonFocus(previewFocusRequest)
        previewFocusRequest = null
        outputFile?.delete()
        outputFile = null
        recordingStartedAt = null
        elapsedSeconds.value = 0
        finalDurationSeconds.value = 0
        recording.value = false
        previewing.value = false
        playing.value = false
        sending.value = false
        sendFailed.value = false
    }

    companion object {
        private const val TAG = "RecordVoiceNote"
        private const val PERMISSION = "android.permission.RECORD_AUDIO"
    }
}
