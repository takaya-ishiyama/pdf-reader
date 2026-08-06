package com.example.android.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AndroidSpeechEngine(context: Context) : SpeechEngine, TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)
    private val utteranceIds = AtomicLong()
    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let(completionCallbacks::remove)?.invoke()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let(completionCallbacks::remove)
                }

                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let(completionCallbacks::remove)
                }
            })
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (ready) {
            val utteranceId = "markdown-reader-${utteranceIds.incrementAndGet()}"
            completionCallbacks[utteranceId] = onDone
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    override fun stop() {
        tts.stop()
        completionCallbacks.clear()
    }

    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    fun shutdown() {
        tts.shutdown()
    }
}
