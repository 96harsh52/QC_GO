package com.qcgo.quality

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
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

/**
 * Capture screen. Guided two-step flow:
 *   Step 1 — capture the empty background (helps counting).
 *   Step 2 — place the item(s) and capture; results open on [ResultActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    private lateinit var counter: ItemCounter
    private lateinit var classifier: QualityClassifier
    private lateinit var pipeline: AnalyzerPipeline

    private var backgroundReady = false
    private var capturing = false

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

        binding.captureButton.setOnClickListener { onCaptureClicked() }
        binding.retakeButton.setOnClickListener {
            backgroundReady = false
            updateStep()
        }

        updateStep()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the result screen — allow another capture.
        capturing = false
        binding.captureButton.isEnabled = true
        updateStep()
    }

    private fun onCaptureClicked() {
        if (capturing) return
        if (!backgroundReady) {
            pipeline.requestCaptureBackground()
            backgroundReady = true
            updateStep()
            Toast.makeText(this, getString(R.string.bg_captured), Toast.LENGTH_SHORT).show()
        } else {
            capturing = true
            binding.captureButton.isEnabled = false
            binding.captureButton.text = getString(R.string.analyzing)
            pipeline.requestCapture()
        }
    }

    /** Reflect the current step in the pill + button. */
    private fun updateStep() {
        if (!backgroundReady) {
            binding.stepTitle.text = getString(R.string.step1_title)
            binding.stepDesc.text = getString(R.string.step1_desc)
            binding.captureButton.text = getString(R.string.capture_background)
            binding.retakeButton.visibility = View.GONE
        } else {
            binding.stepTitle.text = getString(R.string.step2_title)
            binding.stepDesc.text = getString(R.string.step2_desc)
            binding.captureButton.text = getString(R.string.capture_items)
            binding.retakeButton.visibility = View.VISIBLE
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
            ResultStore.latest = result
            startActivity(Intent(this, ResultActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        classifier.close()
    }

    companion object {
        private const val TAG = "QC_GO"
    }
}
