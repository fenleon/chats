package com.lightphone.chats.server

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "attach a photo" picker (Phase 13). The tool runtime forbids
 * startActivity and the companion can't launch activities from the background,
 * so the tool calls `StartPhotoSend` (which records the room) and then starts
 * this activity via `SimpleLightScreen.startServerActivity`. The activity shows
 * the system photo picker; the chosen photo is compressed, uploaded to Matrix,
 * and sent in the recorded room — the tool's thread poll then shows it.
 *
 * Same pattern as Audiobooks' [DeleteConsentActivity]: the pending room id is
 * handed over through a static, since the launch intent can't carry extras.
 */
class PhotoSendActivity : ComponentActivity() {

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val roomId = pendingRoomId
        pendingRoomId = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (roomId != null && uri != null) {
            sendPickedPhoto(roomId, uri)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = takePendingRoomId() ?: run {
            finish()
            return
        }
        pendingRoomId = roomId
        pickPhoto.launch(
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
        )
    }

    /** Reads, compresses, uploads and sends the picked photo; then closes. */
    private fun sendPickedPhoto(roomId: String, uri: Uri) {
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                runCatching { MatrixRepository.sendPhoto(roomId, photoPayload(uri)) }.getOrDefault(false)
            }
            if (sent) {
                android.util.Log.d(TAG, "PhotoSend: sent photo to room $roomId")
            } else {
                android.util.Log.w(TAG, "PhotoSend: failed to send photo to room $roomId")
            }
            finish()
        }
    }

    /** Reads [uri]'s bytes, compresses to the send size, and returns what
     *  [MatrixRepository.sendPhoto] needs. */
    private fun photoPayload(uri: Uri): MatrixRepository.PhotoPayload {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return MatrixRepository.PhotoPayload(ByteArray(0), "photo.jpg", "image/jpeg", 0, 0)
        val fileName = queryName(uri) ?: "photo.jpg"
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
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

    private var pendingRoomId: String? = null

    companion object {
        private const val TAG = "PhotoSendActivity"

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
