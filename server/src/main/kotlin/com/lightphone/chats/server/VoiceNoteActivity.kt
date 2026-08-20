package com.lightphone.chats.server

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "record a voice note" screen (Phase 14). The tool runtime forbids
 * startActivity and the companion can't launch activities from the background,
 * so the tool calls `StartVoiceNoteSend` (which records the room) and then
 * starts this activity via `SimpleLightScreen.startServerActivity` — the same
 * pattern as [PhotoSendActivity]. Tap the mic to record (Opus in an ogg
 * container — the MSC3245 canonical voice-message format, ~2-3× smaller than
 * the old AAC/m4a at speech bitrates), tap again to stop; the recording is
 * uploaded to Matrix as an m.audio message and sent in the recorded room.
 * RECORD_AUDIO is requested at
 * runtime (the manifest declares it; the launcher activity grants it on
 * install, but the companion's own install may not carry the grant).
 */
class VoiceNoteActivity : ComponentActivity() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var previewPlayer: android.media.MediaPlayer? = null

    // Activity-level state so the recording functions can flip the UI (a
    // composable-local remember can't be reached from startRecording/stopAndSend).
    private var recording by mutableStateOf(false)
    private var previewing by mutableStateOf(false)
    private var playing by mutableStateOf(false)
    private var sending by mutableStateOf(false)
    /** RECORD_AUDIO was denied — show why, with a retry (2026-08-19 feedback
     *  round: "the phone doesn't ask for the permission" — a denial must not
     *  silently drop the screen). */
    private var micDenied by mutableStateOf(false)
    /** The last send failed — keep the take + SEND so the user can retry
     *  instead of a silent drop (2026-08-19 feedback round). */
    private var sendFailed by mutableStateOf(false)

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
        setContent {
            val themeColors by LightThemeController.colors.collectAsState()

            LightTheme(colors = themeColors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = {
                                discardRecording()
                                finish()
                            },
                            contentDescription = "Discard voice note",
                        ),
                        center = LightTopBarCenter.Text("Voice note"),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Record → tap to stop → play the take back → SEND
                            // in the bottom bar (or back to discard). A mic
                            // denial shows a message + ALLOW instead of a
                            // silent drop.
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .lightClickable(
                                        enabled = !sending,
                                        onClick = {
                                            when {
                                                micDenied -> {
                                                    micDenied = false
                                                    ensureMicThenRecord()
                                                }
                                                recording -> stopRecording()
                                                previewing -> togglePreviewPlayback()
                                                else -> ensureMicThenRecord()
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightIcon(
                                    icon = when {
                                        micDenied -> LightIcons.MICROPHONE
                                        recording -> LightIcons.MICROPHONE
                                        previewing && playing -> LightIcons.PAUSE
                                        previewing -> LightIcons.PLAY
                                        else -> LightIcons.MICROPHONE
                                    },
                                    size = 3f,
                                    contentDescription = when {
                                        micDenied -> "Allow microphone"
                                        recording -> "Stop recording"
                                        previewing -> if (playing) "Stop preview" else "Play preview"
                                        else -> "Record voice note"
                                    },
                                )
                            }
                            LightText(
                                text = when {
                                    micDenied -> "Microphone permission is needed to record."
                                    sending -> "Sending…"
                                    recording -> "Recording… tap to stop"
                                    previewing && playing -> "Playing… tap to stop"
                                    previewing -> "Tap to play back"
                                    else -> "Tap to record"
                                },
                                variant = LightTextVariant.Copy,
                                lighten = !recording && !sending,
                                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
                            )
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
                            null,
                            if ((previewing || sendFailed) && !sending) {
                                LightBarButton.Text(
                                    text = "SEND",
                                    onClick = { sendCurrent(roomId) },
                                )
                            } else {
                                null
                            },
                            null,
                        ),
                    )
                }
            }
        }
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
        recording = true
    }

    private fun stopRecording() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recording = false
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
            }
            player != null -> {
                player.seekTo(0)
                player.start()
                playing = true
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
                        setOnCompletionListener { playing = false }
                        setOnErrorListener { _, _, _ -> playing = false; true }
                        setDataSource(file.absolutePath)
                        prepare()
                        start()
                    }.also { previewPlayer = it }
                    playing = true
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
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        outputFile?.delete()
        outputFile = null
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
