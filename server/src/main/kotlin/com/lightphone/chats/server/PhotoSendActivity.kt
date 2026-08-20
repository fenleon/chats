package com.lightphone.chats.server

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
 * The "attach a photo" flow (Phase 13). The tool runtime forbids
 * startActivity and the companion can't launch activities from the background,
 * so the tool calls `StartPhotoSend` (which records the room) and then starts
 * this activity via `SimpleLightScreen.startServerActivity`. The activity shows
 * the system photo picker; the chosen photo is compressed, uploaded to Matrix,
 * and sent in the recorded room — the tool's thread poll then shows it.
 *
 * Picker resilience (2026-08-19 feedback round — "sending photos does not
 * work, the phone doesn't ask for permissions"): some devices (the LP3's
 * stripped firmware) have no handler for `ACTION_GET_CONTENT` or the Android
 * photo picker, so the flow falls back through GET_CONTENT → `ACTION_PICK_IMAGES`
 * → an in-app gallery over DCIM/Pictures (which requests READ_MEDIA_IMAGES at
 * runtime — the permission dialog the user expected).
 */
class PhotoSendActivity : ComponentActivity() {

    private var roomId: String? = null

    /** Gallery fallback visible (the system pickers are absent). */
    private var galleryVisible by mutableStateOf(false)
    /** READ_MEDIA_IMAGES was denied — show why, with a retry. */
    private var mediaDenied by mutableStateOf(false)
    /** The gallery's photo list (scanned off the main thread). */
    private var photos by mutableStateOf<List<File>>(emptyList())

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val room = roomId ?: return@registerForActivityResult
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            sendPhoto(room, readBytes(uri), queryName(uri) ?: "photo.jpg", guessMime(uri))
        } else {
            finish()
        }
    }

    private val requestMediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            mediaDenied = false
            showGallery()
        } else {
            mediaDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roomId = takePendingRoomId() ?: run {
            finish()
            return
        }
        setContent { Content() }
        launchSystemPicker()
    }

    @Composable
    private fun Content() {
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
                        onClick = { finish() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Photos"),
                )
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        // The system picker is launching over this screen — an
                        // empty host until the result lands.
                        mediaDenied -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = "Photo access is needed to pick a photo.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )
                        }
                        !galleryVisible -> Unit
                        photos.isEmpty() -> LightText(
                            text = "No photos on this device.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 24.dp),
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(photos, key = { it.absolutePath }) { photo ->
                                PhotoRow(photo) { sendPhotoFile(photo) }
                            }
                        }
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = if (mediaDenied) {
                        listOf(
                            null,
                            LightBarButton.Text(
                                text = "ALLOW",
                                onClick = {
                                    mediaDenied = false
                                    requestMediaPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
                                },
                            ),
                            null,
                        )
                    } else {
                        listOf(null, null, null)
                    },
                )
            }
        }
    }

    /** Tries the system pickers in order; the in-app gallery is the last resort. */
    private fun launchSystemPicker() {
        var launched = false
        try {
            pickPhoto.launch(
                Intent(Intent.ACTION_GET_CONTENT)
                    .setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
            )
            launched = true
        } catch (_: Exception) {
            // No DocumentsUI handler — try the Android photo picker below.
        }
        if (!launched && Build.VERSION.SDK_INT >= 33) {
            try {
                pickPhoto.launch(Intent(MediaStore.ACTION_PICK_IMAGES))
                launched = true
            } catch (_: Exception) {
                // No photo picker module either — in-app gallery.
            }
        }
        if (!launched) requestMediaPermissionIfNeeded()
    }

    private fun requestMediaPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showGallery()
        } else {
            requestMediaPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    /** The gallery fallback: DCIM/Pictures images, tapped to send. */
    private fun showGallery() {
        galleryVisible = true
        mediaDenied = false
        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { scanPhotos() }
        }
    }

    @Composable
    private fun PhotoRow(photo: File, onPick: () -> Unit) {
        // Small decode: full-size bitmaps for every row would blow memory.
        var thumb by remember(photo) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(photo) {
            thumb = withContext(Dispatchers.IO) { decodeThumb(photo) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onPick)
                .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val t = thumb
            if (t != null) {
                Image(
                    bitmap = t.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 48.dp, height = 48.dp),
                )
            }
            LightText(
                text = photo.name,
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.padding(start = 1.5f.gridUnitsAsDp()),
            )
        }
    }

    private fun decodeThumb(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 128)
            .takeHighestOneBit().coerceAtLeast(1)
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /** Images under DCIM + Pictures, newest first, bounded. */
    private fun scanPhotos(): List<File> {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        )
        val out = ArrayList<File>()
        val exts = setOf("jpg", "jpeg", "png", "webp", "gif")
        outer@ for (root in roots) {
            if (!root.isDirectory) continue@outer
            for (f in root.walkTopDown()) {
                if (f.isFile && f.extension.lowercase() in exts) {
                    out += f
                    if (out.size >= MAX_GALLERY_PHOTOS) break@outer
                }
            }
        }
        return out.sortedByDescending { it.lastModified() }
    }

    private fun sendPhotoFile(file: File) {
        val room = roomId ?: return
        sendPhoto(room, runCatching { file.readBytes() }.getOrNull() ?: ByteArray(0), file.name, "image/jpeg")
    }

    /** Reads, compresses, uploads and sends the photo; then closes. */
    private fun sendPhoto(roomId: String, bytes: ByteArray, fileName: String, mime: String) {
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    MatrixRepository.sendPhoto(roomId, photoPayload(bytes, fileName, mime))
                }.getOrDefault(false)
            }
            if (sent) {
                android.util.Log.d(TAG, "PhotoSend: sent photo to room $roomId")
            } else {
                android.util.Log.w(TAG, "PhotoSend: failed to send photo to room $roomId")
            }
            finish()
        }
    }

    private fun readBytes(uri: Uri): ByteArray =
        runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: ByteArray(0)

    private fun guessMime(uri: Uri): String = contentResolver.getType(uri) ?: "image/jpeg"

    /** Compresses [bytes] to the send size and returns what [MatrixRepository.sendPhoto] needs. */
    private fun photoPayload(bytes: ByteArray, fileName: String, mime: String): MatrixRepository.PhotoPayload {
        if (bytes.isEmpty()) return MatrixRepository.PhotoPayload(ByteArray(0), fileName, mime, 0, 0)
        val compressed = MatrixRepository.compressImage(
            bytes,
            MatrixRepository.SENT_PHOTO_MAX_DIMENSION,
            MatrixRepository.SENT_PHOTO_JPEG_QUALITY,
        ) ?: return MatrixRepository.PhotoPayload(ByteArray(0), fileName, mime, 0, 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(compressed, 0, compressed.size, bounds)
        return MatrixRepository.PhotoPayload(
            jpeg = compressed,
            fileName = fileName,
            mimeType = mime,
            width = bounds.outWidth,
            height = bounds.outHeight,
        )
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        private const val TAG = "PhotoSendActivity"
        private const val MAX_GALLERY_PHOTOS = 200

        /** The room awaiting [PhotoSendActivity] (one at a time). */
        @Volatile
        private var pendingRoom: String? = null

        /** Records the room a photo attach should land in (called by StartPhotoSend). */
        fun register(roomId: String) {
            pendingRoom = roomId
        }

        private fun takePendingRoomId(): String? =
            pendingRoom.also { pendingRoom = null }
    }
}
