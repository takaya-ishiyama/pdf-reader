package com.example.android.tts

class TextToSpeechController(
    private val engine: SpeechEngine,
) {
    var state: SpeechState = SpeechState.Idle
        private set
    var speechRate: Float = 1.0f
        private set

    private var paragraphs: List<String> = emptyList()
    private var index: Int = 0

    fun setMarkdown(markdown: String) {
        paragraphs = markdown
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim().removeMarkdownMarks() }
            .filter { it.isNotBlank() }
        index = 0
        state = SpeechState.Idle
    }

    fun play() {
        val text = paragraphs.getOrNull(index) ?: return
        engine.setSpeechRate(speechRate)
        engine.speak(text)
        state = SpeechState.Playing(index)
    }

    fun pause() {
        engine.stop()
        state = SpeechState.Paused(index)
    }

    fun next() {
        if (index < paragraphs.lastIndex) {
            index += 1
            play()
        }
    }

    fun previous() {
        if (index > 0) {
            index -= 1
            play()
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        engine.setSpeechRate(speechRate)
    }

    fun currentProgressRatio(): Double {
        if (paragraphs.isEmpty()) return 0.0
        return index.toDouble() / paragraphs.size.toDouble()
    }

    private fun String.removeMarkdownMarks(): String =
        replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("[*_`>\\[\\]()]"), "")
}

interface SpeechEngine {
    fun speak(text: String)
    fun stop()
    fun setSpeechRate(rate: Float)
}

sealed class SpeechState {
    data object Idle : SpeechState()
    data class Playing(val paragraphIndex: Int) : SpeechState()
    data class Paused(val paragraphIndex: Int) : SpeechState()
}
