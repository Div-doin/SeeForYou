package com.example.seeforyou.services

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FirebaseService {

    private val db: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun logDetection(label: String, confidence: Float) {
        val timestamp = formatter.format(Date())
        val key = timestamp.replace(" ", "_").replace(":", "-")
        val data = mapOf(
            "label" to label,
            "confidence" to String.format("%.2f", confidence),
            "timestamp" to timestamp
        )
        db.child("logs").child(key).setValue(data)
    }

    fun logOcr(text: String) {
        val timestamp = formatter.format(Date())
        val key = timestamp.replace(" ", "_").replace(":", "-")
        val data = mapOf(
            "text" to text,
            "timestamp" to timestamp
        )
        db.child("ocr_logs").child(key).setValue(data)
    }

    fun sendSosAlert(lat: Double, lng: Double) {
        val timestamp = formatter.format(Date())
        val data = mapOf(
            "lat" to lat.toString(),
            "lng" to lng.toString(),
            "timestamp" to timestamp,
            "resolved" to false
        )
        db.child("sos").push().setValue(data)
    }

    fun getLogsReference(): DatabaseReference {
        return db.child("logs")
    }
}