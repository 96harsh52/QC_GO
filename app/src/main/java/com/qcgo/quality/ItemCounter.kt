package com.qcgo.quality

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Counts items on a FIXED background using classical OpenCV — no neural network.
 * This is a 1:1 port of model/count_opencv.py; keep the two in sync.
 *
 *   1. grayscale + blur
 *   2. background subtraction (absdiff) OR Otsu threshold if no background captured
 *   3. morphology to clean the mask
 *   4. findContours, keep blobs whose area is in [minAreaFrac, maxAreaFrac] of the frame
 *
 * Returns one bounding box (android.graphics.Rect) per item.
 */
class ItemCounter(
    private val blurKsize: Int = 5,
    private val thresh: Double = 30.0,
    private val minAreaFrac: Double = 0.005,
    private val maxAreaFrac: Double = 0.95,
    private val morphKsize: Int = 5,
) {
    // Reference image of the empty background (grayscale, blurred). Null until captured.
    private var backgroundGray: Mat? = null

    /** Capture the current frame as the empty-background reference. */
    fun setBackground(bitmap: Bitmap) {
        backgroundGray?.release()
        backgroundGray = toGrayBlur(bitmapToMat(bitmap))
    }

    fun hasBackground(): Boolean = backgroundGray != null

    fun clearBackground() {
        backgroundGray?.release()
        backgroundGray = null
    }

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba) // RGBA
        return rgba
    }

    private fun toGrayBlur(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        if (blurKsize >= 3) {
            Imgproc.GaussianBlur(gray, gray, Size(blurKsize.toDouble(), blurKsize.toDouble()), 0.0)
        }
        src.release()
        return gray
    }

    private fun buildMask(frameGray: Mat): Mat {
        val mask = Mat()
        val bg = backgroundGray
        if (bg != null) {
            val diff = Mat()
            Core.absdiff(frameGray, bg, diff)
            Imgproc.threshold(diff, mask, thresh, 255.0, Imgproc.THRESH_BINARY)
            diff.release()
        } else {
            // No reference background: assume a uniform backdrop; Otsu + invert.
            Imgproc.threshold(
                frameGray, mask, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
            )
        }
        val k = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(morphKsize.toDouble(), morphKsize.toDouble()),
        )
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k, org.opencv.core.Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k, org.opencv.core.Point(-1.0, -1.0), 1)
        k.release()
        return mask
    }

    /** Count items in [bitmap]; returns left-to-right sorted item boxes. */
    fun count(bitmap: Bitmap): List<Rect> {
        val frameArea = (bitmap.width.toLong() * bitmap.height.toLong()).toDouble()
        val minArea = minAreaFrac * frameArea
        val maxArea = maxAreaFrac * frameArea

        val frameGray = toGrayBlur(bitmapToMat(bitmap))
        val mask = buildMask(frameGray)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            mask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val boxes = ArrayList<Rect>()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < minArea || area > maxArea) {
                c.release()
                continue
            }
            val r = Imgproc.boundingRect(c)
            boxes.add(Rect(r.x, r.y, r.x + r.width, r.y + r.height))
            c.release()
        }

        frameGray.release()
        mask.release()
        hierarchy.release()

        boxes.sortBy { it.left }
        return boxes
    }
}
