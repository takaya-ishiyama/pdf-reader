package com.example.pdfreader.tts

class MarkdownSpeechNormalizer {
    fun normalize(markdown: String): String {
        val withoutCodeBlocks = markdown.replace(Regex("(?s)```.*?```"), "")
        return withoutCodeBlocks
            .lineSequence()
            .map { line -> normalizeLine(line) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun normalizeLine(line: String): String {
        if (isTableSeparator(line)) return ""
        if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
            return line
                .trim()
                .trim('|')
                .split('|')
                .map { removeSpeechPunctuation(normalizeInline(it)).trim() }
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }

        return normalizeInline(line)
            .let(::removeSpeechPunctuation)
            .trim()
    }

    private fun normalizeInline(line: String): String {
        return line
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    }

    private fun removeSpeechPunctuation(line: String): String =
        line.replace(Regex("[*_`>#-]"), "")

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") &&
            trimmed.endsWith("|") &&
            trimmed.trim('|').split('|').all { cell ->
                cell.trim().matches(Regex(":?-{3,}:?"))
            }
    }
}

interface TextToSpeechEngine {
    fun speak(text: String)
    fun stop()
    fun setSpeechRate(rate: Float)
}

enum class SpeechState {
    Stopped,
    Playing,
    Paused,
}

class TextToSpeechController(
    private val engine: TextToSpeechEngine,
    private val normalizer: MarkdownSpeechNormalizer = MarkdownSpeechNormalizer(),
) {
    var state: SpeechState = SpeechState.Stopped
        private set

    var speechRate: Float = DEFAULT_SPEECH_RATE
        private set

    var currentParagraphIndex: Int = 0
        private set

    var paragraphCount: Int = 0
        private set

    private var paragraphs: List<String> = emptyList()

    fun play(markdown: String) {
        paragraphs = normalizer.normalize(markdown)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        paragraphCount = paragraphs.size
        currentParagraphIndex = 0
        if (paragraphs.isEmpty()) {
            state = SpeechState.Stopped
            return
        }
        speakCurrentParagraph()
    }

    fun pause() {
        if (state == SpeechState.Playing) {
            engine.stop()
            state = SpeechState.Paused
        }
    }

    fun stop() {
        engine.stop()
        state = SpeechState.Stopped
        currentParagraphIndex = 0
    }

    fun nextParagraph() {
        if (paragraphs.isEmpty()) return
        currentParagraphIndex = (currentParagraphIndex + 1).coerceAtMost(paragraphs.lastIndex)
        speakCurrentParagraph()
    }

    fun previousParagraph() {
        if (paragraphs.isEmpty()) return
        currentParagraphIndex = (currentParagraphIndex - 1).coerceAtLeast(0)
        speakCurrentParagraph()
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        engine.setSpeechRate(speechRate)
    }

    fun positionLabel(): String =
        if (paragraphCount == 0) {
            "0/0"
        } else {
            "${currentParagraphIndex + 1}/$paragraphCount"
        }

    fun progressRatio(): Double =
        when {
            paragraphCount <= 0 -> 0.0
            paragraphCount == 1 -> 0.0
            else -> currentParagraphIndex.toDouble() / (paragraphCount - 1).toDouble()
        }

    private fun speakCurrentParagraph() {
        engine.speak(paragraphs[currentParagraphIndex])
        state = SpeechState.Playing
    }

    private companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
    }
}
