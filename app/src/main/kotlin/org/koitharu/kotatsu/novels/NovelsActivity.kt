package org.koitharu.kotatsu.novels

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityNovelsBinding

/** First integrated novel reader. Manga storage and navigation remain independent. */
class NovelsActivity : BaseActivity<ActivityNovelsBinding>() {
    private val repository = RoyalRoadRepository()
    private val progress by lazy { getSharedPreferences("shiori_novels", MODE_PRIVATE) }
    private var task: Job? = null
    private var novel: NovelDetails? = null
    private var chapterIndex = -1
    private var query = ""
    private var results = emptyList<NovelLink>()
    private var fontSize = 20f
    private var readingText: TextView? = null
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityNovelsBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(true, false)
        fontSize = progress.getFloat("font_size", 20f).coerceIn(14f, 32f)
        query = savedInstanceState?.getString("query").orEmpty()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        val url = savedInstanceState?.getString("novel")
        if (url != null) {
            openNovel(url, savedInstanceState.getString("chapter"), savedInstanceState.getInt("offset", 0))
        } else showSearch()
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        return insets
    }

    override fun onSupportNavigateUp(): Boolean { goBack(); return true }

    private fun goBack() {
        task?.cancel()
        if (chapterIndex >= 0) {
            savePosition()
            chapterIndex = -1
            showChapters()
        } else if (novel != null) {
            novel = null
            showSearch()
        } else finish()
    }

    private fun clear(title: String) {
        supportActionBar?.title = title
        readingText = null
        viewBinding.novelControls.removeAllViews()
        viewBinding.novelContent.removeAllViews()
        viewBinding.novelScroll.scrollTo(0, 0)
    }

    private fun label(text: CharSequence, size: Float = 16f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 12, 0, 12)
        viewBinding.novelContent.addView(this)
    }

    private fun button(text: String, parent: LinearLayout = viewBinding.novelContent, action: () -> Unit) {
        parent.addView(Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun showSearch() {
        clear(getString(R.string.novels_title))
        val input = EditText(this).apply {
            hint = getString(R.string.novels_search_hint)
            setSingleLine(true)
            setText(query)
        }
        viewBinding.novelControls.addView(input)
        button(getString(R.string.novels_search), viewBinding.novelControls) {
            query = input.text.toString().trim()
            if (query.isNotEmpty()) search()
        }
        input.setOnEditorActionListener { _, _, _ ->
            query = input.text.toString().trim()
            if (query.isNotEmpty()) search()
            true
        }
        val recent = progress.getString("recent_url", null)
        if (recent != null) {
            button(getString(R.string.novels_continue, progress.getString("recent_title", ""))) {
                openNovel(recent, progress.getString("chapter:$recent", null), progress.getInt("offset:$recent", 0))
            }
        }
        label(getString(R.string.novels_source_notice))
        if (results.isEmpty()) label(getString(R.string.novels_search_prompt))
        results.forEach { book -> button(book.title) { openNovel(book.url) } }
    }

    private fun search(): Unit = load({ search() }) {
        novel = null
        chapterIndex = -1
        results = repository.search(query)
        showSearch()
        if (results.isEmpty()) label(getString(R.string.novels_no_results))
    }

    private fun openNovel(url: String, resumeChapter: String? = null, offset: Int = 0) {
        load({ openNovel(url, resumeChapter, offset) }) {
            novel = repository.details(url)
            chapterIndex = -1
            page = 0
            val index = novel!!.chapters.indexOfFirst { it.url == resumeChapter }
            if (index >= 0) {
                // Start another task only after this task has returned.
                viewBinding.root.post { if (!isFinishing && !isDestroyed) openChapter(index, offset) }
            } else showChapters()
        }
    }

    private fun showChapters() {
        val book = novel ?: return
        clear(book.title)
        chapterIndex = -1
        val lastUrl = progress.getString("chapter:${book.url}", null)
        val last = book.chapters.indexOfFirst { it.url == lastUrl }
        if (last >= 0) button(getString(R.string.novels_resume)) {
            openChapter(last, progress.getInt("offset:${book.url}", 0))
        }
        label(book.synopsis)
        label(getString(R.string.novels_chapter_count, book.chapters.size), 20f)
        book.chapters.drop(page * 100).take(100).forEachIndexed { index, chapter ->
            button(chapter.title) { openChapter(page * 100 + index) }
        }
        if (page > 0) button(getString(R.string.novels_previous_page)) { page--; showChapters() }
        if ((page + 1) * 100 < book.chapters.size) button(getString(R.string.novels_next_page)) { page++; showChapters() }
        button(getString(R.string.novels_open_website)) { openWebsite(book.url) }
    }

    private fun openChapter(index: Int, offset: Int = 0) {
        val book = novel ?: return
        val chapter = book.chapters.getOrNull(index) ?: return
        savePosition()
        chapterIndex = -1
        load({ openChapter(index, offset) }) {
            val html = repository.chapter(chapter.url)
            clear(chapter.title)
            chapterIndex = index
            button(getString(R.string.novels_chapters), viewBinding.novelControls) {
                savePosition(); chapterIndex = -1; showChapters()
            }
            val sizes = LinearLayout(this@NovelsActivity)
            viewBinding.novelControls.addView(sizes)
            listOf(-2f to "A−", 2f to "A+").forEach { (delta, name) ->
                sizes.addView(Button(this@NovelsActivity).apply {
                    text = name
                    contentDescription = getString(if (delta < 0) R.string.novels_smaller else R.string.novels_larger)
                    setOnClickListener {
                        fontSize = (fontSize + delta).coerceIn(14f, 32f)
                        readingText?.textSize = fontSize
                        progress.edit { putFloat("font_size", fontSize) }
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            label(chapter.title, 24f)
            readingText = label(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT), fontSize).apply {
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.4f)
            }
            if (index > 0) button(getString(R.string.novels_previous_chapter)) { openChapter(index - 1) }
            if (index < book.chapters.lastIndex) button(getString(R.string.novels_next_chapter)) { openChapter(index + 1) }
            button(getString(R.string.novels_open_website)) { openWebsite(chapter.url) }
            viewBinding.novelScroll.post {
                viewBinding.novelScroll.scrollTo(0, offset)
                savePosition()
            }
        }
    }

    private fun openWebsite(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    private fun load(retry: () -> Unit, block: suspend () -> Unit) {
        task?.cancel()
        clear(getString(R.string.novels_title))
        label(getString(R.string.novels_loading))
        task = lifecycleScope.launch {
            try { block() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clear(getString(R.string.novels_title))
                label(getString(R.string.novels_error, e.localizedMessage ?: e.javaClass.simpleName))
                button(getString(R.string.novels_retry)) { retry() }
                button(getString(R.string.novels_open_website)) { openWebsite(novel?.url ?: RoyalRoadRepository.BASE) }
            }
        }
    }

    private fun savePosition() {
        val book = novel ?: return
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        if (readingText == null) return
        progress.edit {
            putString("recent_url", book.url)
            putString("recent_title", book.title)
            putString("chapter:${book.url}", chapter.url)
            putInt("offset:${book.url}", viewBinding.novelScroll.scrollY)
        }
    }

    override fun onStop() { savePosition(); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        savePosition()
        outState.putString("query", query)
        outState.putString("novel", novel?.url)
        outState.putString("chapter", novel?.chapters?.getOrNull(chapterIndex)?.url)
        outState.putInt("offset", viewBinding.novelScroll.scrollY)
        super.onSaveInstanceState(outState)
    }
}
