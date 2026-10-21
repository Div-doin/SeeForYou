package com.example.seeforyou.voice

/** Explicit, debug-launch-only comparisons; never persisted to user settings. */
enum class VoiceDiagnostic {
    BASELINE, TIMING, NO_BEEP, ENGLISH, HINDI;

    val language: String get() = if (this == HINDI) "hi-IN" else "en-IN"
    val autoSwitch: Boolean get() = this != ENGLISH && this != HINDI
}
