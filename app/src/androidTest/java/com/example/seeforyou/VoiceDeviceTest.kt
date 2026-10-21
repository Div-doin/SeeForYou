package com.example.seeforyou

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.example.seeforyou.voice.SpeechGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import android.widget.TextView
import com.example.seeforyou.localization.AppLanguage

@RunWith(AndroidJUnit4::class)
class VoiceDeviceTest {
    @Test fun foregroundWakeListenerStartsAndStopsWithApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        val prefs = AppLanguage.prefs(context)
        val wasEnabled = prefs.getBoolean("hands_free", false)
        try {
            prefs.edit().putBoolean("hands_free", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = System.currentTimeMillis() + 45000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        ready = it.findViewById<TextView>(R.id.voice_status).text.toString() == it.getString(R.string.voice_ready_wake)
                    }
                    if (!ready) Thread.sleep(250)
                }
                assertTrue("Wake microphone did not start", ready)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity {
                    assertEquals(it.getString(R.string.voice_off),
                        it.findViewById<TextView>(R.id.voice_status).text.toString())
                }
            }
        } finally { prefs.edit().putBoolean("hands_free", wasEnabled).commit() }
    }

    @Test fun bundledWakeModelLoadsAndSilenceDoesNotWake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        var model: Model? = null
        var error: Exception? = null
        instrumentation.runOnMainSync {
            StorageService.unpack(instrumentation.targetContext, "vosk-model-small-en-us-0.15", "wake-model",
                { model = it; ready.countDown() }, { error = it; ready.countDown() })
        }
        assertTrue("Offline model loading timed out", ready.await(45, TimeUnit.SECONDS))
        assertNull("Model error: $error", error)
        val loaded = requireNotNull(model)
        try {
            Recognizer(loaded, 16000f, """["hey see for you", "[unk]"]""").use { decoder ->
                repeat(20) { decoder.acceptWaveForm(ShortArray(1600), 1600) }
                assertEquals("", JSONObject(decoder.finalResult).optString("text"))
            }
            fun detectsWake(asset: String): Boolean {
                val wave = instrumentation.context.assets.open(asset).use { it.readBytes() }
                val bytes = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
                var offset = 12
                var pcm = ByteArray(0)
                while (offset + 8 <= wave.size) {
                    val name = String(wave, offset, 4, Charsets.US_ASCII)
                    val size = bytes.getInt(offset + 4)
                    require(size >= 0 && offset + 8L + size <= wave.size)
                    if (name == "fmt ") {
                        assertEquals(1, bytes.getShort(offset + 8).toInt())
                        assertEquals(1, bytes.getShort(offset + 10).toInt())
                        assertEquals(16000, bytes.getInt(offset + 12))
                    }
                    if (name == "data") pcm = wave.copyOfRange(offset + 8, offset + 8 + size)
                    offset += 8 + size + (size % 2)
                }
                assertTrue(pcm.isNotEmpty())
                var detected = false
                Recognizer(loaded, 16000f, """["hey see for you", "[unk]"]""").use { decoder ->
                    fun inspect(json: String) {
                        val result = JSONObject(json)
                        if (result.optString("text", result.optString("partial")) == "hey see for you") detected = true
                    }
                    pcm.asList().chunked(3200).forEach { chunk ->
                        val data = chunk.toByteArray()
                        if (decoder.acceptWaveForm(data, data.size)) inspect(decoder.result)
                        else inspect(decoder.partialResult)
                    }
                    inspect(decoder.finalResult)
                }
                return detected
            }
            assertTrue("Synthesized wake phrase should activate", detectsWake("wake-positive.wav"))
            assertFalse("Other speech must not activate", detectsWake("wake-negative.wav"))
        } finally { loaded.close() }
    }

    @Test fun speechGateKeepsWakePausedUntilAllSpeakersFinish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val a = Any()
            val b = Any()
            try {
                SpeechGate.set(a, true)
                SpeechGate.set(b, true)
                SpeechGate.set(a, false)
                assertTrue(SpeechGate.busy)
                SpeechGate.set(b, false)
                assertFalse(SpeechGate.busy)
            } finally { SpeechGate.set(a, false); SpeechGate.set(b, false) }
        }
    }
}
