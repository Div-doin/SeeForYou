package com.example.seeforyou.voice

import android.os.Handler
import android.os.Looper

/** Coordinates wake audio with app speech and the existing destination microphone. */
object SpeechGate {
    private val owners = mutableSetOf<Any>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    val busy: Boolean get() = owners.isNotEmpty()
    fun set(owner: Any, active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { set(owner, active) }
            return
        }
        val changed = if (active) owners.add(owner) else owners.remove(owner)
        if (changed) listeners.toList().forEach { it() }
    }
    fun add(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
}
