package com.example.seeforyou.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.widget.Toast
import com.example.seeforyou.R
import com.example.seeforyou.localization.AppLanguage
import com.example.seeforyou.voice.SpeechGate

/** Serialize speech on the main thread; urgent alerts interrupt directions. */
class AudioPriorityQueue(private val context: Context) {
    enum class Priority(val level: Int) {
        DANGER(0), WARNING(1), NAVIGATION(2), CAUTION(3), INFO(4)
    }
    private data class Message(val text: String, val priority: Priority, val id: String,
        val created: Long = SystemClock.elapsedRealtime())
    private val main = Handler(Looper.getMainLooper())
    private val queue = mutableListOf<Message>()
    private var current: Message? = null
    private var sequence = 0L
    private var ready = false
    private var closed = false
    private var engineFailed = false
    @Volatile var paused = false
        private set
    private var lastText = ""
    private var lastTime = 0L
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status -> main.post {
            if (!closed && status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                    @Deprecated("Android legacy callback")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                })
                next()
            } else if (!closed) {
                engineFailed = true
                if (queue.isNotEmpty()) Toast.makeText(context, R.string.voice_failed, Toast.LENGTH_LONG).show()
                queue.clear()
            }
        } }
    }

    private fun finished(id: String?) { main.post {
        if (current?.id == id) { current = null; SpeechGate.set(this, false); next() }
    } }

    fun speak(text: String, priority: Priority) { main.post {
        if (closed || paused) return@post
        if (engineFailed) {
            Toast.makeText(context, R.string.voice_failed, Toast.LENGTH_LONG).show()
            return@post
        }
        val now = SystemClock.elapsedRealtime()
        if (lastText == text && now - lastTime < 3000) return@post
        if (current?.text == text || queue.any { it.text == text }) return@post
        val message = Message(text, priority, (++sequence).toString())
        // Retain only the latest pending message in each category.
        queue.removeAll { it.priority == priority }
        val speaking = current
        val replacesDirection = priority == Priority.NAVIGATION && speaking?.priority == Priority.NAVIGATION
        if (speaking != null && ((priority.level < speaking.priority.level && priority.level <= 2) || replacesDirection)) {
            if (speaking.priority == Priority.NAVIGATION && queue.none { it.priority == Priority.NAVIGATION }) {
                queue.add(speaking.copy(id = (++sequence).toString()))
            }
            current = null
            tts?.stop()
        }
        queue.add(message)
        next()
    } }

    private fun next() {
        if (!ready || closed || paused || current != null) return
        queue.removeAll { SystemClock.elapsedRealtime() - it.created > 15000 }
        val message = queue.minByOrNull { it.priority.level } ?: return
        queue.remove(message)
        if ((tts?.setLanguage(AppLanguage.locale(context)) ?: TextToSpeech.ERROR) < TextToSpeech.LANG_AVAILABLE) {
            queue.clear()
            Toast.makeText(context, R.string.voice_missing, Toast.LENGTH_LONG).show()
            return
        }
        tts?.setSpeechRate(AppLanguage.speed(context))
        current = message
        SpeechGate.set(this, true)
        lastText = message.text
        lastTime = SystemClock.elapsedRealtime()
        if (tts?.speak(message.text, TextToSpeech.QUEUE_FLUSH,
                AppLanguage.speechParams(AppLanguage.volume(context)), message.id) == TextToSpeech.ERROR) {
            current = null
            SpeechGate.set(this, false)
            next()
        }
    }

    fun setSpeed(speed: Float) { main.post { tts?.setSpeechRate(speed) } }
    fun setLanguage(locale: Locale) { main.post { tts?.language = locale } }
    fun stop() { main.post { queue.clear(); current = null; tts?.stop(); SpeechGate.set(this, false) } }
    fun pause() {
        paused = true
        stop()
    }
    fun resume() {
        paused = false
        main.post { lastText = ""; lastTime = 0L }
    }
    fun shutdown() { main.post {
        closed = true
        SpeechGate.set(this, false)
        queue.clear()
        current = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    } }
}
