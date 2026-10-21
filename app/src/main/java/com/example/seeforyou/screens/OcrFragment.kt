package com.example.seeforyou.screens

import android.Manifest
import android.content.pm.PackageManager
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
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import com.example.seeforyou.localization.AppLanguage
import java.util.Locale
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService
import com.example.seeforyou.services.TtsService
import com.example.seeforyou.voice.SpeechGate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var tvCharCount: TextView
    private lateinit var tvCameraHint: TextView
    private lateinit var btnRead: MaterialButton
    private lateinit var tts: TtsService
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var readingMuted = false
    private var lastReadText = ""
    private var captureGeneration = 0
    private var pendingVoiceRead = false

    fun readFromVoice() {
        SpeechGate.set(this, true)
        pendingVoiceRead = true
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        } else if (imageCapture != null) {
            pendingVoiceRead = false
            captureAndRead()
        }
    }

    private lateinit var recognizer: TextRecognizer
    internal fun readRecognizedText(text: String) {
        val language = if (text.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale("en", "IN")
        tts.speak(text, force = true, language = language)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_ocr, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView    = view.findViewById(R.id.ocr_preview)
        tvResult       = view.findViewById(R.id.tv_ocr_result)
        tvCharCount    = view.findViewById(R.id.tv_char_count)
        tvCameraHint   = view.findViewById(R.id.tv_camera_hint)
        btnRead        = view.findViewById(R.id.btn_read)
        tts            = TtsService(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()
        recognizer = if (AppLanguage.locale(requireContext()).language == "hi")
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        if (AppLanguage.locale(requireContext()).language == "kn") {
            view.findViewById<View>(R.id.tv_ocr_language_note).visibility = View.VISIBLE
            tvResult.setText(R.string.ocr_kn_limit)
            tvCameraHint.setText(R.string.ocr_kn_limit)
        }

        if (!isHidden && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else if (!isHidden) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }

        btnRead.setOnClickListener { captureAndRead() }
        view.findViewById<View>(R.id.btn_stop_reading).setOnClickListener {
            readingMuted = true
            tts.stop()
            pendingVoiceRead = false
            captureGeneration++
            btnRead.isEnabled = true
            btnRead.setText(R.string.read_aloud)
            SpeechGate.set(this, false)
        }
        view.findViewById<View>(R.id.btn_repeat_reading).setOnClickListener {
            readingMuted = false
            if (lastReadText.isBlank()) tts.speak(getString(R.string.no_previous_text), force = true)
            else readRecognizedText(lastReadText)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            SpeechGate.set(this, false)
            pendingVoiceRead = false
            imageCapture = null
            captureGeneration++
            btnRead.isEnabled = true
            btnRead.text = getString(R.string.read_aloud)
            readingMuted = true
            tts.stop()
            releaseCamera()
        } else startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            if (isHidden || view == null) return@addListener
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture!!
                )
                if (pendingVoiceRead) {
                    pendingVoiceRead = false
                    val token = captureGeneration
                    previewView.postDelayed({
                        if (view != null && !isHidden && token == captureGeneration) captureAndRead()
                    }, 500)
                }
            } catch (e: Exception) {
                pendingVoiceRead = false
                SpeechGate.set(this, false)
                android.util.Log.e("OcrFragment", "Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun releaseCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun captureAndRead() {
        if (!btnRead.isEnabled) return
        if (imageCapture == null) {
            tts.speak(getString(R.string.camera_not_ready), force = true)
            SpeechGate.set(this, false)
            return
        }
        readingMuted = false
        val generation = ++captureGeneration
        SpeechGate.set(this, true)
        android.util.Log.i("SeeForYouSpeech", "ocrCaptureStarted")
        btnRead.isEnabled = false
        btnRead.text      = getString(R.string.reading)
        tvCameraHint.text = getString(R.string.processing_image)
        tvResult.text     = ""
        tvCharCount.text  = ""

        imageCapture?.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val mediaImage = imageProxy.image ?: run {
                        imageProxy.close()
                        activity?.runOnUiThread {
                            if (generation == captureGeneration && view != null) {
                                SpeechGate.set(this@OcrFragment, false)
                                btnRead.isEnabled = true
                                btnRead.text = getString(R.string.read_aloud)
                                tvCameraHint.text = getString(R.string.image_unavailable)
                            }
                        }
                        return
                    }
                    val inputImage = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    recognizer.process(inputImage)
                        .addOnSuccessListener { result ->
                            val text = result.text.trim()
                            activity?.runOnUiThread {
                                if (generation != captureGeneration || view == null || isHidden) return@runOnUiThread
                                if (text.isEmpty()) {
                                    tvResult.textSize = 15f
                                    tvResult.setTextColor(
                                        requireContext().getColor(R.color.text_secondary)
                                    )
                                    tvResult.text    = getString(R.string.no_text_tips)
                                    tvCharCount.text = ""
                                    tvCameraHint.text = getString(R.string.align_text)
                                    lastReadText = ""
                                    if (!readingMuted) tts.speak(getString(R.string.no_text), force = true)
                                } else {
                                    tvResult.textSize = 16f
                                    tvResult.setTextColor(
                                        requireContext().getColor(R.color.text_primary)
                                    )
                                    tvResult.text    = text
                                    tvCharCount.text = getString(R.string.char_count, text.length)
                                    tvCameraHint.text = getString(R.string.text_recognized)
                                    lastReadText = text
                                    if (!readingMuted) readRecognizedText(text)
                                    FirebaseService.logOcr(text)
                                }
                                btnRead.isEnabled = true
                                btnRead.text      = getString(R.string.read_aloud)
                                // TTS has acquired its own gate before capture releases its gate.
                                SpeechGate.set(this@OcrFragment, false)
                                android.util.Log.i("SeeForYouSpeech", "ocrCaptureFinished")
                            }
                        }
                        .addOnFailureListener {
                            activity?.runOnUiThread {
                                if (generation != captureGeneration || view == null || isHidden) return@runOnUiThread
                                tvResult.text     = getString(R.string.read_failed)
                                tvCharCount.text  = ""
                                tvCameraHint.text = getString(R.string.align_text)
                                btnRead.isEnabled = true
                                btnRead.text      = getString(R.string.read_aloud)
                                SpeechGate.set(this@OcrFragment, false)
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }

                override fun onError(exception: ImageCaptureException) {
                    activity?.runOnUiThread {
                        if (generation != captureGeneration || view == null || isHidden) return@runOnUiThread
                        tvResult.text     = getString(R.string.camera_error)
                        tvCameraHint.text = getString(R.string.align_text)
                        btnRead.isEnabled = true
                        btnRead.text      = getString(R.string.read_aloud)
                        SpeechGate.set(this@OcrFragment, false)
                    }
                }
            }
        )
    }

    override fun onDestroyView() {
        SpeechGate.set(this, false)
        pendingVoiceRead = false
        imageCapture = null
        captureGeneration++
        super.onDestroyView()
        cameraExecutor.shutdown()
        recognizer.close()
        tts.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && view != null && !isHidden) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else {
                pendingVoiceRead = false
                SpeechGate.set(this, false)
                tts.speak(getString(R.string.voice_camera_permission), true)
            }
        }
    }

    override fun onStop() {
        pendingVoiceRead = false
        captureGeneration++
        btnRead.isEnabled = true
        btnRead.setText(R.string.read_aloud)
        SpeechGate.set(this, false)
        readingMuted = true
        tts.stop()
        super.onStop()
    }
}
