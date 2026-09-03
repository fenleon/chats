package com.lightphone.chats.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen photo viewer: tapping an image row in the thread opens it here.
 * Shows the display JPEG scaled to fit the screen; the back button (or a tap
 * on the photo) closes. Pinch zooms up to 5x, double-tap toggles 1x/2x
 * (feedback 2026-08-23). The bottom bar saves the photo to the device's
 * Pictures/Chats album (server-side original, not this display JPEG);
 * the LP3-classic fullscreen panel confirms (2026-09-03).
 */
class FullscreenImageScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    /** Image event id — keys the shared decoded-bitmap cache ([chatsBitmapCache]). */
    private val eventId: String,
    private val bytes: ByteArray,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // Decode off the main thread; the viewer shows the background until
        // the bitmap lands (feedback 2026-08-23). Seeded from the shared
        // decode cache so the thread row finds the bitmap already decoded
        // when this viewer closes (LP3 2026-08-23 — re-decode flash).
        val bitmap by produceState<ImageBitmap?>(chatsBitmapCache.get(eventId), bytes) {
            if (value == null) {
                value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }?.also { chatsBitmapCache.put(eventId, it) }
            }
        }
        val image = bitmap
        // Zoom/pan state, keyed on the photo so a new image starts at 1x.
        var scale by remember(bytes) { mutableFloatStateOf(1f) }
        var pan by remember(bytes) { mutableStateOf(Offset.Zero) }
        var boxSize by remember(bytes) { mutableStateOf(IntSize.Zero) }
        // Save state: null = idle, true/false = result (drives the confirm panel).
        val scope = rememberCoroutineScope()
        var saving by remember(bytes) { mutableStateOf(false) }
        var saved by remember(bytes) { mutableStateOf<Boolean?>(null) }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .onSizeChanged { boxSize = it },
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            // Pinch to zoom + drag to pan; a plain tap closes
                            // (lightClickable — no double-tap to race it, the
                            // transform detector only claims moved gestures).
                            .lightClickable(onClick = { goBack() })
                            .pointerInput(bytes) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                                    val maxPanX = ((scale - 1f) * boxSize.width / 2f).coerceAtLeast(0f)
                                    val maxPanY = ((scale - 1f) * boxSize.height / 2f).coerceAtLeast(0f)
                                    pan = Offset(
                                        (pan.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                                        (pan.y + panChange.y).coerceIn(-maxPanY, maxPanY),
                                    )
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pan.x
                                translationY = pan.y
                            },
                    )
                }
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                )
                LightBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.Text(
                            text = "SAVE PHOTO",
                            onClick = {
                                if (!saving && saved == null) {
                                    saving = true
                                    scope.launch {
                                        saved = ChatClient.saveMessageImage(roomId, eventId)
                                        saving = false
                                    }
                                }
                            },
                        ),
                    ),
                )
                saved?.let { ok ->
                    LightFullscreenModal(
                        message = if (ok) "Photo saved" else "Couldn't save photo",
                        onClose = { saved = null },
                    )
                }
            }
        }
    }
}
