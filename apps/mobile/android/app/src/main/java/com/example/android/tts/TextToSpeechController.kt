package com.example.android.tts

class TextToSpeechController(
    private val engine: SpeechEngine,
) {
    var onHighlightChanged: ((IntRange?) -> Unit)? = null
    var state: SpeechState = SpeechState.Idle
        private set
    var speechRate: Float = 1.0f
        private set

    private var sentences: List<SpeechSentence> = emptyList()
    private var index: Int = 0
    private var playbackGeneration: Int = 0

    fun setMarkdown(markdown: String) {
        playbackGeneration += 1
        engine.stop()
        sentences = sentencePattern.findAll(markdown)
            .mapNotNull { match ->
                val leadingWhitespace = match.value.indexOfFirst { !it.isWhitespace() }
                if (leadingWhitespace < 0) return@mapNotNull null
                val trailingWhitespace = match.value.indexOfLast { !it.isWhitespace() }
                val start = match.range.first + leadingWhitespace
                val end = match.range.first + trailingWhitespace
                val spokenText = markdown.substring(start, end + 1).removeMarkdownMarks().trim()
                spokenText.takeIf { it.isNotBlank() }?.let {
                    SpeechSentence(it, start..end)
                }
            }
            .toList()
        index = 0
        state = SpeechState.Idle
        onHighlightChanged?.invoke(null)
    }

    fun play() {
        val sentence = sentences.getOrNull(index) ?: return
        val generation = ++playbackGeneration
        engine.setSpeechRate(speechRate)
        state = SpeechState.Playing(index)
        onHighlightChanged?.invoke(sentence.range)
        engine.speak(sentence.text) {
            if (generation != playbackGeneration || state !is SpeechState.Playing) return@speak
            if (index < sentences.lastIndex) {
                index += 1
                play()
            } else {
                state = SpeechState.Idle
                onHighlightChanged?.invoke(null)
            }
        }
    }

    fun pause() {
        playbackGeneration += 1
        engine.stop()
        state = SpeechState.Paused(index)
        onHighlightChanged?.invoke(null)
    }

    fun next() {
        if (index < sentences.lastIndex) {
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
        if (sentences.isEmpty()) return 0.0
        return index.toDouble() / sentences.size.toDouble()
    }

    private fun String.removeMarkdownMarks(): String =
        replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("[*_`>\\[\\]()]"), "")

    private companion object {
        val sentencePattern = Regex("[^。！？.!?\\n]+[。！？.!?]*")
    }
}

private data class SpeechSentence(
    val text: String,
    val range: IntRange,
)

interface SpeechEngine {
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    fun setSpeechRate(rate: Float)
}

sealed class SpeechState {
    data object Idle : SpeechState()
    data class Playing(val paragraphIndex: Int) : SpeechState()
    data class Paused(val paragraphIndex: Int) : SpeechState()
}
