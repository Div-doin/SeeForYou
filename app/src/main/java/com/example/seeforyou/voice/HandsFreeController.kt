package com.example.seeforyou.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.example.seeforyou.R
import com.example.seeforyou.utils.SpeechInputHelper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** Local wake listener only while the activity is foreground and the user has enabled it. */
class HandsFreeController(
    private val context: Context,
    private val status: (String) -> Unit,
    private val command: (VoiceCommand) -> Unit,
    private val feedback: (String) -> Unit,
    private val heard: (String) -> Unit = {},
    private val diagnostic: VoiceDiagnostic? = null
) {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var closed = false
    private var foreground = false
    private var enabled = false
    private var commanding = false
    private var generation = 0
    private var service: SpeechService? = null
    private var decoder: Recognizer? = null
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    private val timeout: Runnable = Runnable {
        if (commanding) {
            android.util.Log.i("SeeForYouSpeech", "commandTimeout")
            commanding = false
            input.cancel()
            feedback(context.getString(R.string.voice_command_retry))
            refresh()
        }
    }
    private val input: SpeechInputHelper = SpeechInputHelper(context,
        onResult = {},
        onAlternatives = { texts ->
            if (commanding && foreground && enabled) {
                commanding = false
                main.removeCallbacks(timeout)
                val text = texts.firstOrNull().orEmpty().take(200)
                heard(context.getString(R.string.voice_heard, text))
                val parsed = VoiceCommandParser.parseAlternatives(texts)
                if (parsed != null && diagnostic != null) {
                    android.util.Log.i("SeeForYouSpeech", "diagnostic=$diagnostic commandMatched=true")
                    feedback("Test command recognized.")
                }
                else if (parsed != null) command(parsed)
                else feedback(context.getString(R.string.voice_command_unknown_heard, text))
                refresh()
            }
        },
        onError = {
            if (commanding) {
                commanding = false
                main.removeCallbacks(timeout)
                feedback(it)
                refresh()
            }
        },
        onListening = {
            heard("")
            status(context.getString(R.string.voice_command_listening))
            if (diagnostic != VoiceDiagnostic.NO_BEEP) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }, multilingual = true, diagnostic = diagnostic)
    private val gateChanged: () -> Unit = { refresh() }
    init { SpeechGate.add(gateChanged) }

    fun setEnabled(value: Boolean) { enabled = value; refresh() }
    fun setForeground(value: Boolean) { foreground = value; refresh() }

    private fun refresh() {
        if (closed) return
        if (!enabled || !foreground) {
            commanding = false
            main.removeCallbacksAndMessages(null)
            stopWake()
            input.cancel()
            status(context.getString(R.string.voice_off))
            return
        }
        if (commanding) return
        if (SpeechGate.busy) {
            stopWake()
            status(context.getString(R.string.voice_paused))
            return
        }
        if (model == null) {
            if (loading) return
            loading = true
            status(context.getString(R.string.voice_loading_model))
            StorageService.unpack(context, "vosk-model-small-en-us-0.15", "wake-model",
                { loaded ->
                    loading = false
                    if (closed) loaded.close() else { model = loaded; refresh() }
                },
                {
                    loading = false
                    if (!closed) {
                        enabled = false
                        status(context.getString(R.string.voice_wake_error))
                    }
                })
            return
        }
        if (service != null) return
        val token = ++generation
        main.postDelayed({
            if (token == generation && !closed && foreground && enabled && !commanding && !SpeechGate.busy) startWake()
        }, 450)
    }

    private fun startWake() {
        try {
            val token = ++generation
            decoder = Recognizer(model, 16000f, """["hey see for you", "[unk]"]""")
            service = SpeechService(decoder, 16000f)
            service!!.startListening(object : RecognitionListener {
                private fun heard(json: String) {
                    if (token != generation || !foreground || !enabled || commanding || SpeechGate.busy) return
                    val result = runCatching { JSONObject(json) }.getOrNull() ?: return
                    val text = result.optString("text", result.optString("partial"))
                    if (VoiceCommandParser.normalize(text) == "hey see for you") {
                        android.util.Log.i("SeeForYouSpeech", "wakeDetected")
                        commanding = true
                        stopWake()
                        android.util.Log.i("SeeForYouSpeech", "wakeMicrophoneReleased")
                        status(context.getString(R.string.voice_command_starting))
                        main.postDelayed({
                            if (commanding && foreground && enabled && !closed) {
                                input.startListening()
                                main.postDelayed(timeout, 15000)
                            }
                        }, 150)
                    }
                }
                override fun onPartialResult(hypothesis: String) = heard(hypothesis)
                override fun onResult(hypothesis: String) = heard(hypothesis)
                override fun onFinalResult(hypothesis: String) = heard(hypothesis)
                override fun onTimeout() {}
                override fun onError(exception: Exception) {
                    if (token != generation) return
                    stopWake()
                    enabled = false
                    status(context.getString(R.string.voice_wake_error))
                }
            })
            status(context.getString(R.string.voice_ready_wake))
        } catch (_: Exception) {
            stopWake()
            enabled = false
            status(context.getString(R.string.voice_wake_error))
        }
    }

    private fun stopWake() {
        generation++
        service?.cancel()
        service?.shutdown()
        service = null
        decoder?.close()
        decoder = null
    }
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        SpeechGate.remove(gateChanged)
        input.destroy()
        stopWake()
        tone.release()
        model?.close()
        model = null
    }
}
