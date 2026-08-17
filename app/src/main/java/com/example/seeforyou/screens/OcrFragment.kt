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
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService
import com.example.seeforyou.services.TtsService
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

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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

        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }

        btnRead.setOnClickListener { captureAndRead() }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) releaseCamera() else startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
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
            } catch (e: Exception) {
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
        btnRead.isEnabled = false
        btnRead.text      = "Reading..."
        tvCameraHint.text = "⏳  Processing image..."
        tvResult.text     = ""
        tvCharCount.text  = ""

        imageCapture?.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val mediaImage = imageProxy.image ?: run {
                        imageProxy.close()
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
                                if (text.isEmpty()) {
                                    tvResult.textSize = 15f
                                    tvResult.setTextColor(
                                        requireContext().getColor(R.color.text_secondary)
                                    )
                                    tvResult.text    = "No text found.\n\nTips:\n• Get closer to the text\n• Make sure lighting is good\n• Hold phone steady"
                                    tvCharCount.text = ""
                                    tvCameraHint.text = "📷  Align text within frame"
                                    tts.speak("No text found. Try again.", force = true)
                                } else {
                                    tvResult.textSize = 16f
                                    tvResult.setTextColor(
                                        requireContext().getColor(R.color.text_primary)
                                    )
                                    tvResult.text    = text
                                    tvCharCount.text = "${text.length} chars"
                                    tvCameraHint.text = "✅  Text recognized"
                                    tts.speak(text, force = true)
                                    FirebaseService.logOcr(text)
                                }
                                btnRead.isEnabled = true
                                btnRead.text      = "🔊  Read Text Aloud"
                            }
                        }
                        .addOnFailureListener {
                            activity?.runOnUiThread {
                                tvResult.text     = "Failed to read text.\nPlease try again."
                                tvCharCount.text  = ""
                                tvCameraHint.text = "📷  Align text within frame"
                                btnRead.isEnabled = true
                                btnRead.text      = "🔊  Read Text Aloud"
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }

                override fun onError(exception: ImageCaptureException) {
                    activity?.runOnUiThread {
                        tvResult.text     = "Camera error. Please try again."
                        tvCameraHint.text = "📷  Align text within frame"
                        btnRead.isEnabled = true
                        btnRead.text      = "🔊  Read Text Aloud"
                    }
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        tts.shutdown()
    }
}