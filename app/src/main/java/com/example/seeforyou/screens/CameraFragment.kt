package com.example.seeforyou.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Geocoder
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import com.example.seeforyou.localization.AppLanguage
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    private lateinit var scenePolicy: QuietScenePolicy
    private lateinit var btnSilence: Button
    private lateinit var tvSpeechStatus: TextView
    @Volatile private var navigationAlertsEnabled = false
    @Volatile private var screenActive = false
    private var lastDescription: String? = null
    private var lastDescriptionAt = 0L
    private lateinit var searchExecutor: ExecutorService
    private var searchGeneration = 0
    @Volatile private var listeningForDestination = false
    private var confirmationDialog: AlertDialog? = null

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
        btnSilence = view.findViewById(R.id.btn_silence)
        tvSpeechStatus = view.findViewById(R.id.tv_speech_status)

        cameraExecutor    = Executors.newSingleThreadExecutor()
        scenePolicy = QuietScenePolicy(AppLanguage.book(requireContext()))
        searchExecutor = Executors.newSingleThreadExecutor()
        yoloDetector      = YoloDetector(requireContext())
        audioQueue        = AudioPriorityQueue(requireContext())
        navigationManager = NavigationManager(requireContext())

        setupNavigation()
        setupSpeechInput()

        btnNavigate.setOnClickListener {
            if (navigationManager.isActive()) stopNavigationFlow()
            startNavigationFlow()
        }
        btnStopNav.setOnClickListener  { stopNavigationFlow()  }
        view.findViewById<Button>(R.id.btn_describe).setOnClickListener { describeSurroundings() }
        view.findViewById<Button>(R.id.btn_read_text).setOnClickListener {
            requireActivity().findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_ocr
        }
        view.findViewById<Button>(R.id.btn_repeat).setOnClickListener {
            if (!listeningForDestination) {
                resumeSpeech()
                if (navigationManager.isActive()) navigationManager.repeatInstruction()
                else if (lastDescription != null && SystemClock.elapsedRealtime() - lastDescriptionAt < 15000) {
                    audioQueue.speak(lastDescription!!, AudioPriorityQueue.Priority.INFO)
                } else describeSurroundings()
            }
        }
        btnSilence.setOnClickListener {
            if (audioQueue.paused) resumeSpeech()
            else {
                audioQueue.pause()
                btnSilence.setText(R.string.resume_speech)
                tvSpeechStatus.setText(R.string.speech_paused)
            }
        }

        previewView.post {
            if (isHidden || this.view == null) return@post
            if (hasCameraPermission()) startCamera()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    // ─── Navigation Setup ────────────────────────────────────────────────────

    private fun resumeSpeech() {
        audioQueue.resume()
        btnSilence.setText(R.string.stop_speaking)
        tvSpeechStatus.setText(if (navigationManager.isActive()) R.string.quiet_navigation else R.string.quiet_ready)
    }
    fun pauseForVoice() {
        audioQueue.pause()
        btnSilence.setText(R.string.resume_speech)
        tvSpeechStatus.setText(R.string.speech_paused)
    }

    private fun describeSurroundings() {
        if (listeningForDestination) return
        resumeSpeech()
        val now = SystemClock.elapsedRealtime()
        val description = scenePolicy.description(now)
        lastDescription = description
        lastDescriptionAt = now
        tvSpeechStatus.text = description
        audioQueue.speak(description, AudioPriorityQueue.Priority.INFO)
    }

    private fun setupNavigation() {
        navigationManager.onInstructionReady = { instruction, priority ->
            navigationAlertsEnabled = navigationManager.isActive()
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                tvNavInstruction.text = instruction
                navCard.visibility    = View.VISIBLE
                if (navigationManager.isActive()) {
                    btnNavigate.isEnabled = true
                    btnNavigate.text = getString(R.string.change_destination)
                    if (!audioQueue.paused) tvSpeechStatus.setText(R.string.quiet_navigation)
                }
            }
            audioQueue.speak(instruction, priority)
        }

        navigationManager.onNavigationComplete = {
            navigationAlertsEnabled = false
            activity?.runOnUiThread {
                navCard.visibility     = View.GONE
                btnNavigate.visibility = View.VISIBLE
                btnStopNav.visibility  = View.GONE
                btnNavigate.isEnabled = true
                btnNavigate.text = getString(R.string.navigate)
                if (!audioQueue.paused) tvSpeechStatus.setText(R.string.quiet_ready)
            }
        }

        navigationManager.onError = { error ->
            navigationAlertsEnabled = false
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                audioQueue.speak(error, AudioPriorityQueue.Priority.INFO)
                btnNavigate.isEnabled = true
                btnNavigate.text      = getString(R.string.navigate)
                btnStopNav.visibility = View.GONE
                tvNavInstruction.text = error
                navCard.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSpeechInput() {
        speechInput = SpeechInputHelper(
            context = requireContext(),
            onResult = { destination ->
                listeningForDestination = false
                activity?.runOnUiThread {
                    btnNavigate.text = getString(R.string.finding_route)
                    audioQueue.speak(
                        getString(R.string.searching, destination),
                        AudioPriorityQueue.Priority.INFO
                    )
                }
                geocodeAndNavigate(destination)
            },
            onError = { error ->
                listeningForDestination = false
                activity?.runOnUiThread {
                    audioQueue.speak(error, AudioPriorityQueue.Priority.INFO)
                    btnNavigate.isEnabled = true
                    btnNavigate.text      = getString(R.string.navigate)
                    btnStopNav.visibility = View.GONE
                }
            },
            onListening = {
                activity?.runOnUiThread {
                    btnNavigate.text = getString(R.string.listening)
                    // Do not speak into the microphone while recognizing a destination.
                }
            }
        )
    }

    private fun startNavigationFlow() {
        resumeSpeech()
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 103)
            return
        }
        btnNavigate.isEnabled = false
        btnNavigate.text      = getString(R.string.listening)
        btnStopNav.visibility = View.VISIBLE
        listeningForDestination = true
        audioQueue.stop()
        speechInput.startListening()
    }

    private fun stopNavigationFlow() {
        navigationAlertsEnabled = false
        searchGeneration++
        listeningForDestination = false
        speechInput.cancel()
        confirmationDialog?.dismiss()
        audioQueue.stop()
        navigationManager.stopNavigation()
        navCard.visibility     = View.GONE
        btnStopNav.visibility  = View.GONE
        btnNavigate.visibility = View.VISIBLE
        btnNavigate.isEnabled  = true
        btnNavigate.text       = getString(R.string.navigate)
    }

    private fun geocodeAndNavigate(destinationName: String) {
        val session = ++searchGeneration
        val appContext = requireContext().applicationContext
        searchExecutor.execute {
            val addresses = try {
                @Suppress("DEPRECATION")
                Geocoder(appContext, AppLanguage.locale(appContext)).getFromLocationName(destinationName, 1)
            } catch (_: Exception) { null }
            activity?.runOnUiThread {
                if (session != searchGeneration || view == null || isHidden) return@runOnUiThread
                val address = addresses?.firstOrNull()
                if (address == null) {
                    audioQueue.speak(getString(R.string.destination_not_found), AudioPriorityQueue.Priority.NAVIGATION)
                    btnNavigate.isEnabled = true
                    btnNavigate.text = getString(R.string.navigate)
                    btnStopNav.visibility = View.GONE
                    return@runOnUiThread
                }
                val label = address.getAddressLine(0) ?: destinationName
                val prompt = getString(R.string.destination_confirm, label)
                audioQueue.speak(prompt, AudioPriorityQueue.Priority.NAVIGATION)
                confirmationDialog = AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.confirm_title))
                    .setMessage(getString(R.string.confirm_detail, label))
                    .setPositiveButton(getString(R.string.start_walking)) { _, _ ->
                        if (session == searchGeneration) {
                            audioQueue.stop()
                            btnNavigate.text = getString(R.string.finding_route)
                            navigationManager.startNavigation(address.latitude, address.longitude, label)
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ -> stopNavigationFlow() }
                    .setOnCancelListener { stopNavigationFlow() }
                    .show()
            }
        }
    }
    // ─── Camera ──────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        screenActive = !isHidden
        if (!isHidden && hasCameraPermission()) startCamera()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        screenActive = !hidden
        if (hidden) {
            if (btnStopNav.visibility == View.VISIBLE) stopNavigationFlow()
            releaseCamera()
            audioQueue.stop()
            scenePolicy.reset()
        } else {
            previewView.post { startCamera() }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (view == null || !screenActive || cameraExecutor.isShutdown) return@addListener
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
        if (!screenActive) { imageProxy.close(); return }
        val bitmap          = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val rotated = rotateBitmap(bitmap, rotationDegrees)
        val result  = yoloDetector.detectWithMotion(rotated)
        if (!screenActive) return

        if (result != null) {
            lastDetections   = result
            lastBitmapWidth  = rotated.width
            lastBitmapHeight = rotated.height

            val now = SystemClock.elapsedRealtime()
            scenePolicy.observe(result.map {
                QuietScenePolicy.ObjectInView(it.label, it.confidence,
                    it.boundingBox.centerX() / rotated.width,
                    (it.boundingBox.height() / rotated.height).coerceIn(0f, 1f))
            }, now)
            if (screenActive && navigationAlertsEnabled && !listeningForDestination && !audioQueue.paused) {
                scenePolicy.navigationAlert(now)?.let {
                    audioQueue.speak(it, AudioPriorityQueue.Priority.WARNING)
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
            if (!screenActive || view == null || !isAdded) return@runOnUiThread
            overlay.setYoloResults(detections, bitmapWidth, bitmapHeight)

            if (detections.isEmpty()) {
                tvLabel.text         = getString(R.string.scanning)
                tvConf.text          = ""
                tvObject1.visibility = View.GONE
                tvObject2.visibility = View.GONE
                return@runOnUiThread
            }

            // Top bar — highest priority object
            val top      = detections[0]
            val phrases = AppLanguage.book(requireContext())
            tvLabel.text = phrases.objectName(top.label)
            tvConf.text  = "${(top.confidence * 100).toInt()}%"

            // Object card 1
            val obj1 = detections.getOrNull(0)
            if (obj1 != null) {
                val position = when {
                    obj1.boundingBox.centerX() / bitmapWidth < 0.35f -> "left"
                    obj1.boundingBox.centerX() / bitmapWidth > 0.65f -> "right"
                    else -> "center"
                }
                tvObject1.text = phrases.text("object_position", phrases.objectName(obj1.label), phrases.text(position))
                tvObject1.visibility = View.VISIBLE
            } else {
                tvObject1.visibility = View.GONE
            }

            // Object card 2
            val obj2 = detections.getOrNull(1)
            if (obj2 != null) {
                val position = when {
                    obj2.boundingBox.centerX() / bitmapWidth < 0.35f -> "left"
                    obj2.boundingBox.centerX() / bitmapWidth > 0.65f -> "right"
                    else -> "center"
                }
                tvObject2.text = phrases.text("object_position", phrases.objectName(obj2.label), phrases.text(position))
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

    override fun onStop() {
        screenActive = false
        if (::btnStopNav.isInitialized && btnStopNav.visibility == View.VISIBLE) stopNavigationFlow()
        audioQueue.stop()
        scenePolicy.reset()
        super.onStop()
    }

    override fun onDestroyView() {
        screenActive = false
        searchGeneration++
        confirmationDialog?.dismiss()
        searchExecutor.shutdownNow()
        super.onDestroyView()
        cameraExecutor.shutdown()
        navigationManager.shutdown()
        audioQueue.shutdown()
        speechInput.destroy()
        yoloDetector.close()
        scenePolicy.reset()
    }
}
