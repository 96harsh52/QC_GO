package com.qcgo.quality

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.qcgo.quality.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    private lateinit var counter: ItemCounter
    private lateinit var classifier: QualityClassifier
    private lateinit var pipeline: AnalyzerPipeline

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV failed to load")
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        counter = ItemCounter()
        classifier = QualityClassifier(this)
        pipeline = AnalyzerPipeline(counter, classifier) { result -> onCaptureResult(result) }

        binding.captureBgButton.setOnClickListener {
            pipeline.requestCaptureBackground()
            Toast.makeText(this, "Background captured", Toast.LENGTH_SHORT).show()
        }

        binding.captureButton.setOnClickListener {
            binding.resultText.text = getString(R.string.analyzing)
            binding.resultText.setBackgroundColor(COLOR_NEUTRAL)
            pipeline.requestCapture()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, pipeline) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCaptureResult(result: FrameResult) {
        runOnUiThread {
            binding.overlay.setResult(result)

            when {
                result.total == 0 -> {
                    binding.resultText.text = getString(R.string.result_none)
                    binding.resultText.setBackgroundColor(COLOR_NEUTRAL)
                }
                result.total == 1 -> {
                    // Single item: lead with the verdict + confidence.
                    val item = result.items[0]
                    val pct = (item.confidence * 100).toInt()
                    if (result.dirty == 0) {
                        binding.resultText.text = getString(R.string.result_clean_one, pct)
                        binding.resultText.setBackgroundColor(COLOR_CLEAN)
                    } else {
                        binding.resultText.text = getString(R.string.result_dirty_one, pct)
                        binding.resultText.setBackgroundColor(COLOR_DIRTY)
                    }
                }
                else -> {
                    // Multiple items: show the breakdown, e.g. "3 items\n1 clean · 2 dirty".
                    binding.resultText.text = getString(
                        R.string.result_many, result.total, result.clean, result.dirty,
                    )
                    binding.resultText.setBackgroundColor(
                        if (result.dirty == 0) COLOR_CLEAN else COLOR_DIRTY,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        classifier.close()
    }

    companion object {
        private const val TAG = "QC_GO"
        private const val COLOR_CLEAN = 0xE01B7F32.toInt()   // green
        private const val COLOR_DIRTY = 0xE0B00020.toInt()   // red
        private const val COLOR_NEUTRAL = 0xB0000000.toInt() // translucent black
    }
}
