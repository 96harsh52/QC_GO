package com.qcgo.quality

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/** One classified item. */
data class ItemResult(val box: Rect, val label: QualityClassifier.Label, val confidence: Float)

/** Aggregate result for one frame. */
data class FrameResult(
    val total: Int,
    val clean: Int,
    val dirty: Int,
    val items: List<ItemResult>,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * CameraX analyzer that chains: OpenCV count -> crop -> clean/dirty classify.
 * Configure the ImageAnalysis use case with RGBA_8888 output so each frame is a
 * ready-to-use bitmap.
 */
class AnalyzerPipeline(
    private val counter: ItemCounter,
    private val classifier: QualityClassifier,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    // Set from the UI thread when the user taps "Capture background".
    private val captureBackground = AtomicBoolean(false)

    fun requestCaptureBackground() {
        captureBackground.set(true)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap() // CameraX util for RGBA_8888 frames

            if (captureBackground.getAndSet(false)) {
                counter.setBackground(bitmap)
            }

            val boxes: List<Rect> = counter.count(bitmap)

            val items = ArrayList<ItemResult>(boxes.size)
            var clean = 0
            for (box in boxes) {
                val crop = safeCrop(bitmap, box) ?: continue
                val res = classifier.classify(crop)
                if (crop !== bitmap) crop.recycle()
                if (res.label == QualityClassifier.Label.CLEAN) clean++
                items.add(ItemResult(box, res.label, res.confidence))
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
}
