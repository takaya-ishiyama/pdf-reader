package com.example.pdfreader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownSpeechNormalizerTest {
    @Test
    fun removesMarkdownSyntaxForSpeech() {
        val markdown = """
            # Chapter 1
            Read [OpenAI](https://openai.com) and ![diagram](image.png).
            ```kotlin
            println("skip")
            ```
            **Important** text
        """.trimIndent()

        val normalized = MarkdownSpeechNormalizer().normalize(markdown)

        assertEquals(
            """
            Chapter 1
            Read OpenAI and diagram.
            Important text
            """.trimIndent(),
            normalized,
        )
    }

    @Test
    fun simplifiesMarkdownTablesForSpeech() {
        val markdown = """
            | Name | Value |
            | --- | ---: |
            | Speed | **Fast** |
            | Link | [OpenAI](https://openai.com) |
        """.trimIndent()

        val normalized = MarkdownSpeechNormalizer().normalize(markdown)

        assertEquals(
            """
            Name, Value
            Speed, Fast
            Link, OpenAI
            """.trimIndent(),
            normalized,
        )
    }

    @Test
    fun controllerMovesThroughSpeechStates() {
        val engine = FakeTextToSpeechEngine()
        val controller = TextToSpeechController(engine)

        controller.play("# Title")
        assertEquals(SpeechState.Playing, controller.state)
        assertEquals("Title", engine.spokenText)
        assertEquals("1/1", controller.positionLabel())

        controller.pause()
        assertEquals(SpeechState.Paused, controller.state)
        assertEquals(1, engine.stopCount)

        controller.stop()
        assertEquals(SpeechState.Stopped, controller.state)
        assertEquals(2, engine.stopCount)
    }

    @Test
    fun controllerTracksParagraphPositionAndSpeechRate() {
        val engine = FakeTextToSpeechEngine()
        val controller = TextToSpeechController(engine)

        controller.play(
            """
            # One

            Two
            Three
            """.trimIndent(),
        )

        assertEquals(0, controller.currentParagraphIndex)
        assertEquals(3, controller.paragraphCount)
        assertEquals("1/3", controller.positionLabel())
        assertEquals(0.0, controller.progressRatio(), 0.0)
        assertEquals("One", engine.spokenText)

        controller.nextParagraph()
        assertEquals(1, controller.currentParagraphIndex)
        assertEquals("2/3", controller.positionLabel())
        assertEquals(0.5, controller.progressRatio(), 0.0)
        assertEquals("Two", engine.spokenText)

        controller.previousParagraph()
        assertEquals(0, controller.currentParagraphIndex)
        assertEquals(0.0, controller.progressRatio(), 0.0)
        assertEquals("One", engine.spokenText)

        controller.setSpeechRate(1.5f)
        assertEquals(1.5f, controller.speechRate)
        assertEquals(1.5f, engine.lastSpeechRate)

        controller.setSpeechRate(9.0f)
        assertEquals(2.0f, controller.speechRate)
        assertEquals(2.0f, engine.lastSpeechRate)
    }
}

private class FakeTextToSpeechEngine : TextToSpeechEngine {
    var spokenText: String? = null
    var stopCount: Int = 0

    override fun speak(text: String) {
        spokenText = text
    }

    override fun stop() {
        stopCount += 1
    }

    var lastSpeechRate: Float = 1.0f

    override fun setSpeechRate(rate: Float) {
        lastSpeechRate = rate
    }
}
