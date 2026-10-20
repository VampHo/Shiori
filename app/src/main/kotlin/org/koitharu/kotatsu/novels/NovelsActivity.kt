package org.koitharu.kotatsu.novels

import coil3.request.target
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Typeface
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import org.koitharu.kotatsu.main.ui.MainActivity
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
    private val repository = NovelCatalogRepository()
    private val shelf by lazy { NovelShelf(this) }
    private val images by lazy { coil3.ImageLoader.Builder(applicationContext).components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory()) }.build() }
    private var selectedSource: NovelSource? = null
    private var browsePage = 1
    private var order = ""
    private var genre = ""
    private var shelfTab = "Downloads"
    private var libraryQuery = ""
    private var listMode = false
    private var catalogLoaded = false
    private var downloadJob: Job? = null
    private val importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>> = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load({ importLauncher.launch(arrayOf("application/epub+zip", "text/plain")) }) {
            novel = shelf.importBook(uri)
            chapterIndex = -1
            page = 0
            showChapters()
        }
    }
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
        viewBinding.novelShelves.isVisible = home && section == R.id.novel_favorites
    }

    private fun savedBooks(key: String) = shelf.books(key)
    private fun storeBooks(key: String, books: List<NovelLink>) = shelf.save(key, books)

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
        listMode = progress.getBoolean("list_mode", false)
        selectedSource = NovelCatalogRepository.sources.firstOrNull { it.id == savedInstanceState?.getString("source") }
        browsePage = savedInstanceState?.getInt("browse_page", 1) ?: 1
        order = savedInstanceState?.getString("order").orEmpty()
        genre = savedInstanceState?.getString("genre").orEmpty()
        shelfTab = savedInstanceState?.getString("shelf_tab") ?: "Downloads"
        listOf("Downloads", "Reading", "On-Hold", "Plan to Read", "Completed", "All").forEach {
            viewBinding.novelShelves.addTab(viewBinding.novelShelves.newTab().setText(it))
        }
        viewBinding.novelShelves.getTabAt((0 until viewBinding.novelShelves.tabCount).firstOrNull {
            viewBinding.novelShelves.getTabAt(it)?.text == shelfTab
        } ?: 0)?.select()
        viewBinding.novelShelves.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { shelfTab = tab.text.toString(); showSearch() }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        section = savedInstanceState?.getInt("section", R.id.novel_explore) ?: R.id.novel_explore
        viewBinding.novelNav.selectedItemId = section
        viewBinding.novelNav.setOnItemSelectedListener {
            task?.cancel()
            savePosition()
            section = it.itemId
            libraryQuery = ""
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
        } else if (selectedSource != null && section == R.id.novel_explore) browse() else showSearch()
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
        } else if (selectedSource != null && section == R.id.novel_explore) {
            selectedSource = null; results = emptyList(); query = ""; catalogLoaded = false; showSearch()
        } else finish()
    }

    private fun clear(title: String) {
        supportActionBar?.title = title
        readingText = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewBinding.novelGrid.isVisible = false
        viewBinding.novelGrid.adapter = null
        viewBinding.novelScroll.isVisible = true
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

    private fun header(hint: String, value: String, actionIcon: Int, actionDescription: String,
                       action: () -> Unit, submit: (String) -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(com.google.android.material.color.MaterialColors.getColor(viewBinding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
        }
        fun icon(res: Int, description: String, click: () -> Unit) = androidx.appcompat.widget.AppCompatImageButton(this).apply {
            setImageResource(res); contentDescription = description
            setBackgroundResource(android.R.color.transparent)
            imageTintList = android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(viewBinding.root, com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { click() }
        }
        val input = androidx.appcompat.widget.AppCompatEditText(this).apply {
            this.hint = hint; setText(value); textSize = 16f; setSingleLine(true)
            background = null; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(4), 0, dp(4), 0)
        }
        fun searchNow() {
            WindowInsetsControllerCompat(window, input).hide(WindowInsetsCompat.Type.ime())
            input.clearFocus(); submit(input.text.toString().trim())
        }
        row.addView(icon(android.R.drawable.ic_menu_search, "Search") { searchNow() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(icon(actionIcon, actionDescription, action), LinearLayout.LayoutParams(dp(48), dp(48)))
        viewBinding.novelControls.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        input.setOnEditorActionListener { _, code, _ -> if (code == EditorInfo.IME_ACTION_SEARCH) { searchNow(); true } else false }
    }

    private fun choice(title: String, options: List<String>, selected: String, action: (String) -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), options.indexOf(selected)) { dialog, which ->
                dialog.dismiss(); action(options[which])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun compactRow(items: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this)
        items.forEach { (name, action) ->
            row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = name; textSize = 12f; isAllCaps = false; minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        viewBinding.novelControls.addView(row)
    }

    private fun grid(books: List<NovelLink>, sources: Boolean = false) {
        viewBinding.novelScroll.isVisible = false
        viewBinding.novelGrid.isVisible = true
        viewBinding.novelGrid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, if (listMode && !sources) 1 else 3)
        viewBinding.novelGrid.adapter = NovelGridAdapter(images, books, sources, listMode && !sources, onClick = { book ->
            if (sources) {
                selectedSource = NovelCatalogRepository.sources.first { it.id == book.source }
                order = selectedSource!!.orders.first().second; genre = ""; query = ""; browsePage = 1; results = emptyList(); browse()
            } else openNovel(book.url, if (section == R.id.novel_history && !isIncognito) progress.getString("chapter:${book.url}", null) else null,
                if (isIncognito) 0 else progress.getInt("offset:${book.url}", 0))
        }, onLongClick = { book -> if (!sources) bookActions(book) })
    }

    private fun empty(message: String, action: String? = null, click: () -> Unit = {}) {
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_book_page); alpha = 0.5f
            imageTintList = android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(viewBinding.root, androidx.appcompat.R.attr.colorPrimary))
        }
        viewBinding.novelContent.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(64); bottomMargin = dp(16) })
        label(message).apply { gravity = Gravity.CENTER; alpha = 0.8f }
        if (action != null) button(action, action = click)
    }

    private fun showSearch() {
        clear(getString(R.string.novels_title)); chrome(true)
        when (section) {
            R.id.novel_settings -> { showSettings(); return }
            R.id.novel_favorites, R.id.novel_history -> {
                val library = section == R.id.novel_favorites
                header(if (library) "Search $shelfTab…" else "Search history…", libraryQuery,
                    if (library) android.R.drawable.ic_input_add else R.drawable.ic_grid,
                    if (library) "Import EPUB or text book" else "Change layout",
                    { if (library) importLauncher.launch(arrayOf("application/epub+zip", "text/plain")) else toggleLayout() }) { libraryQuery = it; showSearch() }
                compactRow(listOf((if (listMode) "Grid view" else "List view") to { toggleLayout() }, "Sort" to {
                    choice("Sort books", listOf("Recently added", "Title A–Z", "Title Z–A"), progress.getString("shelf_sort", "Recently added").orEmpty()) {
                        progress.edit { putString("shelf_sort", it) }; showSearch()
                    }
                }))
                var books = if (!library) { if (isIncognito) emptyList() else savedBooks("history") }
                    else if (shelfTab == "Downloads") savedBooks("downloads") else savedBooks("favorites").filter { shelfTab == "All" || shelf.status(it.url) == shelfTab }
                books = books.filter { it.title.contains(libraryQuery, true) }
                books = when (progress.getString("shelf_sort", "Recently added")) { "Title A–Z" -> books.sortedBy { it.title.lowercase() }; "Title Z–A" -> books.sortedByDescending { it.title.lowercase() }; else -> books }
                if (books.isEmpty()) empty(if (libraryQuery.isNotBlank()) "No matching books" else if (library) "No books in $shelfTab yet" else "Your reading history will appear here",
                    if (library && shelfTab == "Downloads") "+ Import a book" else "Explore novels") {
                    if (library && shelfTab == "Downloads") importLauncher.launch(arrayOf("application/epub+zip", "text/plain")) else viewBinding.novelNav.selectedItemId = R.id.novel_explore
                } else grid(books)
            }
            else -> {
                val source = selectedSource
                if (source == null) {
                    header("Search novel sources…", libraryQuery, R.drawable.ic_filter_menu, "Source information", {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Novel sources")
                            .setMessage("Choose a source to browse or search its novels. Each source has its own catalogue. Available language: English.")
                            .setPositiveButton("OK", null).show()
                    }) { libraryQuery = it; showSearch() }
                    val sources = NovelCatalogRepository.sources.filter { it.name.contains(libraryQuery, true) }.map {
                        NovelLink(it.name, it.base, "${it.base}/favicon.ico", it.id)
                    }
                    if (sources.isEmpty()) empty("No matching sources") else grid(sources, true)
                } else {
                    header("Search ${source.name}…", query, R.drawable.ic_language, "Choose source", {
                        selectedSource = null; results = emptyList(); query = ""; libraryQuery = ""; showSearch()
                    }) { query = it; browsePage = 1; browse() }
                    compactRow(listOf("${source.orders.firstOrNull { it.second == order }?.first ?: source.orders.first().first} ▾" to {
                        choice("Browse order", source.orders.map { it.first }, source.orders.firstOrNull { it.second == order }?.first.orEmpty()) { name ->
                            order = source.orders.first { it.first == name }.second; query = ""; browsePage = 1; browse()
                        }
                    }, "${genre.ifBlank { "All genres" }} ▾" to {
                        choice("Genre", source.genres.map { it.first }, genre.ifBlank { "All genres" }) { name ->
                            genre = source.genres.first { it.first == name }.second; query = ""; browsePage = 1; browse()
                        }
                    }, (if (listMode) "Grid" else "List") to { toggleLayout() }))
                    if (query.isBlank()) compactRow(listOf("‹ Previous" to { if (browsePage > 1) { browsePage--; browse() } }, "Page $browsePage" to {}, "Next ›" to { if (results.isNotEmpty()) { browsePage++; browse() } }))
                    if (results.isEmpty()) empty(if (catalogLoaded) "No novels found" else "Browse ${source.name}", "Reload") { browse() } else grid(results)
                }
            }
        }
    }

    private fun toggleLayout() { listMode = !listMode; progress.edit { putBoolean("list_mode", listMode) }; showSearch() }

    private fun browse() {
        val source = selectedSource ?: return
        if (order.isBlank()) order = source.orders.first().second
        load({ browse() }) {
            novel = null; chapterIndex = -1
            results = if (query.isBlank()) repository.browse(source, browsePage, order, genre) else repository.search(source, query)
            catalogLoaded = true; showSearch()
        }
    }

    private fun bookActions(book: NovelLink) {
        val options = listOf("Reading", "On-Hold", "Plan to Read", "Completed", "Remove from library") +
            if (savedBooks("downloads").any { it.url == book.url }) listOf("Delete downloaded copy") else emptyList()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(book.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (val value = options[which]) {
                    "Remove from library" -> { storeBooks("favorites", savedBooks("favorites").filterNot { it.url == book.url }); refreshCurrent() }
                    "Delete downloaded copy" -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setMessage("Delete the offline copy of this book?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> shelf.removeDownload(book); refreshCurrent() }.show()
                    else -> { shelf.setStatus(book, value); refreshCurrent() }
                }
            }.show()
    }
    private fun refreshCurrent() { if (novel != null) showChapters() else showSearch() }

    private fun showSettings() {
        label("Novel settings", 24f)
        button("Text size · ${fontSize.toInt()}") {
            choice("Reading text size", (14..32 step 2).map { it.toString() }, fontSize.toInt().toString()) {
                fontSize = it.toFloat(); progress.edit { putFloat("font_size", fontSize) }; showSearch()
            }
        }
        button("Typeface · ${progress.getString("reader_font", "Serif")}") {
            choice("Reader typeface", listOf("Serif", "Sans serif"), progress.getString("reader_font", "Serif").orEmpty()) {
                progress.edit { putString("reader_font", it) }; showSearch()
            }
        }
        button("Line spacing · ${progress.getString("reader_spacing", "Comfortable")}") {
            choice("Line spacing", listOf("Compact", "Comfortable", "Spacious"), progress.getString("reader_spacing", "Comfortable").orEmpty()) {
                progress.edit { putString("reader_spacing", it) }; showSearch()
            }
        }
        val keep = progress.getBoolean("keep_screen", false)
        button("Keep screen on while reading · ${if (keep) "On" else "Off"}") { progress.edit { putBoolean("keep_screen", !keep) }; showSearch() }
        button("Default layout · ${if (listMode) "List" else "Grid"}") { toggleLayout() }
        label("Imports: EPUB and UTF-8 text. Downloaded chapters and imported books are available offline.", 14f)
        label("Long-press a cover to organize your library. Reading progress is saved automatically unless incognito mode is enabled.", 14f)
    }

    private fun openNovel(url: String, resumeChapter: String? = null, offset: Int = 0) {
        load({ openNovel(url, resumeChapter, offset) }) {
            novel = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                shelf.cachedDetails(url) ?: repository.details(url).also { shelf.cacheDetails(it) }
            }
            shelf.remember(novel!!.link())
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
        val cover = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        viewBinding.novelContent.addView(cover, LinearLayout.LayoutParams(dp(120), dp(174)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        images.enqueue(coil3.request.ImageRequest.Builder(this).data(book.cover.ifBlank { null }).target(cover).build())
        label(book.title, 24f)
        label(listOf(book.author, if (book.source == "local") "Imported book" else NovelCatalogRepository.sourceFor(book.url)?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), 14f)
        button(if (savedBooks("favorites").any { it.url == book.url }) "Library · ${shelf.status(book.url)}" else "Add to library") { bookActions(book.link()) }
        button(if (downloadJob?.isActive == true) "Download in progress…" else "Download chapters") { downloadChapters(book) }
        if (book.source != "local") button("Refresh chapter list") {
            load({ openNovel(book.url) }) {
                novel = repository.details(book.url)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shelf.cacheDetails(novel!!) }
                showChapters()
            }
        }
        val lastUrl = progress.getString("chapter:${book.url}", null)
        val last = book.chapters.indexOfFirst { it.url == lastUrl }
        if (last < 0 || isIncognito) button("Start reading") { openChapter(0) }
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
                text = (if (shelf.hasChapter(chapter.url)) "↓  " else "") + chapter.title
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
        if (book.source != "local") button(getString(R.string.novels_open_website)) { openWebsite(book.url) }
    }

    private fun openChapter(index: Int, offset: Int = 0) {
        val book = novel ?: return
        val chapter = book.chapters.getOrNull(index) ?: return
        savePosition()
        chapterIndex = -1
        load({ openChapter(index, offset) }) {
            val html = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                shelf.cachedChapter(chapter.url) ?: repository.chapter(chapter.url)
            }
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
                typeface = Typeface.create(if (progress.getString("reader_font", "Serif") == "Serif") "serif" else "sans-serif", Typeface.NORMAL)
                setTextIsSelectable(true)
                setLineSpacing(0f, when (progress.getString("reader_spacing", "Comfortable")) { "Compact" -> 1.2f; "Spacious" -> 1.7f; else -> 1.4f })
            }
            if (progress.getBoolean("keep_screen", false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isIncognito && shelf.status(book.url) != "Completed") shelf.setStatus(book.link(), "Reading")
            if (index > 0) button(getString(R.string.novels_previous_chapter)) { openChapter(index - 1) }
            if (index < book.chapters.lastIndex) button(getString(R.string.novels_next_chapter)) { openChapter(index + 1) }
            if (book.source != "local") button(getString(R.string.novels_open_website)) { openWebsite(chapter.url) }
            viewBinding.novelScroll.post {
                viewBinding.novelScroll.scrollTo(0, offset)
                savePosition()
            }
        }
    }

    private fun downloadChapters(book: NovelDetails) {
        if (book.source == "local") { android.widget.Toast.makeText(this, "This book is already available offline", android.widget.Toast.LENGTH_SHORT).show(); return }
        if (downloadJob?.isActive == true) { android.widget.Toast.makeText(this, "A download is already running", android.widget.Toast.LENGTH_SHORT).show(); return }
        val choices = listOf("Next 10 chapters", "Next 50 chapters", "All chapters")
        choice("Download ${book.title}", choices, choices.first()) { selected ->
            val lastUrl = progress.getString("chapter:${book.url}", null)
            val start = book.chapters.indexOfFirst { it.url == lastUrl }.coerceAtLeast(0)
            val chapters = if (selected == "All chapters") book.chapters else book.chapters.drop(start).take(if (selected == choices.first()) 10 else 50)
            val indicator = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { max = chapters.size }
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Downloading chapters")
                .setMessage("Keep this screen open. Completed chapters are retained if you cancel.")
                .setView(indicator).setNegativeButton("Cancel") { _, _ -> downloadJob?.cancel() }.create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnCancelListener { downloadJob?.cancel() }
            dialog.show()
            downloadJob = lifecycleScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shelf.cacheDetails(book) }
                    for ((index, chapter) in chapters.withIndex()) {
                        if (!shelf.hasChapter(chapter.url)) {
                            val html = repository.chapter(chapter.url)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shelf.cacheChapter(chapter.url, html) }
                            shelf.downloaded(book)
                            kotlinx.coroutines.delay(1000)
                        } else shelf.downloaded(book)
                        indicator.progress = index + 1
                    }
                    android.widget.Toast.makeText(this@NovelsActivity, "Chapters saved for offline reading", android.widget.Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.widget.Toast.makeText(this@NovelsActivity, "Download stopped: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                finally { dialog.dismiss(); if (!isFinishing && !isDestroyed && novel?.url == book.url && chapterIndex < 0) showChapters() }
            }
        }
    }

    private fun openWebsite(url: String) {
        if (url.startsWith("local:")) return
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
                button(getString(R.string.novels_open_website)) { openWebsite(novel?.url ?: selectedSource?.base ?: RoyalRoadRepository.BASE) }
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
            storeBooks("history", (listOf(book.link()) + history.filterNot { it.url == book.url }).take(100))
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
        outState.putString("source", selectedSource?.id)
        outState.putInt("browse_page", browsePage)
        outState.putString("order", order)
        outState.putString("genre", genre)
        outState.putString("shelf_tab", shelfTab)
        outState.putInt("section", section)
        outState.putString("query", query)
        outState.putString("novel", novel?.url)
        outState.putString("chapter", novel?.chapters?.getOrNull(chapterIndex)?.url)
        outState.putInt("offset", viewBinding.novelScroll.scrollY)
        super.onSaveInstanceState(outState)
    }
}
