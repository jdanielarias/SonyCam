package com.sonycam.monitor.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.math.tanh

/** Exercise the real GPU shader, including compilation and output pixels. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class FocusPeakingRendererTest {
    private fun source(width: Int = 64, height: Int = 64, gray: (Int, Int) -> Int): Bitmap =
        Bitmap.createBitmap(IntArray(width * height) { i ->
            val v = gray(i % width, i / width)
            Color.rgb(v, v, v)
        }, width, height, Bitmap.Config.ARGB_8888)

    private fun render(input: Bitmap, level: FocusPeakingLevel,
                       width: Int = 64, height: Int = 64): Bitmap {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val node = RenderNode("peaking-test").apply { setPosition(0, 0, width, height) }
        try {
            val canvas = node.beginRecording()
            canvas.drawColor(Color.BLACK)
            FocusPeakingRenderer().draw(canvas,
                BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                input.width, input.height, width.toFloat(), height.toFloat(), level)
            node.endRecording()
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var frame = reader.acquireLatestImage()
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (frame == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10)
                frame = reader.acquireLatestImage()
            }
            val image = requireNotNull(frame) { "GPU did not produce a frame" }
            return image.use {
                requireNotNull(it.hardwareBuffer).use { buffer ->
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB)))
                    try { requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { hardware.recycle() }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }

    private fun redCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.red(it) > Color.green(it) + 40 &&
            Color.red(it) > Color.blue(it) + 40 }
    }

    @Test fun sharpDetailIsMarkedButBlurredEdgeAndNoiseAreNot() {
        val sharp = source { x, _ -> if (x < 32) 40 else 220 }
        val blurred = source { x, _ -> (130 + 90 * tanh((x - 31.5) / 4.0)).roundToInt() }
        val noise = source { x, y -> 128 + (x * 17 + y * 13) % 5 - 2 }
        val marked = render(sharp, FocusPeakingLevel.MEDIUM)
        val soft = render(blurred, FocusPeakingLevel.MEDIUM)
        val quiet = render(noise, FocusPeakingLevel.HIGH)
        try {
            assertTrue("Sharp edge should have red peaking", redCount(marked) > 30)
            assertEquals("Broad blur should not peak", 0, redCount(soft))
            assertEquals("Low-contrast noise should not peak", 0, redCount(quiet))
        } finally { listOf(sharp, blurred, noise, marked, soft, quiet).forEach { it.recycle() } }
    }

    @Test fun levelsIncreaseSensitivityAndOffPreservesTheImage() {
        val input = source { x, _ -> if (x < 32) 100 else 155 }
        val low = render(input, FocusPeakingLevel.LOW)
        val medium = render(input, FocusPeakingLevel.MEDIUM)
        val high = render(input, FocusPeakingLevel.HIGH)
        val off = render(input, FocusPeakingLevel.OFF)
        try {
            assertTrue(redCount(low) < redCount(medium))
            assertTrue(redCount(medium) <= redCount(high))
            assertEquals(0, redCount(off))
            assertEquals(input.getPixel(20, 20), off.getPixel(20, 20))
            assertEquals(input.getPixel(40, 20), off.getPixel(40, 20))
        } finally { listOf(input, low, medium, high, off).forEach { it.recycle() } }
    }

    @Test fun aspectFitKeepsLetterboxBlackWithoutPeakingBorders() {
        val input = source(width = 64, height = 32) { _, _ -> 210 }
        val result = render(input, FocusPeakingLevel.HIGH)
        try {
            assertEquals(Color.BLACK, result.getPixel(32, 8))
            assertEquals(Color.rgb(210, 210, 210), result.getPixel(32, 32))
            assertEquals(Color.BLACK, result.getPixel(32, 56))
            assertEquals(0, redCount(result))
        } finally { input.recycle(); result.recycle() }
    }
}
