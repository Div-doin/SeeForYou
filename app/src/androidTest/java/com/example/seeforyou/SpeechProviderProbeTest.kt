package com.example.seeforyou

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechProviderProbeTest {
    @Test fun downloadHindiModelWhenExplicitlyRequested() {
        if (Build.VERSION.SDK_INT < 34 || InstrumentationRegistry.getArguments().getString("downloadHindi") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val complete = CountDownLatch(1)
        lateinit var recognizer: SpeechRecognizer
        instrumentation.runOnMainSync {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            recognizer.triggerModelDownload(intent, context.mainExecutor, object : android.speech.ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {}
                override fun onSuccess() { android.util.Log.i("SeeForYouSpeechProbe", "Hindi model download complete"); complete.countDown() }
                override fun onScheduled() { android.util.Log.i("SeeForYouSpeechProbe", "Hindi model download scheduled"); complete.countDown() }
                override fun onError(error: Int) { android.util.Log.i("SeeForYouSpeechProbe", "Hindi model download error=$error"); complete.countDown() }
            })
        }
        if (!complete.await(40, TimeUnit.SECONDS)) android.util.Log.i("SeeForYouSpeechProbe", "Hindi model download still pending")
        instrumentation.runOnMainSync { recognizer.destroy() }
    }
    @Test fun reportRecognitionSupportWithoutRecording() {
        if (Build.VERSION.SDK_INT < 33) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        android.util.Log.i("SeeForYouSpeechProbe", "onDeviceAvailable=${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
        val services = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
            .filter { it.serviceInfo.packageName in listOf("com.google.android.as", "com.google.android.tts") }
        for (service in services) {
            val complete = CountDownLatch(1)
            lateinit var recognizer: SpeechRecognizer
            instrumentation.runOnMainSync {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context,
                    ComponentName(service.serviceInfo.packageName, service.serviceInfo.name))
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        android.util.Log.i("SeeForYouSpeechProbe", "${service.serviceInfo.packageName}: installed=${support.installedOnDeviceLanguages} pending=${support.pendingOnDeviceLanguages} available=${support.supportedOnDeviceLanguages} online=${support.onlineLanguages}")
                        complete.countDown()
                    }
                    override fun onError(error: Int) {
                        android.util.Log.i("SeeForYouSpeechProbe", "${service.serviceInfo.packageName}: supportError=$error")
                        complete.countDown()
                    }
                })
            }
            if (!complete.await(10, TimeUnit.SECONDS)) android.util.Log.i("SeeForYouSpeechProbe", "${service.serviceInfo.packageName}: timeout")
            instrumentation.runOnMainSync { recognizer.destroy() }
        }
    }
}
