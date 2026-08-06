package com.example.android

import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import com.example.android.data.DocumentRepository
import com.example.android.data.FileMarkdownCache
import com.example.android.data.HttpApiClient
import com.example.android.data.HttpMarkdownFetcher
import com.example.android.data.MarkdownDocument
import com.example.android.data.PositionType
import com.example.android.data.ProgressUpdate
import com.example.android.data.ResultState
import com.example.android.data.SharedPreferencesClientIdProvider
import com.example.android.data.SharedPreferencesMetadataStore
import com.example.android.sync.ProgressSyncManager
import com.example.android.tts.AndroidSpeechEngine
import com.example.android.tts.TextToSpeechController
import com.example.android.ui.DocumentReaderViewModel
import com.example.android.ui.MarkdownPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: DocumentRepository
    private lateinit var viewModel: DocumentReaderViewModel
    private lateinit var syncManager: ProgressSyncManager
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var ttsController: TextToSpeechController

    private lateinit var root: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var markdown: TextView
    private var currentDocument: MarkdownDocument? = null
    private var pager: MarkdownPager? = null
    private var pageIndex: Int = 0
    private lateinit var pageStatus: TextView
    private var currentHeadingAnchor: String = "top"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        repository = createRepository()
        viewModel = DocumentReaderViewModel(repository, scope)
        syncManager = ProgressSyncManager(repository, scope)
        speechEngine = AndroidSpeechEngine(this)
        ttsController = TextToSpeechController(speechEngine)
        buildLayout()
        bindState()
        viewModel.loadDocuments()
    }

    override fun onPause() {
        syncManager.onBackgrounded()
        super.onPause()
    }

    override fun onDestroy() {
        speechEngine.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun createRepository(): DocumentRepository {
        val preferences = getSharedPreferences("markdown_reader", MODE_PRIVATE)
        return DocumentRepository(
            apiClient = HttpApiClient(BuildConfig.API_BASE_URL),
            markdownFetcher = HttpMarkdownFetcher(),
            markdownCache = FileMarkdownCache(File(cacheDir, "markdown")),
            metadataStore = SharedPreferencesMetadataStore(preferences),
            clientIdProvider = SharedPreferencesClientIdProvider(preferences),
        )
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val contentPadding = dp(16)
            view.updatePadding(
                left = contentPadding + systemBars.left,
                top = contentPadding + systemBars.top,
                right = contentPadding + systemBars.right,
                bottom = contentPadding + systemBars.bottom,
            )
            insets
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        loading = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply { textSize = 14f }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { visibility = View.GONE }
        markdown = TextView(this).apply {
            textSize = 17f
            setLineSpacing(8f, 1.05f)
            setPadding(0, dp(12), 0, dp(96))
        }
        scroll.addView(markdown)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val back = Button(this).apply {
            text = "List"
            setOnClickListener { showList() }
        }
        val previous = Button(this).apply {
            text = "Prev"
            setOnClickListener { ttsController.previous() }
        }
        val play = Button(this).apply {
            text = "Play"
            setOnClickListener { ttsController.play(); syncSpeechProgress() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { ttsController.pause() }
        }
        val next = Button(this).apply {
            text = "Next"
            setOnClickListener { ttsController.next(); syncSpeechProgress() }
        }
        val rate = SeekBar(this).apply {
            max = 150
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    ttsController.setSpeechRate(0.5f + progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controls.addView(back)
        controls.addView(previous)
        controls.addView(play)
        controls.addView(stop)
        controls.addView(next)
        controls.addView(rate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val pageNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            tag = "page_navigation"
        }
        val previousPage = Button(this).apply {
            text = "Previous page"
            setOnClickListener { showPage(pageIndex - 1) }
        }
        pageStatus = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
        }
        val nextPage = Button(this).apply {
            text = "Next page"
            setOnClickListener { showPage(pageIndex + 1) }
        }
        pageNavigation.addView(previousPage)
        pageNavigation.addView(pageStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pageNavigation.addView(nextPage)

        root.addView(title)
        root.addView(loading)
        root.addView(status)
        root.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(pageNavigation)
        root.addView(controls)
        setContentView(root)

        scroll.viewTreeObserver.addOnScrollChangedListener { syncScrollProgress() }
        controls.tag = "controls"
    }

    private fun bindState() {
        scope.launch {
            viewModel.documents.collect { state ->
                when (state) {
                    ResultState.Loading -> {
                        loading.visibility = View.VISIBLE
                        status.text = "Loading documents"
                    }
                    is ResultState.Loaded -> {
                        loading.visibility = View.GONE
                        renderList(state.value)
                    }
                    is ResultState.Failed -> {
                        loading.visibility = View.GONE
                        status.text = state.message
                    }
                }
            }
        }
        scope.launch {
            viewModel.document.collect { state ->
                when (state) {
                    null -> Unit
                    ResultState.Loading -> {
                        loading.visibility = View.VISIBLE
                        status.text = "Loading document"
                    }
                    is ResultState.Loaded -> {
                        loading.visibility = View.GONE
                        renderDocument(state.value)
                    }
                    is ResultState.Failed -> {
                        loading.visibility = View.GONE
                        status.text = state.message
                    }
                }
            }
        }
    }

    private fun renderList(documents: List<com.example.android.data.DocumentSummary>) {
        showList()
        list.removeAllViews()
        status.text = if (documents.isEmpty()) "No documents" else "Documents"
        documents.forEach { document ->
            val button = Button(this).apply {
                text = "${document.title}  ${(document.progressRatio * 100).toInt()}%"
                setOnClickListener { viewModel.openDocument(document.documentId) }
            }
            list.addView(button, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderDocument(document: MarkdownDocument) {
        currentDocument = document
        pager = MarkdownPager(document.markdown)
        pageIndex = pager!!.pageIndexForProgress(document.detail.readingProgress?.progressRatio ?: 0.0)
        list.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        root.findViewWithTag<LinearLayout>("page_navigation").visibility = View.VISIBLE
        root.findViewWithTag<LinearLayout>("controls").visibility = View.VISIBLE
        status.text = document.detail.title + if (document.fromCache) " (cached)" else ""
        val ratio = document.detail.readingProgress?.progressRatio ?: 0.0
        showPage(pageIndex, syncProgress = false)
        scroll.post {
            val maxScroll = max(0, markdown.height - scroll.height)
            val pageProgress = (ratio * pager!!.pageCount - pageIndex).coerceIn(0.0, 1.0)
            scroll.scrollTo(0, (maxScroll * pageProgress).toInt())
        }
    }

    private fun showPage(requestedIndex: Int, syncProgress: Boolean = true) {
        val currentPager = pager ?: return
        pageIndex = requestedIndex.coerceIn(0, currentPager.pageCount - 1)
        val page = currentPager.page(pageIndex)
        markdown.text = page
        pageStatus.text = "${pageIndex + 1} / ${currentPager.pageCount}"
        ttsController.setMarkdown(page)
        scroll.scrollTo(0, 0)
        if (syncProgress) syncScrollProgress()
    }

    private fun showList() {
        if (currentDocument != null) {
            syncManager.flush()
        }
        currentDocument = null
        pager = null
        list.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        root.findViewWithTag<LinearLayout>("page_navigation").visibility = View.GONE
        root.findViewWithTag<LinearLayout>("controls").visibility = View.GONE
        ttsController.pause()
    }

    private fun syncScrollProgress() {
        val document = currentDocument ?: return
        val currentPager = pager ?: return
        val maxScroll = max(1, markdown.height - scroll.height)
        val pageProgress = (scroll.scrollY.toDouble() / maxScroll.toDouble()).coerceIn(0.0, 1.0)
        val ratio = currentPager.progress(pageIndex, pageProgress)
        val page = currentPager.page(pageIndex)
        val heading = headingAnchorNearOffset(page, pageProgress)
        val type = if (heading != null) PositionType.HeadingAnchor else PositionType.Line
        val value = heading ?: "page-${pageIndex + 1}"
        currentHeadingAnchor = value
        syncManager.onPositionChanged(
            ProgressUpdate(
                documentId = document.detail.documentId,
                version = document.detail.version,
                positionType = type,
                positionValue = value,
                progressRatio = ratio,
            ),
        )
    }

    private fun syncSpeechProgress() {
        val document = currentDocument ?: return
        val currentPager = pager ?: return
        val ratio = currentPager.progress(pageIndex, ttsController.currentProgressRatio())
        syncManager.onPositionChanged(
            ProgressUpdate(
                documentId = document.detail.documentId,
                version = document.detail.version,
                positionType = PositionType.HeadingAnchor,
                positionValue = currentHeadingAnchor,
                progressRatio = ratio,
            ),
        )
    }

    private fun headingAnchorNearOffset(markdown: String, ratio: Double): String? {
        val lines = markdown.lines()
        val target = (lines.size * ratio).toInt().coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        return lines.take(target + 1)
            .lastOrNull { it.startsWith("#") }
            ?.trimStart('#')
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9\\s-]"), "")
            ?.replace(Regex("\\s+"), "-")
            ?.takeIf { it.isNotBlank() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
