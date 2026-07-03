package com.qcgo.quality

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Clean/dirty classifier backed by the INT8 LiteRT model (clean_dirty_int8.tflite).
 *
 * Model contract (must match model/export_litert.py):
 *   input : uint8 [1, 224, 224, 3], pixel range [0, 255]  (raw camera pixels)
 *   output: float32 [1, 2] softmax over [clean, dirty]
 *
 * Runs on CPU with multiple threads (XNNPACK is on by default in the LiteRT
 * runtime). We intentionally do NOT enable the GPU delegate — it is unreliable
 * for these graphs on many Android devices and the model is tiny on CPU.
 */
class QualityClassifier(
    context: Context,
    modelAsset: String = "clean_dirty_int8.tflite",
    numThreads: Int = 4,
) {
    enum class Label { CLEAN, DIRTY }

    data class Result(val label: Label, val confidence: Float)

    private val interpreter: Interpreter
    private val inputSize = 224
    private val inputBuffer: ByteBuffer
    private val output = Array(1) { FloatArray(2) }
    private val pixels = IntArray(inputSize * inputSize)
    private val scaledBitmap: Bitmap =
        Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(loadModelFile(context, modelAsset), options)

        // uint8 input => 1 byte per channel.
        inputBuffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
    }

    private fun loadModelFile(context: Context, asset: String): ByteBuffer {
        context.assets.openFd(asset).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                val channel = stream.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
    }

    /** Classify a single item crop. The crop is resized to 224x224 internally. */
    fun classify(crop: Bitmap): Result {
        // Resize crop into the reusable 224x224 ARGB bitmap.
        val canvas = android.graphics.Canvas(scaledBitmap)
        val dst = android.graphics.Rect(0, 0, inputSize, inputSize)
        canvas.drawBitmap(crop, null, dst, null)

        inputBuffer.rewind()
        scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            // ARGB int -> R,G,B bytes (model expects RGB, uint8, [0,255]).
            inputBuffer.put(((p shr 16) and 0xFF).toByte())
            inputBuffer.put(((p shr 8) and 0xFF).toByte())
            inputBuffer.put((p and 0xFF).toByte())
        }

        interpreter.run(inputBuffer, output)
        val probs = output[0]
        return if (probs[0] >= probs[1]) {
            Result(Label.CLEAN, probs[0])
        } else {
            Result(Label.DIRTY, probs[1])
        }
    }

    fun close() {
        interpreter.close()
        scaledBitmap.recycle()
    }
}
