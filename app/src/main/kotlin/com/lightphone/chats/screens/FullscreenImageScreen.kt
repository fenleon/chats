package com.lightphone.chats.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen photo viewer: tapping an image row in the thread opens it here.
 * Shows the display JPEG scaled to fit the screen; the back button (or a tap
 * on the photo) closes. Pinch zooms up to 5x, double-tap toggles 1x/2x.
 * The bottom bar saves the photo to the device's
 * Pictures/Chats album (server-side original, not this display JPEG);
 * the LP3-classic fullscreen panel confirms.
 */
class FullscreenImageScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    /** Image event id — keys the shared decoded-bitmap cache ([chatsBitmapCache]). */
    private val eventId: String,
    private val bytes: ByteArray,
    /** Video rows: the viewer shows the extracted frame(s) — SAVE hidden
     *  (it would save a thumbnail frame, not the media). */
    private val video: Boolean = false,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // GIF bytes keep their animation: decode via ImageDecoder into an
        // AnimatedImageDrawable and play it (the thread row stays a static
        // first frame — animated rows would burn the battery for a scroll-past).
        // Flipbook containers (WhatsApp GIFs sent as silent mp4 loops —
        // [chatsFlipbook]) cycle their extracted frames. Everything else
        // decodes to a still, seeded from the shared decode cache so the
        // thread row finds the bitmap already decoded when this viewer closes.
        val gif = isGif(bytes)
        val bitmap by produceState<ImageBitmap?>(chatsBitmapCache.get(eventId)?.bitmap, bytes) {
            if (value == null && !gif) {
                value = withContext(Dispatchers.Default) {
                    // Keep the flipbook flag intact — the thread row reads it
                    // from this cache when the viewer closes.
                    val decoded = chatsFlipbook.firstFrame(bytes)
                        ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?.asImageBitmap()?.let { DecodedBitmap(it, false) }
                    decoded?.also { chatsBitmapCache.put(eventId, it) }?.bitmap
                }
            }
        }
        val animated by produceState<AnimatedImageDrawable?>(null, bytes) {
            if (value == null && gif) {
                value = withContext(Dispatchers.Default) {
                    ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(bytes, 0, bytes.size)
                    ) as? AnimatedImageDrawable
                }
            }
        }
        val flipbook by produceState<Pair<List<ImageBitmap>, Int>?>(null, bytes) {
            if (value == null && !gif) {
                value = withContext(Dispatchers.Default) { chatsFlipbook.parse(bytes) }
            }
        }
        val image = bitmap
        // Zoom/pan state, keyed on the photo so a new image starts at 1x.
        var scale by remember(bytes) { mutableFloatStateOf(1f) }
        var pan by remember(bytes) { mutableStateOf(Offset.Zero) }
        var boxSize by remember(bytes) { mutableStateOf(IntSize.Zero) }
        val imageModifier = Modifier
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
            }

        // Save state: null = idle, true/false = result (drives the confirm panel).
        val scope = rememberCoroutineScope()
        var saving by remember(bytes) { mutableStateOf(false) }
        var saved by remember(bytes) { mutableStateOf<Boolean?>(null) }
        // The confirmation auto-dismisses after 2 s — no tap needed (LP3
        // feedback 2026-09-03).
        LaunchedEffect(saved) {
            if (saved != null) {
                delay(2_000)
                saved = null
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .onSizeChanged { boxSize = it },
            ) {
                if (animated != null) {
                    Image(
                        painter = remember { GifPainter(animated!!) },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier,
                    )
                } else if (flipbook != null) {
                    // Flipbook: cycle the extracted frames at the pace the
                    // companion measured out of the video.
                    val frames = flipbook!!.first
                    val frameMs = flipbook!!.second
                    var frameIdx by remember(bytes) { mutableIntStateOf(0) }
                    LaunchedEffect(bytes) {
                        while (true) {
                            delay(frameMs.toLong())
                            frameIdx = (frameIdx + 1) % frames.size
                        }
                    }
                    Image(
                        bitmap = frames[frameIdx],
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier,
                    )
                } else if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier,
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
                    items = if (video) emptyList() else listOf(
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

private fun isGif(bytes: ByteArray) = bytes.size > 3 &&
    bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
    bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()

/**
 * Painter for an [AnimatedImageDrawable]: the drawable invalidates itself per
 * animation frame through its [Drawable.Callback]; bumping a snapshot state
 * re-runs [onDraw]. Driven from the main-thread handler the drawable expects.
 */
private class GifPainter(private val drawable: AnimatedImageDrawable) : Painter() {
    private val frame = mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())

    init {
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame.intValue++
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, `when`)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what)
            }
        }
        drawable.start()
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        else Size.Unspecified

    override fun DrawScope.onDraw() {
        // Observed snapshot read — each animation frame invalidates the draw.
        frame.intValue
        // ContentScale.Fit by hand: the drawable's bounds are unset at decode,
        // and the painter's intrinsic size alone doesn't drive the Image's
        // scaling here.
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw > 0 && ih > 0) {
            val scale = minOf(size.width / iw, size.height / ih)
            val dw = iw * scale
            val dh = ih * scale
            drawable.setBounds(
                ((size.width - dw) / 2f).toInt(),
                ((size.height - dh) / 2f).toInt(),
                ((size.width + dw) / 2f).toInt(),
                ((size.height + dh) / 2f).toInt(),
            )
        }
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
