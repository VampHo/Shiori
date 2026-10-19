package org.koitharu.kotatsu.novels

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Typeface
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.main.ui.MainActivity
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
import dagger.hilt.android.EntryPointAccessors
import org.koitharu.kotatsu.core.ui.BaseActivityEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityNovelsBinding

/** First integrated novel reader. Manga storage and navigation remain independent. */
class NovelsActivity : BaseActivity<ActivityNovelsBinding>() {
    private val repository = RoyalRoadRepository()
    private val isIncognito: Boolean
        get() = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(applicationContext).settings.isIncognitoModeEnabled
    private val progress by lazy { getSharedPreferences("shiori_novels", MODE_PRIVATE) }
    private var task: Job? = null
    private var novel: NovelDetails? = null
    private var chapterIndex = -1
    private var query = ""
    private var results = emptyList<NovelLink>()
    private var fontSize = 20f
    private var readingText: TextView? = null
    private var page = 0
    private var section = R.id.novel_explore
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun chrome(home: Boolean) {
        findViewById<TabLayout>(R.id.reading_modes).isVisible = home
        viewBinding.toolbar.isVisible = !home
        viewBinding.novelNav.isVisible = home
    }

    private fun savedBooks(key: String): List<NovelLink> = runCatching {
        val array = JSONArray(progress.getString(key, "[]"))
        (0 until array.length()).map { index ->
            val book = array.getJSONObject(index)
            NovelLink(book.getString("title"), book.getString("url"))
        }
    }.getOrDefault(emptyList())

    private fun storeBooks(key: String, books: List<NovelLink>) {
        val array = JSONArray()
        books.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        progress.edit { putString(key, array.toString()) }
    }

    private fun bookRow(book: NovelLink, subtitle: String, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            contentDescription = "${book.title}. $subtitle"
            setOnClickListener { action() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        column.addView(TextView(this).apply {
            text = book.title; textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = subtitle; textSize = 14f; alpha = 0.72f
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(column)
        viewBinding.novelContent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityNovelsBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(true, false)
        findViewById<TabLayout>(R.id.reading_modes).apply {
            getTabAt(1)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (tab.position == 0) {
                        savePosition()
                        startActivity(Intent(this@NovelsActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        section = savedInstanceState?.getInt("section", R.id.novel_explore) ?: R.id.novel_explore
        viewBinding.novelNav.selectedItemId = section
        viewBinding.novelNav.setOnItemSelectedListener {
            task?.cancel()
            section = it.itemId
            novel = null
            chapterIndex = -1
            showSearch()
            true
        }
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
        setPadding(0, dp(8), 0, dp(8))
        viewBinding.novelContent.addView(this)
    }

    private fun button(text: String, parent: LinearLayout = viewBinding.novelContent, action: () -> Unit) {
        parent.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            cornerRadius = dp(24)
            minHeight = dp(48)
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun showSearch() {
        clear(getString(R.string.novels_title))
        chrome(true)
        if (section != R.id.novel_explore) {
            val favorites = section == R.id.novel_favorites
            label(getString(if (favorites) R.string.favourites else R.string.history), 28f)
            val books = if (!favorites && isIncognito) emptyList() else savedBooks(if (favorites) "favorites" else "history")
            if (books.isEmpty()) {
                label(getString(if (favorites) R.string.novels_empty_favorites else R.string.novels_empty_history))
                button(getString(R.string.novels_find_books)) { viewBinding.novelNav.selectedItemId = R.id.novel_explore }
            }
            books.forEach { book ->
                bookRow(book, getString(if (favorites) R.string.novels_source_name else R.string.novels_resume)) {
                    openNovel(book.url, if (isIncognito) null else progress.getString("chapter:${book.url}", null), progress.getInt("offset:${book.url}", 0))
                }
            }
            return
        }
        val field = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat())
            hint = getString(R.string.novels_search_hint)
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(android.R.drawable.ic_menu_search)
            endIconContentDescription = getString(R.string.novels_search)
        }
        val input = TextInputEditText(field.context).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(query)
        }
        field.addView(input)
        viewBinding.novelControls.addView(field)
        fun submit() {
            query = input.text.toString().trim()
            if (query.isNotEmpty()) {
                WindowInsetsControllerCompat(window, input).hide(WindowInsetsCompat.Type.ime())
                input.clearFocus()
                search()
            }
        }
        field.setEndIconOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        val recent = progress.getString("recent_url", null)
        if (recent != null && !isIncognito && results.isEmpty()) {
            label(getString(R.string.novels_resume), 20f)
            bookRow(NovelLink(progress.getString("recent_title", "").orEmpty(), recent), getString(R.string.novels_resume)) {
                openNovel(recent, progress.getString("chapter:$recent", null), progress.getInt("offset:$recent", 0))
            }
        }
        label(getString(if (results.isEmpty()) R.string.explore else R.string.novels_results), 28f)
        label(getString(R.string.novels_source_name), 14f)
        if (results.isEmpty()) {
            label(getString(if (isIncognito) R.string.novels_incognito_prompt else R.string.novels_explore_hint))
        }
        results.forEach { book -> bookRow(book, getString(R.string.novels_source_name)) { openNovel(book.url) } }
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
        chrome(false)
        chapterIndex = -1
        label(book.title, 28f)
        label(getString(R.string.novels_source_name), 14f)
        val isFavorite = savedBooks("favorites").any { it.url == book.url }
        button(getString(if (isFavorite) R.string.novels_remove_favorite else R.string.novels_add_favorite)) {
            val saved = savedBooks("favorites").filterNot { it.url == book.url }
            storeBooks("favorites", if (isFavorite) saved else listOf(NovelLink(book.title, book.url)) + saved)
            showChapters()
        }
        val lastUrl = progress.getString("chapter:${book.url}", null)
        val last = book.chapters.indexOfFirst { it.url == lastUrl }
        if (last >= 0 && !isIncognito) button(getString(R.string.novels_resume)) {
            openChapter(last, progress.getInt("offset:${book.url}", 0))
        }
        label(book.synopsis).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { maxLines = if (maxLines == 4) Int.MAX_VALUE else 4 }
        }
        label(getString(R.string.novels_chapter_count, book.chapters.size), 20f)
        book.chapters.drop(page * 100).take(100).forEachIndexed { index, chapter ->
            val chapterRow = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = chapter.title
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(52)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { openChapter(page * 100 + index) }
            }
            viewBinding.novelContent.addView(chapterRow, LinearLayout.LayoutParams(-1, -2))
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
            chrome(false)
            chapterIndex = index
            button(getString(R.string.novels_chapters), viewBinding.novelControls) {
                savePosition(); chapterIndex = -1; showChapters()
            }
            val sizes = LinearLayout(this@NovelsActivity)
            viewBinding.novelControls.addView(sizes)
            listOf(-2f to "A−", 2f to "A+").forEach { (delta, name) ->
                sizes.addView(MaterialButton(this@NovelsActivity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
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
                typeface = Typeface.create("serif", Typeface.NORMAL)
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
        chrome(novel == null)
        viewBinding.novelContent.addView(CircularProgressIndicator(this).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(48) })
        label(getString(R.string.novels_loading)).gravity = Gravity.CENTER
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
        if (isIncognito) return
        val book = novel ?: return
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        if (readingText == null) return
        val history = savedBooks("history")
        if (history.firstOrNull()?.url != book.url) {
            storeBooks("history", (listOf(NovelLink(book.title, book.url)) + history.filterNot { it.url == book.url }).take(100))
        }
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
        if (isIncognito) {
            super.onSaveInstanceState(outState)
            return
        }
        outState.putInt("section", section)
        outState.putString("query", query)
        outState.putString("novel", novel?.url)
        outState.putString("chapter", novel?.chapters?.getOrNull(chapterIndex)?.url)
        outState.putInt("offset", viewBinding.novelScroll.scrollY)
        super.onSaveInstanceState(outState)
    }
}
