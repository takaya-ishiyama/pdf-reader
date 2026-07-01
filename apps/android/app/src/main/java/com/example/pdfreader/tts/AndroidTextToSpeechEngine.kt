package com.example.pdfreader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class AndroidTextToSpeechEngine(
    context: Context,
) : TextToSpeechEngine {
    private var ready = false
    private var pendingText: String? = null
    private val textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                textToSpeech.language = Locale.getDefault()
                pendingText?.let { speak(it) }
                pendingText = null
            }
        }
    }

    override fun speak(text: String) {
        if (!ready) {
            pendingText = text
            return
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun stop() {
        pendingText = null
        textToSpeech.stop()
    }

    override fun setSpeechRate(rate: Float) {
        textToSpeech.setSpeechRate(rate)
    }

    fun shutdown() {
        textToSpeech.shutdown()
    }
}
