package com.sonycam.monitor.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.max
import kotlin.math.min

internal enum class FocusPeakingLevel(val label: String, val threshold: Float) {
    OFF("PEAK OFF", 1f),
    LOW("PEAK BAJO", 0.055f),
    MEDIUM("PEAK MED", 0.028f),
    HIGH("PEAK ALTO", 0.014f);

    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * How the live view meets a screen of a different shape. The A7 III sends
 * 1024×576 (16:9) and a Pixel 10 is ~20:9, so one of the two has to give:
 * [FIT] shows every pixel of the frame with bands at the sides, [FILL] uses the
 * whole screen and loses ~10 % of the frame at the top and at the bottom.
 * Neither is right for every shot, which is why it is a toggle.
 */
internal enum class LiveViewFraming(val label: String) {
    FILL("LLENAR"),
    FIT("COMPLETO");

    fun next() = entries[(ordinal + 1) % entries.size]

    val contentScale: ContentScale get() = if (this == FILL) ContentScale.Crop else ContentScale.Fit

    /** Scale of the frame in the view, matching [contentScale] exactly. */
    fun scale(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float): Float {
        val sx = viewWidth / imageWidth
        val sy = viewHeight / imageHeight
        return if (this == FILL) max(sx, sy) else min(sx, sy)
    }
}

/** Display-only assistance: the camera's frame and saved captures stay untouched. */
@Composable
internal fun FocusPeakingImage(
    bitmap: Bitmap,
    level: FocusPeakingLevel,
    framing: LiveViewFraming,
    modifier: Modifier,
) {
    if (Build.VERSION.SDK_INT >= 33 && level != FocusPeakingLevel.OFF) {
        PeakingGpuImage(bitmap, level, framing, modifier)
    } else {
        Image(bitmap.asImageBitmap(), "Live view", modifier, contentScale = framing.contentScale)
    }
}

@RequiresApi(33)
@Composable
private fun PeakingGpuImage(
    bitmap: Bitmap,
    level: FocusPeakingLevel,
    framing: LiveViewFraming,
    modifier: Modifier,
) {
    val renderer = remember { FocusPeakingRenderer() }
    // New frames replace the input texture. There is no second frame queue or
    // CPU image processing in the USB/decode loop.
    val source = remember(bitmap) { BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    Canvas(modifier.semantics { contentDescription = "Live view con ayuda de enfoque roja" }) {
        renderer.draw(drawContext.canvas.nativeCanvas, source, bitmap.width, bitmap.height,
            size.width, size.height, level, framing)
    }
}

@RequiresApi(33)
internal class FocusPeakingRenderer {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint().apply { shader = this@FocusPeakingRenderer.shader }

    fun draw(canvas: AndroidCanvas, source: BitmapShader, imageWidth: Int, imageHeight: Int,
             viewWidth: Float, viewHeight: Float, level: FocusPeakingLevel,
             framing: LiveViewFraming = LiveViewFraming.FIT) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
        source.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        shader.setInputShader("image", source)
        shader.setFloatUniform("threshold", level.threshold)
        shader.setFloatUniform("imageSize", imageWidth.toFloat(), imageHeight.toFloat())
        // Work in source pixels, so peaking strength is independent of screen
        // size. Match the plain Image's ContentScale for the same framing, and
        // never mark letterbox edges.
        val scale = framing.scale(imageWidth.toFloat(), imageHeight.toFloat(), viewWidth, viewHeight)
        val save = canvas.save()
        try {
            // FILL draws past the view; the plain Image clips there, so must this.
            canvas.clipRect(0f, 0f, viewWidth, viewHeight)
            canvas.translate((viewWidth - imageWidth * scale) / 2f,
                (viewHeight - imageHeight * scale) / 2f)
            canvas.scale(scale, scale)
            canvas.drawRect(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat(), paint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private companion object {
        // A fine-detail high-pass plus a contrast gate rejects broad blurred
        // edges and low-contrast sensor noise. It estimates sharpness from the
        // preview; it is not an AF point or a lens-distance measurement.
        const val SOURCE = """
            uniform shader image;
            uniform float threshold;
            uniform float2 imageSize;

            float luma(float2 p) {
                return dot(float3(image.eval(p).rgb), float3(0.299, 0.587, 0.114));
            }

            half4 main(float2 p) {
                half4 original = image.eval(p);
                if (p.x < 1.5 || p.y < 1.5 ||
                    p.x > imageSize.x - 1.5 || p.y > imageSize.y - 1.5) return original;
                float c = luma(p);
                float tl = luma(p + float2(-1, -1));
                float t  = luma(p + float2( 0, -1));
                float tr = luma(p + float2( 1, -1));
                float l  = luma(p + float2(-1,  0));
                float r  = luma(p + float2( 1,  0));
                float bl = luma(p + float2(-1,  1));
                float b  = luma(p + float2( 0,  1));
                float br = luma(p + float2( 1,  1));
                float soft = (tl + tr + bl + br + 2.0 * (t + l + r + b) + 4.0 * c) / 16.0;
                float detail = abs(c - soft);
                float gx = (tr + 2.0 * r + br - tl - 2.0 * l - bl) / 4.0;
                float gy = (bl + 2.0 * b + br - tl - 2.0 * t - tr) / 4.0;
                float contrast = length(float2(gx, gy));
                float mark = smoothstep(threshold, threshold * 2.0, detail)
                    * smoothstep(0.10, 0.18, contrast);
                return mix(original, half4(1.0, 0.04, 0.02, 1.0), half(mark * 0.92));
            }
        """
    }
}
