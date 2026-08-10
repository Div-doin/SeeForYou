package com.example.seeforyou.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService
import com.example.seeforyou.services.TtsService
import com.example.seeforyou.utils.Detection
import com.example.seeforyou.utils.DetectionOverlayView
import com.example.seeforyou.utils.YoloDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var tvLabel: TextView
    private lateinit var tvConf: TextView
    private lateinit var tvObject1: TextView
    private lateinit var tvObject2: TextView
    private lateinit var tts: TtsService
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yoloDetector: YoloDetector

    // Keep last result for skipped frames
    private var lastDetections: List<Detection> = emptyList()
    private var lastBitmapWidth = 1
    private var lastBitmapHeight = 1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.camera_preview)
        overlay     = view.findViewById(R.id.detection_overlay)
        tvLabel     = view.findViewById(R.id.tv_detection_label)
        tvConf      = view.findViewById(R.id.tv_confidence)
        tvObject1   = view.findViewById(R.id.tv_object1)
        tvObject2   = view.findViewById(R.id.tv_object2)

        tts            = TtsService(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()
        yoloDetector   = YoloDetector(requireContext())

        previewView.post {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            } else {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            releaseCamera()
        } else {
            previewView.post { startCamera() }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                android.util.Log.e("CameraFragment", "Binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun releaseCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

        // detectWithMotion returns:
        // null            → frame skipped, reuse lastDetections
        // emptyList()     → ran inference, nothing found
        // list of results → ran inference, objects found
        val result = yoloDetector.detectWithMotion(rotatedBitmap)

        if (result != null) {
            // Inference ran — update last known result
            lastDetections = result
            lastBitmapWidth = rotatedBitmap.width
            lastBitmapHeight = rotatedBitmap.height

            if (result.isNotEmpty()) {
                val top   = result[0]
                tts.speak("${top.label} ahead")
                if (top.confidence > 0.7f) {
                    FirebaseService.logDetection(top.label, top.confidence)
                }
            }
        }
        // If result is null (skipped frame), we just redraw with lastDetections

        updateUI(lastDetections, lastBitmapWidth, lastBitmapHeight)
    }

    private fun updateUI(
        detections: List<Detection>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        activity?.runOnUiThread {
            overlay.setYoloResults(detections, bitmapWidth, bitmapHeight)

            if (detections.isEmpty()) {
                tvLabel.text = "Scanning..."
                tvConf.text  = ""
                tvObject1.visibility = View.GONE
                tvObject2.visibility = View.GONE
                return@runOnUiThread
            }

            val top     = detections[0]
            tvLabel.text = top.label
            tvConf.text  = "${(top.confidence * 100).toInt()}%"

            val obj1 = detections.getOrNull(0)
            if (obj1 != null) {
                tvObject1.text       = "${obj1.label} — ${(obj1.confidence * 100).toInt()}%"
                tvObject1.visibility = View.VISIBLE
            } else {
                tvObject1.visibility = View.GONE
            }

            val obj2 = detections.getOrNull(1)
            if (obj2 != null) {
                tvObject2.text       = "${obj2.label} — ${(obj2.confidence * 100).toInt()}%"
                tvObject2.visibility = View.VISIBLE
            } else {
                tvObject2.visibility = View.GONE
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        tts.shutdown()
        yoloDetector.close()
    }
}