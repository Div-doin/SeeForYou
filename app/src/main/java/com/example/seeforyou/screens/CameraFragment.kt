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
import com.example.seeforyou.utils.DetectionOverlayView
import com.example.seeforyou.utils.YoloDetector
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
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

    private var frameCount = 0

    // --- Firebase streaming ---
    // Upload every Nth processed frame to Firebase Storage
    // so a laptop viewer sees a near-live JPEG at:
    //   gs://<your-bucket>/stream/frame.jpg
    private val streamFrameInterval = 10   // upload 1 out of every 10 processed frames
    private var streamFrameCount    = 0
    private var isUploading         = false // simple guard — skip if previous upload still running

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
        frameCount++
        if (frameCount % 3 != 0) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

        // --- Stream frame to Firebase Storage ---
        streamFrameCount++
        if (streamFrameCount % streamFrameInterval == 0 && !isUploading) {
            uploadFrameToFirebase(rotatedBitmap)
        }

        val detections = yoloDetector.detect(rotatedBitmap)

        if (detections.isEmpty()) {
            activity?.runOnUiThread {
                tvLabel.text = "Scanning..."
                tvConf.text  = ""
                tvObject1.visibility = View.GONE
                tvObject2.visibility = View.GONE
                overlay.setYoloResults(emptyList(), rotatedBitmap.width, rotatedBitmap.height)
            }
            return
        }

        val top     = detections[0]
        val label   = top.label
        val conf    = top.confidence
        val confPct = (conf * 100).toInt()

        tts.speak("$label ahead")

        if (conf > 0.7f) {
            FirebaseService.logDetection(label, conf)
        }

        activity?.runOnUiThread {
            tvLabel.text = label
            tvConf.text  = "$confPct%"
            overlay.setYoloResults(detections, rotatedBitmap.width, rotatedBitmap.height)

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

    /**
     * Compresses [bitmap] to JPEG and overwrites stream/frame.jpg in Firebase Storage.
     * Always overwrites the same file so the laptop viewer just refreshes one URL.
     */
    private fun uploadFrameToFirebase(bitmap: Bitmap) {
        isUploading = true
        val stream = ByteArrayOutputStream()
        // Quality 60 → good balance of size vs clarity for a remote preview
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val bytes = stream.toByteArray()

        val ref = FirebaseStorage.getInstance()
            .reference
            .child("stream/frame.jpg")

        ref.putBytes(bytes)
            .addOnSuccessListener {
                android.util.Log.d("CameraFragment", "Frame uploaded to Firebase Storage")
                isUploading = false
            }
            .addOnFailureListener { e ->
                android.util.Log.e("CameraFragment", "Upload failed: ${e.message}")
                isUploading = false
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