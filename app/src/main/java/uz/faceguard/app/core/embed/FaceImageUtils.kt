package uz.faceguard.app.core.embed

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect

object FaceImageUtils {

    /** Returns an upright bitmap for the given sensor rotation (0 returns source). */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Crops the face bounding box with a small margin, clamped to bitmap bounds. */
    fun cropFace(bitmap: Bitmap, box: Rect): Bitmap? {
        val margin = (box.width() * 0.15f).toInt().coerceAtLeast(0)
        val left = (box.left - margin).coerceIn(0, bitmap.width - 1)
        val top = (box.top - margin).coerceIn(0, bitmap.height - 1)
        val right = (box.right + margin).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + margin).coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}
