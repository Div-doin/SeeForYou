package com.example.seeforyou.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.PriorityQueue
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Smart audio queue that handles both navigation and object detection speech.
 * Ensures danger alerts always interrupt lower-priority speech.
 * Prevents overlapping speech.
 */
class AudioPriorityQueue(context: Context) {

    enum class Priority(val level: Int) {
        DANGER(0),      // immediate obstacle — interrupts everything
        WARNING(1),     // close obstacle — speaks after current word
        NAVIGATION(2),  // turn instructions
        CAUTION(3),     // medium distance obstacle
        INFO(4)         // general route info
    }

    data class AudioMessage(
        val text: String,
        val priority: Priority,
        val id: String = System.currentTimeMillis().toString()
    ) : Comparable<AudioMessage> {
        override fun compareTo(other: AudioMessage): Int =
            this.priority.level.compareTo(other.priority.level)
    }

    private var tts: TextToSpeech? = null
    private val isSpeaking = AtomicBoolean(false)
    private val queue = PriorityQueue<AudioMessage>()
    private var isInitialized = false

    // Track last spoken text per priority to avoid repetition
    private val lastSpoken = mutableMapOf<Priority, String>()
    private val lastSpokenTime = mutableMapOf<Priority, Long>()

    // Cooldown per priority in milliseconds
    private val cooldowns = mapOf(
        Priority.DANGER     to 2000L,
        Priority.WARNING    to 3000L,
        Priority.NAVIGATION to 5000L,
        Priority.CAUTION    to 4000L,
        Priority.INFO       to 8000L
    )

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                isInitialized = true

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        isSpeaking.set(false)
                        processNext()
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking.set(false)
                        processNext()
                    }
                })
            }
        }
    }

    /**
     * Add message to queue with priority.
     * DANGER messages flush queue and speak immediately.
     */
    fun speak(text: String, priority: Priority) {
        if (!isInitialized) return

        // Check cooldown — don't repeat same message too fast
        val now = System.currentTimeMillis()
        val lastText = lastSpoken[priority]
        val lastTime = lastSpokenTime[priority] ?: 0L
        val cooldown = cooldowns[priority] ?: 3000L

        if (lastText == text && (now - lastTime) < cooldown) return

        val message = AudioMessage(text, priority)

        if (priority == Priority.DANGER) {
            // Danger: clear queue, interrupt current speech, speak now
            synchronized(queue) { queue.clear() }
            tts?.stop()
            isSpeaking.set(false)
            speakNow(message)
        } else {
            synchronized(queue) { queue.offer(message) }
            if (!isSpeaking.get()) {
                processNext()
            }
        }
    }

    private fun processNext() {
        val next = synchronized(queue) { queue.poll() } ?: return
        speakNow(next)
    }

    private fun speakNow(message: AudioMessage) {
        if (!isInitialized) return
        isSpeaking.set(true)
        lastSpoken[message.priority] = message.text
        lastSpokenTime[message.priority] = System.currentTimeMillis()
        tts?.speak(message.text, TextToSpeech.QUEUE_FLUSH, null, message.id)
    }

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
    }

    fun setLanguage(locale: Locale) {
        tts?.language = locale
    }

    fun stop() {
        tts?.stop()
        synchronized(queue) { queue.clear() }
        isSpeaking.set(false)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}