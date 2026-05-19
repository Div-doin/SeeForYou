package com.example.seeforyou.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsService(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lastSpoken = ""

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                tts?.language = Locale("en", "IN")
                tts?.setSpeechRate(0.9f)
            } else {
                Log.e("TTS", "Initialization failed")
            }
        }
    }

    fun speak(text: String, force: Boolean = false) {
        if (!isReady) return
        if (text == lastSpoken && !force) return
        lastSpoken = text
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "uid_$text")
    }

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
    }

    fun setLanguage(locale: Locale) {
        tts?.language = locale
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
    }
}