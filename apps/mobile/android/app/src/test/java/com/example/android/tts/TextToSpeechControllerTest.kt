package com.example.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToSpeechControllerTest {
    @Test
    fun playSpeaksFirstMarkdownParagraphWithoutMarkdownMarks() {
        val engine = FakeSpeechEngine()
        val controller = TextToSpeechController(engine)

        controller.setMarkdown("# Chapter 1\n\nThis is **body**.")
        controller.play()

        assertEquals("Chapter 1", engine.spoken.last())
        assertEquals(SpeechState.Playing(0), controller.state)
    }

    @Test
    fun nextMovesParagraphAndProgress() {
        val engine = FakeSpeechEngine()
        val controller = TextToSpeechController(engine)
        controller.setMarkdown("One\n\nTwo\n\nThree")

        controller.next()

        assertEquals("Two", engine.spoken.last())
        assertEquals(1.0 / 3.0, controller.currentProgressRatio(), 0.001)
    }

    @Test
    fun speechRateIsClamped() {
        val engine = FakeSpeechEngine()
        val controller = TextToSpeechController(engine)

        controller.setSpeechRate(3.0f)

        assertEquals(2.0f, controller.speechRate)
        assertTrue(engine.rates.contains(2.0f))
    }

    @Test
    fun completionAdvancesToNextSentenceAndUpdatesHighlight() {
        val engine = FakeSpeechEngine()
        val controller = TextToSpeechController(engine)
        val highlights = mutableListOf<IntRange?>()
        controller.onHighlightChanged = highlights::add
        controller.setMarkdown("First sentence. Second sentence。")

        controller.play()
        engine.completeCurrentSentence()

        assertEquals(listOf("First sentence.", "Second sentence。"), engine.spoken)
        assertEquals(16..31, highlights.last())
        assertEquals(SpeechState.Playing(1), controller.state)
    }

    @Test
    fun playResumesFromSentenceMatchingSavedProgress() {
        val engine = FakeSpeechEngine()
        val controller = TextToSpeechController(engine)

        controller.setMarkdown("One. Two. Three. Four.", initialProgressRatio = 0.5)
        controller.play()

        assertEquals("Three.", engine.spoken.last())
        assertEquals(SpeechState.Playing(2), controller.state)
        assertEquals(0.5, controller.currentProgressRatio(), 0.001)
    }
}

private class FakeSpeechEngine : SpeechEngine {
    val spoken = mutableListOf<String>()
    val rates = mutableListOf<Float>()
    var stopped = false
    private var onDone: (() -> Unit)? = null

    override fun speak(text: String, onDone: () -> Unit) {
        spoken += text
        this.onDone = onDone
    }

    fun completeCurrentSentence() = onDone?.invoke()

    override fun stop() {
        stopped = true
    }

    override fun setSpeechRate(rate: Float) {
        rates += rate
    }
}
