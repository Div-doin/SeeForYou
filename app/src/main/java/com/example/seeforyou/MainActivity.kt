package com.example.seeforyou

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.seeforyou.screens.*
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val cameraFragment   = CameraFragment()
    private val ocrFragment      = OcrFragment()
    private val logFragment      = LogFragment()
    private val settingsFragment = SettingsFragment()
    private val sosFragment      = SosFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Only add CameraFragment first — others added lazily on first tab tap
        val fm = supportFragmentManager
        fm.beginTransaction()
            .add(R.id.fragment_container, cameraFragment, "camera")
            .commit()

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_camera   -> showFragment(cameraFragment,   "camera")
                R.id.nav_ocr      -> showFragment(ocrFragment,      "ocr")
                R.id.nav_log      -> showFragment(logFragment,      "log")
                R.id.nav_settings -> showFragment(settingsFragment, "settings")
                R.id.nav_sos      -> showFragment(sosFragment,      "sos")
            }
            true
        }
    }

    private fun showFragment(target: Fragment, tag: String) {
        val fm = supportFragmentManager

        // Hide all currently added fragments
        val transaction = fm.beginTransaction()
        fm.fragments.forEach { transaction.hide(it) }

        // If target not added yet, add it — otherwise just show it
        if (!target.isAdded) {
            transaction.add(R.id.fragment_container, target, tag)
        } else {
            transaction.show(target)
        }

        transaction.commit()
    }
}