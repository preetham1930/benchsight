package com.benchsight.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken replies with Android's built-in system voice (no AI model; allowed by hackathon rules).
 * Uses the technician's language when the phone has that voice installed, otherwise English (India).
 */
class Speaker(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }

    fun speak(sayEn: String, sayLocal: String?, language: String?) {
        if (!ready) return
        val locale = when (language) {
            "telugu", "mixed" -> Locale("te", "IN")
            "tamil" -> Locale("ta", "IN")
            "hindi" -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        val useLocal = locale.language != "en" && !sayLocal.isNullOrBlank() &&
            tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        tts.language = if (useLocal) locale else Locale("en", "IN")
        tts.speak(if (useLocal) sayLocal else sayEn, TextToSpeech.QUEUE_FLUSH, null, "benchsight")
    }

    fun shutdown() = tts.shutdown()
}
