package com.example.seeforyou.localization

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

object AppLanguage {
    // Keep existing indices so installed users retain their selection.
    val tags = listOf("en-IN", "en-US", "kn-IN", "hi-IN")
    val names = listOf("English (India)", "English (US)", "ಕನ್ನಡ — Kannada", "हिन्दी — Hindi")
    fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun tag(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString("language_tag", null)?.takeIf { it in tags }
            ?: tags[prefs.getInt("lang_index", 0).coerceIn(tags.indices)]
    }
    fun locale(context: Context) = Locale.forLanguageTag(tag(context))
    fun book(context: Context) = PhraseBook(tag(context))
    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale(context))
        return context.createConfigurationContext(config)
    }
    fun speed(context: Context) = prefs(context).getInt("speed", 5).coerceIn(0, 10) / 10f + 0.1f
    fun volume(context: Context) = prefs(context).getInt("volume", 80).coerceIn(0, 100) / 100f
    fun speechParams(volume: Float) = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
    }
}
