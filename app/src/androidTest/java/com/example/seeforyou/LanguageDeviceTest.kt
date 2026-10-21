package com.example.seeforyou

import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import com.example.seeforyou.localization.AppLanguage
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.seeforyou.localization.PhraseBook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.equalTo

@RunWith(AndroidJUnit4::class)
class LanguageDeviceTest {
    @Test fun selectingLanguageUpdatesVisibleScreensWithoutSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = AppLanguage.prefs(context)
        val originalTag = prefs.getString("language_tag", null)
        val originalIndex = prefs.getInt("lang_index", 0)
        try {
            prefs.edit().putString("language_tag", "en-IN").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings
                }
                listOf("hi-IN", "kn-IN", "en-IN").forEach { tag ->
                    // Android collapses whitespace in unquoted XML string resources.
                    val expectedSaveLabel = PhraseBook(tag).text("save_settings").replace(Regex("\\s+"), " ")
                    onView(withId(R.id.spinner_language)).perform(scrollTo(), click())
                    onData(equalTo(AppLanguage.names[AppLanguage.tags.indexOf(tag)])).perform(click())
                    onView(withId(R.id.btn_save_settings)).check(matches(withText(expectedSaveLabel)))
                    assertEquals(tag, AppLanguage.tag(context))
                    scenario.onActivity {
                        assertEquals(R.id.nav_settings, it.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
                    }
                    scenario.recreate()
                    onView(withId(R.id.btn_save_settings)).check(matches(withText(expectedSaveLabel)))
                }
            }
        } finally {
            prefs.edit().apply {
                if (originalTag == null) remove("language_tag") else putString("language_tag", originalTag)
                putInt("lang_index", originalIndex)
            }.commit()
        }
    }

    @Test fun activityUsesSavedLanguageAndKeepsSettingsOnRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = AppLanguage.prefs(context)
        val original = prefs.getString("language_tag", null)
        try {
            listOf("hi-IN", "kn-IN").forEach { tag ->
                prefs.edit().putString("language_tag", tag).commit()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val navigation = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
                        navigation.selectedItemId = R.id.nav_settings
                        assertEquals(PhraseBook(tag).text("settings"),
                            navigation.menu.findItem(R.id.nav_settings).title.toString())
                    }
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertEquals(R.id.nav_settings,
                            activity.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
                        assertEquals(PhraseBook(tag).text("settings"), activity.getString(R.string.settings))
                    }
                }
            }
        } finally {
            prefs.edit().apply {
                if (original == null) remove("language_tag") else putString("language_tag", original)
            }.commit()
        }
    }

    @Test fun packagedTranslationsResolveOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("hi-IN", "kn-IN", "en-IN").forEach { tag ->
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            val localized = context.createConfigurationContext(config)
            val book = PhraseBook(tag)
            assertEquals(book.text("settings"), localized.getString(R.string.settings))
            assertEquals(book.text("char_count", 12), localized.getString(R.string.char_count, 12))
            assertEquals(book.text("ocr_kn_limit"), localized.getString(R.string.ocr_kn_limit))
        }
    }

    @Test fun reportInstalledVoiceSupportWithoutSpeaking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        lateinit var engine: TextToSpeech
        instrumentation.runOnMainSync {
            engine = TextToSpeech(instrumentation.targetContext) {
                status = it
                ready.countDown()
            }
        }
        try {
            assertTrue("TTS initialization timed out", ready.await(20, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            listOf("en-IN", "hi-IN", "kn-IN").forEach { tag ->
                android.util.Log.i("SeeForYouLanguageTest", "$tag availability=${engine.isLanguageAvailable(Locale.forLanguageTag(tag))}")
            }
        } finally { instrumentation.runOnMainSync { engine.shutdown() } }
    }
}
