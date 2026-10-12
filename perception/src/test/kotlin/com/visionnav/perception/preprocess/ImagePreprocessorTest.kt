package com.visionnav.perception.preprocess

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImagePreprocessorTest {

    private val w = 64
    private val h = 48
    private val yRowStride = w + 16 // row padding, as real HALs do
    private val uvRowStride = w + 8
    private val uvPixelStride = 2 // interleaved (NV21-like) chroma

    /**
     * Builds a YUV_420_888 frame with grey luma and a "red" chroma block (V = 255) plus bright luma
     * in the sensor's top-right region x ∈ [0.75, 1), y ∈ [0, 0.25).
     */
    private fun frame(): YuvPlanes {
        val y = ByteArray(yRowStride * h) { 0x55 } // stride padding garbage
        for (r in 0 until h) for (c in 0 until w) {
            val inBlock = c >= w * 3 / 4 && r < h / 4
            y[r * yRowStride + c] = (if (inBlock) 200 else 128).toByte()
        }
        val chroma = ByteArray(uvRowStride * (h / 2)) { 0x11 }
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            val inBlock = c >= (w / 2) * 3 / 4 && r < (h / 2) / 4
            chroma[r * uvRowStride + c * 2] = (if (inBlock) 255 else 128).toByte() // V
            chroma[r * uvRowStride + c * 2 + 1] = 128.toByte() // U
        }
        val v = ByteBuffer.wrap(chroma)
        val u = ByteBuffer.wrap(chroma, 1, chroma.size - 1).slice()
        return YuvPlanes(ByteBuffer.wrap(y), yRowStride, 1, u, v, uvRowStride, uvPixelStride, w, h)
    }

    private class Rgb(val r: Float, val g: Float, val b: Float)

    private fun pixel(buf: ByteBuffer, size: Int, x: Int, y: Int): Rgb {
        val f = buf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val i = (y * size + x) * 3
        return Rgb(f.get(i), f.get(i + 1), f.get(i + 2))
    }

    /** Model-input pixel at the centre of a normalised upright point. */
    private fun inputAt(lb: LetterboxParams, ux: Float, uy: Float) =
        lb.uprightNormXToInput(ux).toInt() to lb.uprightNormYToInput(uy).toInt()

    private fun expectRedAt(rotation: Int, ux: Float, uy: Float) {
        val pre = ImagePreprocessor(inputSize = 32, lumaLongSide = 32)
        val out = pre.process(frame(), rotation)
        val (ix, iy) = inputAt(out.letterbox, ux, uy)
        val red = pixel(out.input, 32, ix, iy)
        assertThat(red.r).isGreaterThan(0.95f)
        assertThat(red.g).isLessThan(0.5f)
        // The diagonally opposite region is neutral grey.
        val (gx, gy) = inputAt(out.letterbox, 1f - ux, 1f - uy)
        val grey = pixel(out.input, 32, gx, gy)
        assertThat(grey.r).isWithin(0.01f).of(128f / 255f)
        assertThat(grey.g).isWithin(0.01f).of(128f / 255f)
        assertThat(grey.b).isWithin(0.01f).of(128f / 255f)
        // Luma thumbnail is upright too.
        val tx = (ux * out.lumaWidth).toInt()
        val ty = (uy * out.lumaHeight).toInt()
        assertThat(out.luma[ty * out.lumaWidth + tx].toInt() and 0xFF).isEqualTo(200)
        val ox = ((1f - ux) * out.lumaWidth).toInt()
        val oy = ((1f - uy) * out.lumaHeight).toInt()
        assertThat(out.luma[oy * out.lumaWidth + ox].toInt() and 0xFF).isEqualTo(128)
    }

    @Test fun rotation0_blockStaysTopRight() = expectRedAt(0, 0.875f, 0.125f)

    @Test fun rotation90_blockMovesBottomRight() = expectRedAt(90, 0.875f, 0.875f)

    @Test fun rotation180_blockMovesBottomLeft() = expectRedAt(180, 0.125f, 0.875f)

    @Test fun rotation270_blockMovesTopLeft() = expectRedAt(270, 0.125f, 0.125f)

    @Test fun portraitOutputIsLetterboxedWithGreyPadding() {
        val pre = ImagePreprocessor(inputSize = 32, lumaLongSide = 32)
        val out = pre.process(frame(), 90)
        assertThat(out.uprightWidth).isEqualTo(48)
        assertThat(out.uprightHeight).isEqualTo(64)
        assertThat(out.letterbox.padX).isEqualTo(4)
        assertThat(out.letterbox.padY).isEqualTo(0)
        assertThat(out.lumaWidth).isEqualTo(24)
        assertThat(out.lumaHeight).isEqualTo(32)
        val pad = pixel(out.input, 32, 0, 16)
        assertThat(pad.r).isEqualTo(ImagePreprocessor.PAD)
        assertThat(pad.g).isEqualTo(ImagePreprocessor.PAD)
        assertThat(pad.b).isEqualTo(ImagePreprocessor.PAD)
        val padRight = pixel(out.input, 32, 31, 16)
        assertThat(padRight.r).isEqualTo(ImagePreprocessor.PAD)
    }

    @Test fun buffersAreReusedAcrossFrames() {
        val pre = ImagePreprocessor(inputSize = 32, lumaLongSide = 32)
        val a = pre.process(frame(), 90)
        val b = pre.process(frame(), 90)
        assertThat(b.input).isSameInstanceAs(a.input)
        assertThat(b.luma).isSameInstanceAs(a.luma)
        assertThat(a.input.isDirect).isTrue()
        assertThat(a.input.capacity()).isEqualTo(32 * 32 * 3 * 4)
    }

    @Test fun geometryChangeRebuildsTables() {
        val pre = ImagePreprocessor(inputSize = 32, lumaLongSide = 32)
        pre.process(frame(), 90)
        val out = pre.process(frame(), 0)
        assertThat(out.uprightWidth).isEqualTo(64)
        assertThat(out.letterbox.padY).isEqualTo(4)
    }
}
