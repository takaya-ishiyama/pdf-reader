package com.example.pdfreader

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.pdfreader.document.DocumentListItem
import com.example.pdfreader.document.MarkdownViewerState
import com.example.pdfreader.document.MarkdownViewerViewModel
import com.example.pdfreader.markdown.HeadingAnchor
import com.example.pdfreader.markdown.MarkdownDisplayFormatter
import com.example.pdfreader.markdown.MarkdownHeadingIndex
import com.example.pdfreader.tts.AndroidTextToSpeechEngine
import com.example.pdfreader.tts.TextToSpeechController
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var clientIdProvider: ClientIdProvider
    private lateinit var viewModel: MarkdownViewerViewModel
    private lateinit var speechEngine: AndroidTextToSpeechEngine
    private lateinit var speechController: TextToSpeechController
    private lateinit var documentIdInput: EditText
    private lateinit var documentListLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var speechStatusText: TextView
    private lateinit var titleText: TextView
    private lateinit var markdownText: TextView
    private lateinit var scrollView: ScrollView

    private var currentDocumentId: String? = null
    private var currentVersion: Int? = null
    private var currentMarkdown: String = ""
    private var currentHeadingAnchors: List<HeadingAnchor> = emptyList()
    private val displayFormatter = MarkdownDisplayFormatter()
    private val headingIndex = MarkdownHeadingIndex()
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = AppFactory(this)
        clientIdProvider = ClientIdProvider(this)
        viewModel = factory.markdownViewerViewModel()
        speechEngine = AndroidTextToSpeechEngine(applicationContext)
        speechController = TextToSpeechController(speechEngine)

        setContentView(buildLayout())
    }

    override fun onPause() {
        super.onPause()
        backgroundExecutor.execute { viewModel.onBackgrounded() }
    }

    override fun onDestroy() {
        backgroundExecutor.shutdown()
        speechEngine.shutdown()
        super.onDestroy()
    }

    private fun buildLayout(): LinearLayout {
        documentIdInput = EditText(this).apply {
            hint = "Document ID"
            setSingleLine(true)
        }
        statusText = TextView(this)
        speechStatusText = TextView(this).apply {
            text = "TTS 0/0 1.0x"
        }
        titleText = TextView(this).apply {
            textSize = 20f
            setPadding(0, 12, 0, 8)
        }
        markdownText = TextView(this).apply {
            textSize = 16f
            setLineSpacing(4f, 1.0f)
        }
        scrollView = ScrollView(this).apply {
            addView(
                markdownText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            viewTreeObserver.addOnScrollChangedListener { recordProgressFromScroll() }
        }

        val loadButton = Button(this).apply {
            text = "Load"
            setOnClickListener { loadDocument() }
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadDocumentList() }
        }
        val playButton = Button(this).apply {
            text = "Play"
            setOnClickListener {
                speechController.play(currentMarkdown)
                syncScrollToSpeechPosition()
                updateSpeechStatus()
            }
        }
        val pauseButton = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                speechController.pause()
                updateSpeechStatus()
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                speechController.stop()
                updateSpeechStatus()
            }
        }
        val previousSpeechButton = Button(this).apply {
            text = "Prev"
            setOnClickListener {
                speechController.previousParagraph()
                syncScrollToSpeechPosition()
                updateSpeechStatus()
            }
        }
        val nextSpeechButton = Button(this).apply {
            text = "Next"
            setOnClickListener {
                speechController.nextParagraph()
                syncScrollToSpeechPosition()
                updateSpeechStatus()
            }
        }
        val slowerSpeechButton = Button(this).apply {
            text = "0.8x"
            setOnClickListener {
                speechController.setSpeechRate(speechController.speechRate - 0.1f)
                updateSpeechStatus()
            }
        }
        val fasterSpeechButton = Button(this).apply {
            text = "1.2x"
            setOnClickListener {
                speechController.setSpeechRate(speechController.speechRate + 0.1f)
                updateSpeechStatus()
            }
        }

        val documentControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(refreshButton)
            addView(loadButton)
        }
        val speechPlaybackControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playButton)
            addView(pauseButton)
            addView(stopButton)
        }
        val speechNavigationControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previousSpeechButton)
            addView(nextSpeechButton)
            addView(slowerSpeechButton)
            addView(fasterSpeechButton)
        }
        documentListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(documentIdInput)
            addView(documentControls)
            addView(speechPlaybackControls)
            addView(speechNavigationControls)
            addView(statusText)
            addView(speechStatusText)
            addView(documentListLayout)
            addView(titleText)
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
    }

    private fun loadDocumentList() {
        statusText.text = "Loading"
        backgroundExecutor.execute {
            viewModel.loadDocuments(clientIdProvider.getOrCreate())
            mainHandler.post { renderState(viewModel.state) }
        }
    }

    private fun loadDocument() {
        val documentId = documentIdInput.text.toString().trim()
        if (documentId.isBlank()) {
            statusText.text = "Document ID is required"
            return
        }

        currentDocumentId = documentId
        statusText.text = "Loading"
        backgroundExecutor.execute {
            val cachedLoaded = viewModel.loadCached(documentId)
            if (cachedLoaded) {
                mainHandler.post { renderState(viewModel.state) }
            }
            viewModel.load(documentId, clientIdProvider.getOrCreate())
            mainHandler.post { renderState(viewModel.state) }
        }
    }

    private fun renderState(state: MarkdownViewerState) {
        when (state) {
            MarkdownViewerState.Idle -> statusText.text = ""
            MarkdownViewerState.Loading -> statusText.text = "Loading"
            is MarkdownViewerState.DocumentList -> {
                statusText.text = ""
                renderDocumentList(state.documents)
            }
            is MarkdownViewerState.Content -> {
                statusText.text = ""
                documentListLayout.removeAllViews()
                titleText.text = state.title
                currentMarkdown = state.markdown
                speechController.stop()
                updateSpeechStatus()
                currentHeadingAnchors = headingIndex.extractAnchors(state.markdown)
                markdownText.text = displayFormatter.format(state.markdown)
                currentVersion = state.version
                restoreProgress(state.readingProgress?.progressRatio ?: 0.0)
            }
            is MarkdownViewerState.Error -> {
                statusText.text = state.message
            }
        }
    }

    private fun renderDocumentList(documents: List<DocumentListItem>) {
        documentListLayout.removeAllViews()
        titleText.text = ""
        markdownText.text = ""
        currentMarkdown = ""
        speechController.stop()
        updateSpeechStatus()
        documents.forEach { item ->
            val button = Button(this).apply {
                val progress = item.progressRatio?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                text = "${item.title} v${item.version.value}$progress"
                setOnClickListener {
                    documentIdInput.setText(item.documentId.value)
                    loadDocument()
                }
            }
            documentListLayout.addView(button)
        }
    }

    private fun restoreProgress(progressRatio: Double) {
        scrollView.post {
            val scrollRange = (markdownText.height - scrollView.height).coerceAtLeast(0)
            scrollView.scrollTo(0, (scrollRange * progressRatio).toInt())
        }
    }

    private fun syncScrollToSpeechPosition() {
        val ratio = speechController.progressRatio()
        scrollView.post {
            val scrollRange = (markdownText.height - scrollView.height).coerceAtLeast(0)
            scrollView.scrollTo(0, (scrollRange * ratio).toInt())
            recordProgressFromScroll()
        }
    }

    private fun recordProgressFromScroll() {
        val documentId = currentDocumentId ?: return
        val version = currentVersion ?: return
        val scrollRange = (markdownText.height - scrollView.height).coerceAtLeast(1)
        val ratio = (scrollView.scrollY.toDouble() / scrollRange).coerceIn(0.0, 1.0)

        val approxLine = ((markdownText.lineCount - 1).coerceAtLeast(0) * ratio).toInt()
        val headingAnchor = headingIndex.nearestAnchor(currentHeadingAnchors, approxLine)
        val positionType = if (headingAnchor != null) "heading_anchor" else "offset"
        val positionValue = headingAnchor ?: scrollView.scrollY.toString()
        backgroundExecutor.execute {
            viewModel.onProgressChanged(
                documentId = documentId,
                version = version,
                positionType = positionType,
                positionValue = positionValue,
                progressRatio = ratio,
            )
            viewModel.tickProgressSync()
        }
    }

    private fun updateSpeechStatus() {
        speechStatusText.text = "TTS ${speechController.positionLabel()} ${
            String.format(Locale.US, "%.1f", speechController.speechRate)
        }x"
    }
}
