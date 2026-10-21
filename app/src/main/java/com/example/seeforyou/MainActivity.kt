package com.example.seeforyou

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.seeforyou.localization.AppLanguage
import com.example.seeforyou.screens.*
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.seeforyou.services.TtsService
import com.example.seeforyou.voice.HandsFreeController
import com.example.seeforyou.voice.VoiceCommand

class MainActivity : AppCompatActivity() {
    private lateinit var handsFree: HandsFreeController
    private lateinit var commandSpeech: TtsService
    private var handsFreeEnabled = false
    private var deferredScreen: Int? = null
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            showScreen(item.itemId)
            true
        }
        val selected = savedInstanceState?.getInt("selected_tab", R.id.nav_camera) ?: R.id.nav_camera
        bottomNav.selectedItemId = selected
        commandSpeech = TtsService(this)
        handsFree = HandsFreeController(this,
            diagnostic = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) com.example.seeforyou.voice.VoiceDiagnostic.entries
                .firstOrNull { it.name == intent.getStringExtra("voice_diagnostic") } else null,
            heard = { text -> findViewById<TextView>(R.id.voice_heard).apply {
                this.text = text
                visibility = if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            } },
            status = { findViewById<TextView>(R.id.voice_status).text = it },
            command = { handleVoiceCommand(it) },
            feedback = {
                findViewById<TextView>(R.id.voice_status).text = it
                commandSpeech.speak(it, true)
            })
        handsFreeEnabled = AppLanguage.prefs(this).getBoolean("hands_free", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        updateHandsFree()
        findViewById<Button>(R.id.btn_hands_free).setOnClickListener {
            if (!handsFreeEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 700)
            } else {
                handsFreeEnabled = !handsFreeEnabled
                updateHandsFree()
                if (handsFreeEnabled) commandSpeech.speak(getString(R.string.voice_ready_wake), true)
            }
        }
        if (AppLanguage.prefs(this).getBoolean("announce_language", false)) {
            AppLanguage.prefs(this).edit().remove("announce_language").apply()
            commandSpeech.speak(getString(R.string.voice_language_changed), true)
        }
    }
    private fun updateHandsFree() {
        AppLanguage.prefs(this).edit().putBoolean("hands_free", handsFreeEnabled).apply()
        findViewById<Button>(R.id.btn_hands_free).setText(if (handsFreeEnabled) R.string.voice_disable else R.string.voice_enable)
        handsFree.setEnabled(handsFreeEnabled)
        if (handsFreeEnabled) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    internal fun handleVoiceCommand(command: VoiceCommand) {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        when (command) {
            is VoiceCommand.Language -> {
                AppLanguage.prefs(this).edit().putString("language_tag", command.tag)
                    .putInt("lang_index", AppLanguage.tags.indexOf(command.tag))
                    .putBoolean("announce_language", true).apply()
                recreate()
            }
            VoiceCommand.Read -> {
                nav.selectedItemId = R.id.nav_ocr
                (supportFragmentManager.findFragmentByTag("ocr") as? OcrFragment)?.readFromVoice()
            }
            VoiceCommand.Describe -> {
                nav.selectedItemId = R.id.nav_camera
                supportFragmentManager.findFragmentByTag("camera")?.view?.findViewById<android.view.View>(R.id.btn_describe)?.performClick()
            }
            VoiceCommand.Settings -> { nav.selectedItemId = R.id.nav_settings }
            VoiceCommand.Disable -> { handsFreeEnabled = false; updateHandsFree() }
            VoiceCommand.Stop -> {
                commandSpeech.stop()
                val active = supportFragmentManager.fragments.firstOrNull { !it.isHidden }
                if (active is OcrFragment) active.view?.findViewById<android.view.View>(R.id.btn_stop_reading)?.performClick()
                else if (active is CameraFragment) active.pauseForVoice()
            }
            VoiceCommand.Repeat -> {
                val active = supportFragmentManager.fragments.firstOrNull { !it.isHidden }
                val button = if (active is OcrFragment) R.id.btn_repeat_reading else R.id.btn_repeat
                active?.view?.findViewById<android.view.View>(button)?.performClick()
            }
        }
    }
    override fun onResume() { super.onResume(); if (::handsFree.isInitialized) handsFree.setForeground(true) }
    override fun onPause() {
        if (::handsFree.isInitialized) handsFree.setForeground(false)
        super.onPause()
    }
    override fun onStop() {
        if (::handsFree.isInitialized) handsFree.setForeground(false)
        if (::commandSpeech.isInitialized) commandSpeech.stop()
        super.onStop()
    }
    override fun onDestroy() {
        if (::handsFree.isInitialized) handsFree.close()
        if (::commandSpeech.isInitialized) commandSpeech.shutdown()
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700) {
            handsFreeEnabled = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            updateHandsFree()
            if (handsFreeEnabled) commandSpeech.speak(getString(R.string.voice_ready_wake), true)
            if (!handsFreeEnabled) Toast.makeText(this, R.string.voice_mic_permission, Toast.LENGTH_LONG).show()
        }
    }
    private fun showScreen(id: Int) {
        if (supportFragmentManager.isStateSaved) {
            deferredScreen = id
            return
        }
        val tag = when (id) {
            R.id.nav_ocr -> "ocr"
            R.id.nav_log -> "log"
            R.id.nav_settings -> "settings"
            R.id.nav_sos -> "sos"
            else -> "camera"
        }
        val fm = supportFragmentManager
        val target: Fragment = fm.findFragmentByTag(tag) ?: when (tag) {
            "ocr" -> OcrFragment()
            "log" -> LogFragment()
            "settings" -> SettingsFragment()
            "sos" -> SosFragment()
            else -> CameraFragment()
        }
        val transaction = fm.beginTransaction()
        fm.fragments.filter { it !== target }.forEach { transaction.hide(it) }
        if (target.isAdded) transaction.show(target)
        else transaction.add(R.id.fragment_container, target, tag)
        transaction.commitNow()
    }
    override fun onResumeFragments() {
        super.onResumeFragments()
        deferredScreen?.let { deferredScreen = null; showScreen(it) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
        super.onSaveInstanceState(outState)
    }
}
