package com.example.seeforyou.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.example.seeforyou.R
import com.example.seeforyou.localization.AppLanguage
import java.util.Locale
import android.speech.tts.UtteranceProgressListener
import com.example.seeforyou.voice.SpeechGate

class TtsService(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var failed = false
    private var lastSpoken = ""
    private var pending: Triple<String, Boolean, Locale?>? = null
    private var pendingCheck: ((Int) -> Unit)? = null
    private var locale = AppLanguage.locale(context)
    private var speed = AppLanguage.speed(context)
    private var volume = AppLanguage.volume(context)
    private val main = Handler(Looper.getMainLooper())
    private var sequence = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { status -> main.post {
            if (!closed) {
                initialized = status == TextToSpeech.SUCCESS
                failed = !initialized
                if (initialized) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post {
                        if (id == sequence.toString()) {
                            SpeechGate.set(this@TtsService, false)
                            android.util.Log.i("SeeForYouSpeech", "ttsFinished")
                        }
                    } }
                    override fun onStop(id: String?, interrupted: Boolean) = onDone(id)
                    override fun onError(id: String?, errorCode: Int) = onDone(id)
                    @Deprecated("Legacy callback")
                    override fun onError(id: String?) = onDone(id)
                })
                val check = pendingCheck
                pendingCheck = null
                check?.invoke(if (initialized) configure() else TextToSpeech.ERROR)
                val next = pending
                pending = null
                next?.let { speak(it.first, it.second, it.third) }
            }
        } }
    }
    private fun configure(selected: Locale = locale): Int {
        tts?.setSpeechRate(speed)
        return tts?.setLanguage(selected) ?: TextToSpeech.ERROR
    }
    fun checkVoice(selected: Locale, callback: (Int) -> Unit) {
        locale = selected
        if (initialized) callback(configure())
        else if (failed) callback(TextToSpeech.ERROR)
        else pendingCheck = callback
    }
    fun speak(text: String, force: Boolean = false, language: Locale? = null) {
        if (closed) return
        if (!initialized) {
            if (!failed) {
                pending = Triple(text, force, language)
                SpeechGate.set(this, true)
            } else {
                SpeechGate.set(this, false)
                Toast.makeText(context, R.string.voice_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (configure(language ?: locale) < TextToSpeech.LANG_AVAILABLE) {
            SpeechGate.set(this, false)
            Toast.makeText(context, R.string.voice_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (text == lastSpoken && !force) return
        lastSpoken = text
        SpeechGate.set(this, true)
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, AppLanguage.speechParams(volume),
                (++sequence).toString()) == TextToSpeech.ERROR) SpeechGate.set(this, false)
    }
    fun setSpeed(value: Float) { speed = value }
    fun setLanguage(value: Locale) { locale = value }
    fun setVolume(value: Float) { volume = value }
    fun stop() { pending = null; sequence++; tts?.stop(); SpeechGate.set(this, false) }
    fun shutdown() {
        closed = true
        SpeechGate.set(this, false)
        pending = null
        pendingCheck = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
