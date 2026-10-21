package com.example.seeforyou

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.seeforyou.localization.AppLanguage
import com.example.seeforyou.screens.OcrFragment
import com.example.seeforyou.voice.SpeechGate
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test

class ReadingVoiceRecoveryTest {
    @Test fun wakeReturnsAfterReadingFinishesAndAfterStop() {
        val prefs = AppLanguage.prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        val enabled = prefs.getBoolean("hands_free", false)
        try {
            prefs.edit().putBoolean("hands_free", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun awaitWake() {
                    val deadline = System.currentTimeMillis() + 45000
                    var ready = false
                    while (!ready && System.currentTimeMillis() < deadline) {
                        scenario.onActivity {
                            ready = !SpeechGate.busy && it.findViewById<TextView>(R.id.voice_status).text.toString() == it.getString(R.string.voice_ready_wake)
                        }
                        if (!ready) Thread.sleep(200)
                    }
                    assertTrue("Wake listener did not recover after reading", ready)
                }
                scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_ocr }
                awaitWake()
                // Exercise the real OCR speech path with fixed text, without photographing or logging private text.
                repeat(2) {
                    scenario.onActivity { activity ->
                        (activity.supportFragmentManager.findFragmentByTag("ocr") as OcrFragment)
                            .readRecognizedText("This is a reading test. The next voice command should work.")
                        assertTrue(SpeechGate.busy)
                    }
                    awaitWake()
                }
                scenario.onActivity { activity ->
                    val ocr = activity.supportFragmentManager.findFragmentByTag("ocr") as OcrFragment
                    ocr.readRecognizedText("This is a longer reading test. ".repeat(20))
                    assertTrue(SpeechGate.busy)
                    ocr.requireView().findViewById<android.view.View>(R.id.btn_stop_reading).performClick()
                }
                awaitWake()
            }
        } finally { prefs.edit().putBoolean("hands_free", enabled).commit() }
    }
}
