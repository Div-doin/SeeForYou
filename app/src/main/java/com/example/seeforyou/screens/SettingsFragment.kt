package com.example.seeforyou.screens

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.seeforyou.R
import com.example.seeforyou.localization.AppLanguage
import com.example.seeforyou.localization.PhraseBook
import com.example.seeforyou.services.TtsService
import java.util.Locale

class SettingsFragment : Fragment() {
    private lateinit var tts: TtsService
    private var checkGeneration = 0
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val volume = view.findViewById<SeekBar>(R.id.sb_volume)
        val speed = view.findViewById<SeekBar>(R.id.sb_speed)
        val volumeValue = view.findViewById<TextView>(R.id.tv_volume_value)
        val speedValue = view.findViewById<TextView>(R.id.tv_speed_value)
        val language = view.findViewById<Spinner>(R.id.spinner_language)
        val status = view.findViewById<TextView>(R.id.tv_voice_status)
        val prefs = AppLanguage.prefs(requireContext())
        tts = TtsService(requireContext())
        language.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, AppLanguage.names)
        language.setSelection(AppLanguage.tags.indexOf(AppLanguage.tag(requireContext())))
        volume.progress = prefs.getInt("volume", 80).coerceIn(0, 100)
        speed.progress = prefs.getInt("speed", 5).coerceIn(0, 10)
        fun labels() {
            volumeValue.text = "${volume.progress}%"
            speedValue.setText(when (speed.progress) {
                0 -> R.string.speed_very_slow
                1, 2 -> R.string.speed_slow
                3, 4 -> R.string.speed_moderate
                5, 6 -> R.string.speed_normal
                7, 8 -> R.string.speed_fast
                else -> R.string.speed_very_fast
            })
        }
        labels()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, value: Int, user: Boolean) = labels()
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        }
        volume.setOnSeekBarChangeListener(listener)
        speed.setOnSeekBarChangeListener(listener)
        fun savePreferences(tag: String) {
            prefs.edit().putString("language_tag", tag)
                .putInt("lang_index", AppLanguage.tags.indexOf(tag))
                .putInt("speed", speed.progress).putInt("volume", volume.progress).apply()
        }
        fun check(preview: Boolean) {
            val generation = ++checkGeneration
            val tag = AppLanguage.tags[language.selectedItemPosition]
            tts.setSpeed(speed.progress / 10f + 0.1f)
            tts.setVolume(volume.progress / 100f)
            status.setText(R.string.voice_loading)
            tts.checkVoice(Locale.forLanguageTag(tag)) { result ->
                if (generation == checkGeneration && this.view != null && !isHidden) {
                    if (result < TextToSpeech.LANG_AVAILABLE) {
                        status.setText(R.string.voice_missing)
                        Toast.makeText(requireContext(), R.string.voice_missing, Toast.LENGTH_LONG).show()
                    } else {
                        status.setText(R.string.voice_ready)
                        if (preview) tts.speak(PhraseBook(tag).text("voice_sample"), true)
                    }
                }
            }
        }
        language.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) {
                val tag = AppLanguage.tags[position]
                if (tag != AppLanguage.tag(requireContext())) {
                    // Apply the screen language even when voice data is missing or still loading.
                    checkGeneration++
                    tts.stop()
                    savePreferences(tag)
                    requireActivity().recreate()
                } else check(false)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        view.findViewById<View>(R.id.btn_test_voice).setOnClickListener { check(true) }
        view.findViewById<View>(R.id.btn_save_settings).setOnClickListener {
            savePreferences(AppLanguage.tags[language.selectedItemPosition])
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            check(false)
        }
        view.findViewById<View>(R.id.btn_voice_settings).setOnClickListener {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (hidden) { checkGeneration++; tts.stop() } }
    override fun onDestroyView() { checkGeneration++; tts.shutdown(); super.onDestroyView() }
}
