package com.example.android.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidSpeechEngine(context: Context) : SpeechEngine, TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
        }
    }

    override fun speak(text: String) {
        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "markdown-reader")
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    fun shutdown() {
        tts.shutdown()
    }
}
