package com.lightphone.chats.screens

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Yuv420ToArgbTest {

    private fun buf(vararg bytes: Byte) = ByteBuffer.wrap(bytes)

    @Test
    fun `limited-range white decodes to full white`() {
        // 2x2, y=235 (limited white), neutral chroma; planar layout.
        val y = ByteArray(4) { 235.toByte() }
        val u = ByteArray(1) { 128.toByte() }
        val v = ByteArray(1) { 128.toByte() }
        val out = yuv420ToArgb(
            buf(*y), yRowStride = 2, yPixStride = 1,
            buf(*u), uRowStride = 1, uPixStride = 1,
            buf(*v), vRowStride = 1, vPixStride = 1,
            width = 2, height = 2,
        )
        for (argb in out) {
            assertEquals(0xFF, (argb ushr 24) and 0xFF)
            assertEquals(255, (argb shr 16) and 0xFF)
            assertEquals(255, (argb shr 8) and 0xFF)
            assertEquals(255, argb and 0xFF)
        }
    }

    @Test
    fun `limited-range black decodes to black`() {
        val y = ByteArray(4) { 16.toByte() }
        val u = ByteArray(1) { 128.toByte() }
        val v = ByteArray(1) { 128.toByte() }
        val out = yuv420ToArgb(
            buf(*y), 2, 1, buf(*u), 1, 1, buf(*v), 1, 1,
            width = 2, height = 2,
        )
        assertTrue(out.all { it == 0xFF000000.toInt() })
    }

    @Test
    fun `chroma follows the 2x2 subsampling grid`() {
        // 2x2 with U=255 (max blue) on the (0,0)/(1,1) chroma sample — all
        // four pixels share it: blue channel wins everywhere.
        val y = ByteArray(4) { 128.toByte() }
        val u = ByteArray(1) { 255.toByte() }
        val v = ByteArray(1) { 128.toByte() }
        val out = yuv420ToArgb(
            buf(*y), 2, 1, buf(*u), 1, 1, buf(*v), 1, 1,
            width = 2, height = 2,
        )
        for (argb in out) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            assertTrue(b > r && b > g, "expected blue-dominant, got #${
                Integer.toHexString(argb)
            }")
        }
    }

    @Test
    fun `semi-planar strides read the same pixels as planar`() {
        // NV12-style: one interleaved UV buffer, pixelStride 2; V plane is the
        // same buffer starting one byte in. Y rows padded with stride 4.
        val width = 2
        val height = 2
        val y = byteArrayOf(235.toByte(), 235.toByte(), 0, 0, 16.toByte(), 16.toByte(), 0, 0)
        val uv = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 128.toByte())
        val planar = yuv420ToArgb(
            buf(*y), 4, 1,
            buf(128.toByte()), 1, 1, buf(128.toByte()), 1, 1,
            width, height,
        )
        val semi = yuv420ToArgb(
            buf(*y), 4, 1,
            buf(*uv), 2, 2, buf(*uv), 2, 2,
            width, height,
        )
        // Row 0 white, row 1 black in both layouts (V buffer shares the same
        // interleaved bytes — V samples land on the second byte of each pair).
        assertEquals(planar[0], semi[0])
        assertEquals(planar[3], semi[3])
    }
}
