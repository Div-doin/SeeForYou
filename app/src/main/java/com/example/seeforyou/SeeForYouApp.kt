package com.example.seeforyou

import android.app.Application
import com.google.firebase.FirebaseApp

class SeeForYouApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}