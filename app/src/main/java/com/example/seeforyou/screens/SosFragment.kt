package com.example.seeforyou.screens

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService
import com.example.seeforyou.services.TtsService

class SosFragment : Fragment() {

    private lateinit var btnSos: CardView
    private lateinit var tvStatus: TextView
    private lateinit var tvLocation: TextView
    private lateinit var cardLocation: CardView
    private lateinit var pulseOuter: View
    private lateinit var pulseInner: View
    private lateinit var tts: TtsService

    private var pulseAnimator: AnimatorSet? = null
    private var isSending = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sos, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnSos       = view.findViewById(R.id.btn_sos)
        tvStatus     = view.findViewById(R.id.tv_sos_status)
        tvLocation   = view.findViewById(R.id.tv_location)
        cardLocation = view.findViewById(R.id.card_location)
        pulseOuter   = view.findViewById(R.id.pulse_ring_outer)
        pulseInner   = view.findViewById(R.id.pulse_ring_inner)
        tts          = TtsService(requireContext())

        startIdlePulse()
        btnSos.setOnClickListener { if (!isSending) sendSos() }
    }

    // ─── Pulse Animations ────────────────────────────────────────────────────

    private fun startIdlePulse() {
        pulseAnimator?.cancel()

        val outerScale = createPulseAnimator(pulseOuter, 1f, 1.15f, 1400)
        val innerScale = createPulseAnimator(pulseInner, 1f, 1.10f, 1400)
        innerScale.startDelay = 200

        pulseAnimator = AnimatorSet().apply {
            playTogether(outerScale, innerScale)
            start()
        }
    }

    private fun startSendingPulse() {
        pulseAnimator?.cancel()

        val outerScale = createPulseAnimator(pulseOuter, 1f, 1.25f, 600)
        val innerScale = createPulseAnimator(pulseInner, 1f, 1.18f, 600)
        innerScale.startDelay = 100

        pulseAnimator = AnimatorSet().apply {
            playTogether(outerScale, innerScale)
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseOuter.scaleX = 1f
        pulseOuter.scaleY = 1f
        pulseInner.scaleX = 1f
        pulseInner.scaleY = 1f
    }

    private fun createPulseAnimator(
        target: View,
        from: Float,
        to: Float,
        duration: Long
    ): AnimatorSet {
        val scaleX = ObjectAnimator.ofFloat(target, "scaleX", from, to).apply {
            this.duration = duration
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(target, "scaleY", from, to).apply {
            this.duration = duration
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        return AnimatorSet().apply { playTogether(scaleX, scaleY) }
    }

    // ─── SOS Logic ───────────────────────────────────────────────────────────

    private fun sendSos() {
        isSending = true
        tvStatus.text = "Getting your location..."
        cardLocation.visibility = View.GONE
        startSendingPulse()
        tts.speak("Sending SOS alert now", force = true)

        val hasLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            tvStatus.text = "Location permission needed.\nPlease allow location and try again."
            isSending = false
            startIdlePulse()
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    FirebaseService.sendSosAlert(location.latitude, location.longitude)

                    tvStatus.text = "✅  SOS Alert Sent Successfully"

                    // Show location card
                    tvLocation.text =
                        "Latitude:   ${String.format("%.6f", location.latitude)}\n" +
                                "Longitude: ${String.format("%.6f", location.longitude)}"
                    cardLocation.visibility = View.VISIBLE

                    tts.speak("SOS alert sent. Help is on the way.", force = true)
                    stopPulse()
                    isSending = false

                    // Re-enable after 10 seconds
                    btnSos.postDelayed({
                        tvStatus.text = "Ready to send SOS alert"
                        cardLocation.visibility = View.GONE
                        startIdlePulse()
                    }, 10000)

                } else {
                    tvStatus.text = "Could not get location.\nPlease try again."
                    isSending = false
                    startIdlePulse()
                }
            }
            .addOnFailureListener {
                tvStatus.text = "Failed to send SOS.\nCheck location settings."
                isSending = false
                startIdlePulse()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        tts.shutdown()
    }
}