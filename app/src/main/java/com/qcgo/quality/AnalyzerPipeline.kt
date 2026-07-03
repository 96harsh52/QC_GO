package com.qcgo.quality

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/** One classified item, with a small ROI thumbnail for display. */
data class ItemResult(
    val box: Rect,
    val label: QualityClassifier.Label,
    val confidence: Float,
    val thumb: Bitmap,
)

/** Aggregate result for one capture. */
data class FrameResult(
    val total: Int,
    val clean: Int,
    val dirty: Int,
    val items: List<ItemResult>,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * CameraX analyzer. Idle until the user taps "Capture Items"; then it grabs the
 * next frame, counts items (OpenCV), and classifies each one clean/dirty. Each
 * item keeps a downscaled ROI crop so the result screen can show it.
 *
 * Robust fallback: if the counter finds no items (e.g. a single bottle filling
 * the frame), the WHOLE frame is treated as one item, so the user always gets a
 * clean/dirty answer.
 */
class AnalyzerPipeline(
    private val counter: ItemCounter,
    private val classifier: QualityClassifier,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val captureBackground = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)

    /** Record the current empty scene to help counting. */
    fun requestCaptureBackground() {
        captureBackground.set(true)
    }

    /** Capture the next frame and classify it. */
    fun requestCapture() {
        captureRequested.set(true)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap() // CameraX util for RGBA_8888 frames

            if (captureBackground.getAndSet(false)) {
                counter.setBackground(bitmap)
                return
            }

            if (!captureRequested.getAndSet(false)) return

            var boxes: List<Rect> = counter.count(bitmap)
            if (boxes.isEmpty()) {
                boxes = listOf(Rect(0, 0, bitmap.width, bitmap.height))
            }

            val items = ArrayList<ItemResult>(boxes.size)
            var clean = 0
            for (box in boxes) {
                val crop = safeCrop(bitmap, box) ?: continue
                val res = classifier.classify(crop)
                val thumb = thumbOf(crop)
                if (crop !== bitmap) crop.recycle()
                if (res.label == QualityClassifier.Label.CLEAN) clean++
                items.add(ItemResult(box, res.label, res.confidence, thumb))
            }

            onResult(
                FrameResult(
                    total = items.size,
                    clean = clean,
                    dirty = items.size - clean,
                    items = items,
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height,
                )
            )
        } finally {
            image.close()
        }
    }

    /** Crop a box (clamped to the frame) with a little padding for context. */
    private fun safeCrop(src: Bitmap, box: Rect, pad: Float = 0.06f): Bitmap? {
        val px = (box.width() * pad).toInt()
        val py = (box.height() * pad).toInt()
        val x0 = (box.left - px).coerceIn(0, src.width - 1)
        val y0 = (box.top - py).coerceIn(0, src.height - 1)
        val x1 = (box.right + px).coerceIn(x0 + 1, src.width)
        val y1 = (box.bottom + py).coerceIn(y0 + 1, src.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(src, x0, y0, w, h)
    }

    /** A detached, downscaled copy of the crop for on-screen display. */
    private fun thumbOf(src: Bitmap, maxSide: Int = 400): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxSide.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
