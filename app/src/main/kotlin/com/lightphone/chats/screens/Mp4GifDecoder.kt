package com.lightphone.chats.screens

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Sequential mp4 video-frame decoder for the WhatsApp "GIF" flipbook v1
 * container (see [chatsFlipbook] and MatrixRepository.videoFlipbook): the
 * tool has no video player primitive and the plugin bans Context, so the
 * usual players (MediaPlayer / media3 / SurfaceView — all Context-hungry)
 * are out; MediaExtractor + MediaCodec need no Context and the plugin's
 * blocked-import/code lists don't name android.media. Frames decode ON DEMAND
 * in playback order (the viewer always cycles 0,1,2,… so no seeking), decoded
 * YUV converts to ARGB via [yuv420ToArgb] — memory stays at ~one frame
 * instead of the flipbook's every-frame-at-once.
 */
internal class Mp4GifDecoder(bytes: ByteArray) {

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var lastPtsUs = -1L

    /** Set once the stream drained to EOS — the caller rewinds to loop. */
    var ended = false
        private set

    val width: Int
    val height: Int

    init {
        extractor.setDataSource(Source(bytes))
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "no video track" }
        extractor.selectTrack(trackIndex)
        width = format!!.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /**
     * The next frame, or null at end-of-stream ([rewind] to loop). The return
     * delay is this frame's presentation delta (constant-fps loops make that
     * the frame duration); clamped 10–500 ms.
     */
    fun nextFrame(): Pair<Bitmap, Long>? {
        if (ended) return null
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val pts = info.presentationTimeUs
                val delayMs = if (lastPtsUs < 0) 66L else (pts - lastPtsUs).coerceIn(10_000, 500_000) / 1000
                lastPtsUs = pts
                val bitmap = codec.getOutputImage(outIndex)?.toBitmap()
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                if (bitmap != null) return bitmap to delayMs
                if (ended) return null
                continue // no output image for this buffer — keep draining
            }
            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> feed()
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
            }
        }
    }

    /** Restarts playback from frame 0 (flush + seek; the codec stays started). */
    fun rewind() {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        lastPtsUs = -1L
        ended = false
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    /** Feeds one sample; queues EOS when the extractor runs dry. */
    private fun feed() {
        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex < 0) return
        val buffer = codec.getInputBuffer(inIndex) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private class Source(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = bytes.size.toLong()
        override fun close() {}
    }
}

/** Decoded YUV_420_888 → ARGB_8888 bitmap (one-frame memory, discarded by the caller). */
internal fun Image.toBitmap(): Bitmap? {
    if (format != android.graphics.ImageFormat.YUV_420_888 || planes.size < 3) return null
    val (yP, uP, vP) = planes
    val pixels = yuv420ToArgb(
        yP.buffer, yP.rowStride, yP.pixelStride,
        uP.buffer, uP.rowStride, uP.pixelStride,
        vP.buffer, vP.rowStride, vP.pixelStride,
        width, height,
    )
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * YUV 4:2:0 (planar or semi-planar — the per-plane strides/pixel-strides
 * carry the layout) → ARGB ints, BT.601 limited-range (codec output).
 * Pure so [Yuv420ToArgbTest] can run it on the JVM.
 */
internal fun yuv420ToArgb(
    yBuf: ByteBuffer, yRowStride: Int, yPixStride: Int,
    uBuf: ByteBuffer, uRowStride: Int, uPixStride: Int,
    vBuf: ByteBuffer, vRowStride: Int, vPixStride: Int,
    width: Int, height: Int,
): IntArray {
    val out = IntArray(width * height)
    val yArr = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
    val uArr = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
    val vArr = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
    for (row in 0 until height) {
        val yRow = row * yRowStride
        val uvRow = row / 2
        for (col in 0 until width) {
            val y = (yArr[yRow + col * yPixStride].toInt() and 0xFF) - 16
            val uIdx = uvRow * uRowStride + (col / 2) * uPixStride
            val vIdx = uvRow * vRowStride + (col / 2) * vPixStride
            val u = (uArr[uIdx].toInt() and 0xFF) - 128
            val v = (vArr[vIdx].toInt() and 0xFF) - 128
            val r = 1.164383f * y + 1.596027f * v
            val g = 1.164383f * y - 0.391762f * u - 0.812968f * v
            val b = 1.164383f * y + 2.017232f * u
            val i = row * width + col
            out[i] = 0xFF shl 24 or
                ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b + 0.5f).toInt().coerceIn(0, 255)
        }
    }
    return out
}
