package com.maksimowiczm.foodyou.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.annotation.ColorInt

/**
 * Draws the apple: a faint full outline as the track, with the progress fraction stroked over it.
 * At fraction 0 the track shows bare; at 1 it is fully covered. Glance is RemoteViews underneath and
 * cannot draw arbitrary shapes, so this has to become a Bitmap.
 */
internal object AppleMeterRenderer {

    /**
     * RemoteViews cross a Binder transaction (~1 MB practical ceiling; the framework's own cap is
     * 6 x screenW x screenH, far larger). At 384px ARGB_8888 this is 576 KiB, which covers the
     * ~385px native size of a ~110dp apple at 3.5x density without risking the transaction.
     */
    const val MAX_SIZE_PX = 384

    fun render(
        sizePx: Int,
        strokeFraction: Float,
        @ColorInt trackColor: Int,
        @ColorInt progressColor: Int,
    ): Bitmap {
        val size = sizePx.coerceIn(1, MAX_SIZE_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokeWidth = size * STROKE_RATIO
        val body = appleBody(size.toFloat(), strokeWidth)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        // Track first, progress over it — which is what makes the no-goal case (fraction 0) an
        // unfilled outline for free.
        canvas.drawPath(body, paint.apply { color = trackColor })
        canvas.drawPath(leaf(size.toFloat()), Paint(paint).apply { color = trackColor })

        val fraction = strokeFraction.coerceIn(0f, 1f)
        if (fraction > 0f) {
            // PathMeasure.getLength() only covers the FIRST contour, so the measured path must stay
            // a single closed contour — the leaf is drawn separately and never measured.
            val measure = PathMeasure(body, false)
            val progress = Path()
            val _ = measure.getSegment(0f, measure.length * fraction, progress, true)
            canvas.drawPath(progress, paint.apply { color = progressColor })
        }

        return bitmap
    }

    /** One closed contour: two shoulders, two lobes, a dimple on top and a point at the base. */
    private fun appleBody(size: Float, strokeWidth: Float): Path {
        val pad = strokeWidth / 2f
        val w = size - pad * 2
        val cx = size / 2f
        val top = pad + w * 0.18f
        val bottom = size - pad

        return Path().apply {
            moveTo(cx, top)
            // right lobe
            cubicTo(cx + w * 0.16f, top - w * 0.10f, cx + w * 0.50f, top + w * 0.02f, cx + w * 0.50f, top + w * 0.30f)
            cubicTo(cx + w * 0.50f, top + w * 0.58f, cx + w * 0.26f, bottom, cx, bottom)
            // left lobe (mirror)
            cubicTo(cx - w * 0.26f, bottom, cx - w * 0.50f, top + w * 0.58f, cx - w * 0.50f, top + w * 0.30f)
            cubicTo(cx - w * 0.50f, top + w * 0.02f, cx - w * 0.16f, top - w * 0.10f, cx, top)
            close()
        }
    }

    /** Decoration only — a separate contour, deliberately outside the measured track. */
    private fun leaf(size: Float): Path {
        val cx = size / 2f
        val top = size * 0.16f
        return Path().apply {
            moveTo(cx, top)
            quadTo(cx + size * 0.14f, top - size * 0.12f, cx + size * 0.20f, top - size * 0.02f)
        }
    }

    private const val STROKE_RATIO = 0.075f
}
