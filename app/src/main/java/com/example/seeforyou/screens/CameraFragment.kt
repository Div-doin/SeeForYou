package com.example.seeforyou.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService
import com.example.seeforyou.utils.*
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var tvLabel: TextView
    private lateinit var tvConf: TextView
    private lateinit var tvObject1: TextView
    private lateinit var tvObject2: TextView
    private lateinit var tvNavInstruction: TextView
    private lateinit var navCard: LinearLayout
    private lateinit var btnNavigate: Button
    private lateinit var btnStopNav: Button

    // Core systems
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yoloDetector: YoloDetector
    private lateinit var audioQueue: AudioPriorityQueue
    private lateinit var navigationManager: NavigationManager
    private lateinit var speechInput: SpeechInputHelper
    private val detectionFilter = SmartDetectionFilter()

    // Detection state
    private var lastDetections: List<Detection> = emptyList()
    private var lastBitmapWidth  = 1
    private var lastBitmapHeight = 1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView      = view.findViewById(R.id.camera_preview)
        overlay          = view.findViewById(R.id.detection_overlay)
        tvLabel          = view.findViewById(R.id.tv_detection_label)
        tvConf           = view.findViewById(R.id.tv_confidence)
        tvObject1        = view.findViewById(R.id.tv_object1)
        tvObject2        = view.findViewById(R.id.tv_object2)
        tvNavInstruction = view.findViewById(R.id.tv_nav_instruction)
        navCard          = view.findViewById(R.id.nav_card)
        btnNavigate      = view.findViewById(R.id.btn_navigate)
        btnStopNav       = view.findViewById(R.id.btn_stop_nav)

        cameraExecutor    = Executors.newSingleThreadExecutor()
        yoloDetector      = YoloDetector(requireContext())
        audioQueue        = AudioPriorityQueue(requireContext())
        navigationManager = NavigationManager(requireContext())

        setupNavigation()
        setupSpeechInput()

        btnNavigate.setOnClickListener { startNavigationFlow() }
        btnStopNav.setOnClickListener  { stopNavigationFlow()  }

        previewView.post {
            if (hasCameraPermission()) startCamera()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    // ─── Navigation Setup ────────────────────────────────────────────────────

    private fun setupNavigation() {
        navigationManager.onInstructionReady = { instruction, priority ->
            activity?.runOnUiThread {
                tvNavInstruction.text = instruction
                navCard.visibility    = View.VISIBLE
            }
            audioQueue.speak(instruction, priority)
        }

        navigationManager.onNavigationComplete = {
            activity?.runOnUiThread {
                navCard.visibility     = View.GONE
                btnNavigate.visibility = View.VISIBLE
                btnStopNav.visibility  = View.GONE
            }
        }

        navigationManager.onError = { error ->
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                audioQueue.speak(error, AudioPriorityQueue.Priority.INFO)
                btnNavigate.isEnabled = true
                btnNavigate.text      = "🎤 Navigate"
            }
        }
    }

    private fun setupSpeechInput() {
        speechInput = SpeechInputHelper(
            context = requireContext(),
            onResult = { destination ->
                activity?.runOnUiThread {
                    btnNavigate.text = "Finding route..."
                    audioQueue.speak(
                        "Searching for $destination. Please wait.",
                        AudioPriorityQueue.Priority.INFO
                    )
                }
                geocodeAndNavigate(destination)
            },
            onError = { error ->
                activity?.runOnUiThread {
                    audioQueue.speak(error, AudioPriorityQueue.Priority.INFO)
                    btnNavigate.isEnabled = true
                    btnNavigate.text      = "🎤 Navigate"
                }
            },
            onListening = {
                activity?.runOnUiThread {
                    btnNavigate.text = "Listening..."
                    audioQueue.speak(
                        "Where do you want to go?",
                        AudioPriorityQueue.Priority.INFO
                    )
                }
            }
        )
    }

    private fun startNavigationFlow() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 103)
            return
        }
        btnNavigate.isEnabled = false
        btnNavigate.text      = "Listening..."
        btnStopNav.visibility = View.VISIBLE
        speechInput.startListening()
    }

    private fun stopNavigationFlow() {
        navigationManager.stopNavigation()
        navCard.visibility     = View.GONE
        btnStopNav.visibility  = View.GONE
        btnNavigate.visibility = View.VISIBLE
        btnNavigate.isEnabled  = true
        btnNavigate.text       = "🎤 Navigate"
    }

    private fun geocodeAndNavigate(destinationName: String) {
        try {
            val geocoder  = Geocoder(requireContext(), Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocationName(destinationName, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                navigationManager.startNavigation(
                    address.latitude,
                    address.longitude,
                    destinationName
                )
                activity?.runOnUiThread {
                    btnNavigate.isEnabled = true
                    btnNavigate.text      = "🎤 Navigate"
                    btnStopNav.visibility = View.VISIBLE
                }
            } else {
                activity?.runOnUiThread {
                    audioQueue.speak(
                        "Could not find $destinationName. Please try again.",
                        AudioPriorityQueue.Priority.INFO
                    )
                    btnNavigate.isEnabled = true
                    btnNavigate.text      = "🎤 Navigate"
                }
            }
        } catch (e: Exception) {
            activity?.runOnUiThread {
                audioQueue.speak(
                    "Location search failed. Check internet connection.",
                    AudioPriorityQueue.Priority.INFO
                )
                btnNavigate.isEnabled = true
                btnNavigate.text      = "🎤 Navigate"
            }
        }
    }

    // ─── Camera ──────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (!isHidden && hasCameraPermission()) startCamera()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            releaseCamera()
            detectionFilter.reset()
        } else {
            previewView.post { startCamera() }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val cameraProvider = future.get()
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
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            future.get().unbindAll()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ─── Frame Processing ────────────────────────────────────────────────────

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap          = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val rotated = rotateBitmap(bitmap, rotationDegrees)
        val result  = yoloDetector.detectWithMotion(rotated)

        if (result != null) {
            lastDetections   = result
            lastBitmapWidth  = rotated.width
            lastBitmapHeight = rotated.height

            // Run smart filter — picks ONE object to speak about
            val filterResult = detectionFilter.process(
                result,
                rotated.width,
                rotated.height
            )

            if (filterResult != null && filterResult.shouldSpeak) {
                audioQueue.speak(filterResult.alert, filterResult.priority)

                // Log high confidence detections
                if (filterResult.detection.confidence > 0.7f) {
                    FirebaseService.logDetection(
                        filterResult.detection.label,
                        filterResult.detection.confidence
                    )
                }
            }
        }

        updateUI(lastDetections, lastBitmapWidth, lastBitmapHeight)
    }

    // ─── UI Update ───────────────────────────────────────────────────────────

    private fun updateUI(
        detections: List<Detection>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        activity?.runOnUiThread {
            overlay.setYoloResults(detections, bitmapWidth, bitmapHeight)

            if (detections.isEmpty()) {
                tvLabel.text         = "Scanning..."
                tvConf.text          = ""
                tvObject1.visibility = View.GONE
                tvObject2.visibility = View.GONE
                return@runOnUiThread
            }

            // Top bar — highest priority object
            val top      = detections[0]
            val topDepth = DepthEstimator.estimate(top.boundingBox, bitmapHeight)
            tvLabel.text = top.label
            tvConf.text  = "${(top.confidence * 100).toInt()}%"

            // Object card 1
            val obj1 = detections.getOrNull(0)
            if (obj1 != null) {
                val d = DepthEstimator.estimate(obj1.boundingBox, bitmapHeight)
                val p = PositionEstimator.estimate(obj1.boundingBox, bitmapWidth)
                tvObject1.text       = "${obj1.label} — ${p.shortDescription} — ${d.description}"
                tvObject1.visibility = View.VISIBLE
            } else {
                tvObject1.visibility = View.GONE
            }

            // Object card 2
            val obj2 = detections.getOrNull(1)
            if (obj2 != null) {
                val d = DepthEstimator.estimate(obj2.boundingBox, bitmapHeight)
                val p = PositionEstimator.estimate(obj2.boundingBox, bitmapWidth)
                tvObject2.text       = "${obj2.label} — ${p.shortDescription} — ${d.description}"
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

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun hasCameraPermission()   = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission()    = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            102 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startNavigationFlow()
            103 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startNavigationFlow()
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        audioQueue.shutdown()
        navigationManager.shutdown()
        speechInput.destroy()
        yoloDetector.close()
        detectionFilter.reset()
    }
}