package com.example.seeforyou.utils

import android.app.Activity
import com.example.seeforyou.R
import com.example.seeforyou.localization.AppLanguage
import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import com.example.seeforyou.voice.SpeechGate

/**
 * Handles speech-to-text input for navigation destination.
 * Uses Android built-in SpeechRecognizer — no API key needed.
 */
class SpeechInputHelper(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListening: () -> Unit,
    private val multilingual: Boolean = false,
    private val onAlternatives: ((List<String>) -> Unit)? = null,
    private val diagnostic: com.example.seeforyou.voice.VoiceDiagnostic? = null
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var acceptingResults = false
    private var automaticSwitchUnavailable = false
    private var usingAutomaticSwitch = false
    private var installedLanguages = emptyList<String>()
    private var onDevice = false
    private var sessionStartedAt = 0L
    private var ready = false
    private var speechBegan = false
    private var rmsSamples = 0
    private var peakRms: Float? = null
    private var closed = false
    private var reconnectNeeded = false
    private var recognizerGeneration = 0

    // Timing and signal levels only: never retain audio or recognized words in diagnostics.
    private fun logSession(event: String) {
        android.util.Log.i("SeeForYouSpeech", "$event elapsedMs=${android.os.SystemClock.elapsedRealtime() - sessionStartedAt} ready=$ready speechBegan=$speechBegan rmsSamples=$rmsSamples peakRms=$peakRms")
    }

    init { initializeRecognizer() }

    private fun initializeRecognizer() {
        if (closed) return
        val generation = ++recognizerGeneration
        reconnectNeeded = false
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            onDevice = multilingual && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            speechRecognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            android.util.Log.i("SeeForYouSpeech", "onDevice=$onDevice language=${AppLanguage.tag(context)}")
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    if (generation == recognizerGeneration && acceptingResults) {
                        ready = true
                        logSession("ready")
                        onListening()
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    if (generation != recognizerGeneration || !acceptingResults) return
                    logSession("results")
                    acceptingResults = false
                    SpeechGate.set(this@SpeechInputHelper, false)
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val text = matches?.firstOrNull()
                    android.util.Log.i("SeeForYouSpeech", "result alternatives=${matches?.size ?: 0}")
                    if (!text.isNullOrBlank()) {
                        if (onAlternatives != null) onAlternatives.invoke(matches.orEmpty()) else onResult(text)
                    } else {
                        if (usingAutomaticSwitch) automaticSwitchUnavailable = true
                        onError(context.getString(if (multilingual) R.string.voice_no_transcript else R.string.speech_no_match))
                    }
                }

                override fun onError(error: Int) {
                    if (generation != recognizerGeneration || !acceptingResults) return
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT)
                        reconnectNeeded = true
                    logSession("error=$error")
                    android.util.Log.w("SeeForYouSpeech", "error=$error automaticSwitch=$usingAutomaticSwitch")
                    if (usingAutomaticSwitch && error == SpeechRecognizer.ERROR_NO_MATCH) automaticSwitchUnavailable = true
                    if (usingAutomaticSwitch && error in listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                        acceptingResults = false
                        automaticSwitchUnavailable = true
                        SpeechGate.set(this@SpeechInputHelper, false)
                        onError(context.getString(R.string.voice_language_retry))
                        return
                    }
                    acceptingResults = false
                    SpeechGate.set(this@SpeechInputHelper, false)
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH        -> context.getString(if (multilingual) R.string.voice_no_transcript else R.string.speech_no_match)
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> context.getString(R.string.speech_timeout)
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.speech_network)
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> context.getString(R.string.voice_language_retry)
                        SpeechRecognizer.ERROR_AUDIO           -> context.getString(R.string.speech_audio)
                        else                                   -> context.getString(R.string.speech_error)
                    }
                    onError(message)
                }

                override fun onBeginningOfSpeech() {
                    if (generation == recognizerGeneration && acceptingResults) { speechBegan = true; logSession("speechBegin") }
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (generation == recognizerGeneration && acceptingResults && rmsdB.isFinite()) {
                        rmsSamples++
                        peakRms = maxOf(peakRms ?: rmsdB, rmsdB)
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (acceptingResults) logSession("speechEnd") }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            if (onDevice && Build.VERSION.SDK_INT >= 33) {
                val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppLanguage.tag(context))
                speechRecognizer?.checkRecognitionSupport(request, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (closed || generation != recognizerGeneration) return
                        installedLanguages = support.installedOnDeviceLanguages.filter { it in listOf("en-IN", "en-US", "hi-IN", "kn-IN") }
                        android.util.Log.i("SeeForYouSpeech", "installedLanguages=$installedLanguages")
                    }
                    override fun onError(error: Int) {
                        if (closed || generation != recognizerGeneration) return
                        if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) reconnectNeeded = true
                        android.util.Log.w("SeeForYouSpeech", "supportError=$error")
                    }
                })
            }
        }
    }

    /**
     * Start listening for destination speech input.
     * Prompts user with a question before listening.
     */
    fun startListening() {
        if (closed || acceptingResults) return
        if (reconnectNeeded) {
            recognizerGeneration++
            speechRecognizer?.destroy()
            speechRecognizer = null
            android.util.Log.i("SeeForYouSpeech", "reconnectingRecognizer")
            initializeRecognizer()
        }
        if (speechRecognizer == null) {
            onError(context.getString(R.string.speech_unavailable))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, diagnostic?.language ?: AppLanguage.tag(context))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, diagnostic?.language ?: AppLanguage.tag(context))
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, if (multilingual) 5 else 1)
            // Let the command recognizer choose its endpoint. On the test phone,
            // this override produced empty results; removing it recognized the command.
            // Retain the old value only for navigation and explicit comparison profiles.
            if (!multilingual || (diagnostic != null && diagnostic != com.example.seeforyou.voice.VoiceDiagnostic.TIMING))
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            usingAutomaticSwitch = multilingual && Build.VERSION.SDK_INT >= 34 &&
                (diagnostic?.autoSwitch ?: !automaticSwitchUnavailable) && installedLanguages.size >= 2
            if (usingAutomaticSwitch) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    ArrayList(installedLanguages))
            }
        }
        acceptingResults = true
        sessionStartedAt = android.os.SystemClock.elapsedRealtime()
        ready = false
        speechBegan = false
        rmsSamples = 0
        peakRms = null
        logSession("start diagnostic=$diagnostic onDevice=$onDevice automaticSwitch=$usingAutomaticSwitch language=${diagnostic?.language ?: AppLanguage.tag(context)}")
        SpeechGate.set(this, true)
        try { speechRecognizer?.startListening(intent) }
        catch (_: RuntimeException) {
            reconnectNeeded = true
            acceptingResults = false
            SpeechGate.set(this, false)
            onError(context.getString(R.string.speech_error))
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun cancel() {
        val wasListening = acceptingResults
        acceptingResults = false
        SpeechGate.set(this, false)
        if (wasListening) {
            speechRecognizer?.cancel()
            reconnectNeeded = true
        }
    }

    fun destroy() {
        closed = true
        recognizerGeneration++
        acceptingResults = false
        SpeechGate.set(this, false)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
