package com.example.seeforyou

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.seeforyou.localization.AppLanguage
import com.example.seeforyou.utils.YoloDetector
import com.example.seeforyou.voice.VoiceCommand
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraLanguageRegressionTest {
    @Test fun detectorCanCloseWhileFramesAreQueued() {
        val detector = YoloDetector(InstrumentationRegistry.getInstrumentation().targetContext)
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val frames = worker.submit { repeat(6) { detector.detect(bitmap) } }
            Thread.sleep(100)
            detector.close()
            frames.get(20, TimeUnit.SECONDS)
            assertTrue(detector.detect(bitmap).isEmpty())
            assertNull(detector.detectWithMotion(bitmap))
            detector.close()
        } finally {
            worker.shutdown()
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS))
            detector.close()
            bitmap.recycle()
        }
    }

    @Test fun voiceLanguageChangesWhileCameraIsRunning() {
        val prefs = AppLanguage.prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = prefs.getString("language_tag", "en-IN")
        val index = prefs.getInt("lang_index", 0)
        val announce = prefs.getBoolean("announce_language", false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                listOf("en-IN", "hi-IN", "en-IN", "hi-IN").forEach { tag ->
                    Thread.sleep(1500) // Allow live camera inference before rebuilding the screen.
                    scenario.onActivity { it.handleVoiceCommand(VoiceCommand.Language(tag)) }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    Thread.sleep(1500)
                    scenario.onActivity {
                        assertFalse(it.isFinishing)
                        assertEquals(tag, AppLanguage.tag(it))
                        assertEquals(tag.substringBefore('-'), it.resources.configuration.locales[0].language)
                    }
                }
            }
        } finally {
            prefs.edit().putString("language_tag", original).putInt("lang_index", index)
                .putBoolean("announce_language", announce).commit()
        }
    }
}
