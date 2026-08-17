package com.example.seeforyou.screens

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.example.seeforyou.R
import com.example.seeforyou.services.TtsService
import java.util.Locale

class SettingsFragment : Fragment() {

    private lateinit var sbVolume: SeekBar
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvVolumeValue: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var spinnerLang: Spinner
    private lateinit var btnSave: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var tts: TtsService

    private val languages = listOf(
        Pair("English (India)", Locale("en", "IN")),
        Pair("English (US)",    Locale.US),
        Pair("Kannada",         Locale("kn", "IN")),
        Pair("Hindi",           Locale("hi", "IN"))
    )

    private val speedLabels = listOf(
        "Very Slow", "Slow", "Slow", "Moderate", "Moderate",
        "Normal", "Normal", "Fast", "Fast", "Very Fast", "Very Fast"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sbVolume      = view.findViewById(R.id.sb_volume)
        sbSpeed       = view.findViewById(R.id.sb_speed)
        tvVolumeValue = view.findViewById(R.id.tv_volume_value)
        tvSpeedValue  = view.findViewById(R.id.tv_speed_value)
        spinnerLang   = view.findViewById(R.id.spinner_language)
        btnSave       = view.findViewById(R.id.btn_save_settings)
        btnTest       = view.findViewById(R.id.btn_test_voice)
        tts           = TtsService(requireContext())

        // Language spinner
        val langNames = languages.map { it.first }
        spinnerLang.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            langNames
        )

        // Load saved settings
        val prefs = requireContext()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        sbVolume.progress = prefs.getInt("volume", 80)
        sbSpeed.progress  = prefs.getInt("speed", 5)
        spinnerLang.setSelection(prefs.getInt("lang_index", 0))

        // Set initial value labels
        tvVolumeValue.text = "${sbVolume.progress}%"
        tvSpeedValue.text  = speedLabels[sbSpeed.progress]

        // Live update volume label
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolumeValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Live update speed label
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSpeedValue.text = speedLabels[progress]
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Save button
        btnSave.setOnClickListener {
            prefs.edit()
                .putInt("volume",     sbVolume.progress)
                .putInt("speed",      sbSpeed.progress)
                .putInt("lang_index", spinnerLang.selectedItemPosition)
                .apply()

            tts.setSpeed(sbSpeed.progress / 10f + 0.1f)
            tts.setLanguage(languages[spinnerLang.selectedItemPosition].second)
            tts.speak("Settings saved", force = true)

            btnSave.text = "✅  Saved!"
            btnSave.postDelayed({ btnSave.text = "✅  Save Settings" }, 2000)
        }

        // Test button
        btnTest.setOnClickListener {
            tts.setSpeed(sbSpeed.progress / 10f + 0.1f)
            tts.setLanguage(languages[spinnerLang.selectedItemPosition].second)
            tts.speak(
                "Hello. I can see a person ahead of you, one meter away.",
                force = true
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts.shutdown()
    }
}