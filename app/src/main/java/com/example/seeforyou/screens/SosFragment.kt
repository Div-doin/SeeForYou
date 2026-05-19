package com.example.seeforyou.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService
import com.example.seeforyou.services.TtsService

class SosFragment : Fragment() {

    private lateinit var btnSos: Button
    private lateinit var tvStatus: TextView
    private lateinit var tts: TtsService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sos, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnSos   = view.findViewById(R.id.btn_sos)
        tvStatus = view.findViewById(R.id.tv_sos_status)
        tts      = TtsService(requireContext())

        btnSos.setOnClickListener { sendSos() }
    }

    private fun sendSos() {
        btnSos.isEnabled = false
        tvStatus.text    = "Getting your location..."
        tts.speak("Sending SOS alert now", force = true)

        val hasLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                102
            )
            tvStatus.text    = "Location permission needed.\nPlease allow location and try again."
            btnSos.isEnabled = true
            return
        }

        val fusedClient = LocationServices
            .getFusedLocationProviderClient(requireActivity())

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    FirebaseService.sendSosAlert(
                        location.latitude,
                        location.longitude
                    )
                    tvStatus.text =
                        "SOS sent!\n\n" +
                                "Lat: ${String.format("%.5f", location.latitude)}\n" +
                                "Lng: ${String.format("%.5f", location.longitude)}"
                    tts.speak("SOS alert sent. Help is on the way.", force = true)
                } else {
                    tvStatus.text    = "Could not get location.\nPlease try again."
                    btnSos.isEnabled = true
                }
            }
            .addOnFailureListener {
                tvStatus.text    = "Failed to send SOS.\nCheck location settings."
                btnSos.isEnabled = true
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts.shutdown()
    }
}