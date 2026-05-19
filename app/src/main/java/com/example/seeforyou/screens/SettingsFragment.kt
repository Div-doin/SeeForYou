package com.example.seeforyou.screens

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.seeforyou.R
import com.example.seeforyou.services.TtsService
import java.util.Locale

class SettingsFragment : Fragment() {

    private lateinit var sbVolume: SeekBar
    private lateinit var sbSpeed: SeekBar
    private lateinit var spinnerLang: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var tts: TtsService

    private val languages = listOf(
        Pair("English (India)", Locale("en", "IN")),
        Pair("English (US)",    Locale.US),
        Pair("Kannada",         Locale("kn", "IN")),
        Pair("Hindi",           Locale("hi", "IN"))
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sbVolume    = view.findViewById(R.id.sb_volume)
        sbSpeed     = view.findViewById(R.id.sb_speed)
        spinnerLang = view.findViewById(R.id.spinner_language)
        btnSave     = view.findViewById(R.id.btn_save_settings)
        btnTest     = view.findViewById(R.id.btn_test_voice)
        tts         = TtsService(requireContext())

        // Populate language dropdown
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

        btnSave.setOnClickListener {
            // Save to shared preferences
            prefs.edit()
                .putInt("volume",     sbVolume.progress)
                .putInt("speed",      sbSpeed.progress)
                .putInt("lang_index", spinnerLang.selectedItemPosition)
                .apply()

            // Apply to TTS immediately
            tts.setSpeed(sbSpeed.progress / 10f + 0.1f)
            tts.setLanguage(languages[spinnerLang.selectedItemPosition].second)
            tts.speak("Settings saved", force = true)

            Toast.makeText(requireContext(), "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            tts.speak(
                "Hello, I can see a person ahead of you.",
                force = true
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts.shutdown()
    }
}