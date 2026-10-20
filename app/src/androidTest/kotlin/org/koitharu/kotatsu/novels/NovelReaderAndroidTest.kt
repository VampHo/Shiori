package org.koitharu.kotatsu.novels

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Before
import org.junit.Assert.assertEquals
import org.koitharu.kotatsu.R
import java.io.File
import android.graphics.Bitmap
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import kotlinx.coroutines.runBlocking
import android.content.ContentValues
import android.provider.MediaStore
import androidx.recyclerview.widget.RecyclerView
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.runner.RunWith

/** Runs on Android's regex engine, which rejects a bare closing brace unlike the host JVM. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NovelReaderAndroidTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Before fun setUp() {
        hiltRule.inject()
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("shiori_novels", 0).edit().clear().commit()
    }
    @After fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("shiori_novels", 0).edit().clear().commit()
    }

    @Test fun novelNavigationIsVisibleAndFavoritesOpens() {
        ActivityScenario.launch(NovelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val modes = activity.findViewById<TabLayout>(R.id.reading_modes)
                assertEquals(2, modes.tabCount)
                assertEquals(1, modes.selectedTabPosition)
                assertTrue(modes.isShown)
                assertEquals(7, activity.findViewById<RecyclerView>(R.id.novel_grid).adapter!!.itemCount)
                assertTrue(activity.findViewById<BottomNavigationView>(R.id.novel_nav).isShown)
            }
            screenshot("novels-explore.png")
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.novel_nav).selectedItemId = R.id.novel_favorites
                val shelves = activity.findViewById<TabLayout>(R.id.novel_shelves)
                assertTrue(shelves.isShown)
                assertEquals(6, shelves.tabCount)
            }
            screenshot("novels-favorites.png")
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.novel_nav).selectedItemId = R.id.novel_settings
                assertFalse(activity.findViewById<TabLayout>(R.id.novel_shelves).isShown)
            }
            screenshot("novels-settings.png")
        }
    }

    @Test fun importsEpubInSpineOrderAndReadsOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "fixture.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
            entry("OPS/book.opf", """<package><metadata><dc:title>Offline story</dc:title><dc:creator>Test author</dc:creator></metadata><manifest><item id="two" href="two.xhtml"/><item id="one" href="one.xhtml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""")
            entry("OPS/one.xhtml", "<html><body><h1>First</h1><p>Readable offline.</p><script>unwanted()</script></body></html>")
            entry("OPS/two.xhtml", "<html><body><h1>Second</h1><p>The next chapter.</p></body></html>")
        }
        val shelf = NovelShelf(context)
        val book = shelf.importBook(Uri.fromFile(file))
        assertEquals("Offline story", book.title)
        assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
        assertTrue(shelf.cachedChapter(book.chapters.first().url)!!.contains("Readable offline"))
        assertFalse(shelf.cachedChapter(book.chapters.first().url)!!.contains("unwanted"))
        assertEquals(book, NovelShelf(context).cachedDetails(book.url))
        assertEquals(book.link(), shelf.books("downloads").single())
        shelf.setStatus(book.link(), "On-Hold")
        assertEquals("On-Hold", NovelShelf(context).status(book.url))
        shelf.removeDownload(book.link()); file.delete()
        assertFalse(shelf.hasChapter(book.chapters.first().url))
    }

    @Test fun oldFavoritesSurviveAndLibraryShowsCovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shelf = NovelShelf(context)
        shelf.prefs.edit().putString("favorites", """[{"title":"Existing favorite","url":"https://www.royalroad.com/fiction/1"}]""").commit()
        assertEquals("Existing favorite", shelf.books("favorites").single().title)
        assertEquals("royalroad", shelf.books("favorites").single().source)
        val books = (1..9).map { NovelLink("Story $it", "local:test/$it", source = "local") }
        shelf.save("downloads", books)
        ActivityScenario.launch(NovelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.novel_nav).selectedItemId = R.id.novel_favorites
                val grid = activity.findViewById<RecyclerView>(R.id.novel_grid)
                assertTrue(grid.isShown)
                assertEquals(9, grid.adapter!!.itemCount)
                assertEquals(3, (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)
            }
            screenshot("novels-library-grid.png")
        }
    }

    @Test fun coverRepairPreservesDownloadedChapters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shelf = NovelShelf(context)
        val book = NovelDetails("Cover repair", "https://www.royalroad.com/fiction/fixture", "", listOf(NovelLink("One", "local:cover-fixture")), "https://example.org/logo.png")
        shelf.cacheDetails(book); shelf.cacheChapter(book.chapters[0].url, "<p>Keep this download</p>")
        shelf.downloaded(book); shelf.setStatus(book.link(), "Reading")
        assertTrue(shelf.needsCoverRepair(book.link()))
        val fixed = shelf.repairCover(book.link(), "https://example.org/story-cover.jpg")
        assertEquals(fixed.cover, shelf.books("downloads").single().cover)
        assertEquals(fixed.cover, shelf.cachedDetails(book.url)!!.cover)
        assertEquals("Reading", shelf.status(book.url))
        assertTrue(shelf.cachedChapter(book.chapters[0].url)!!.contains("Keep this download"))
        shelf.removeDownload(fixed)
    }

    @Test fun sourceJsonSupportsMultilineAndBracesInsideStrings() {
        val json = SelectedNovelSources.jsonObjectAfter("""window.prefetched = {
            "text": "A {brace} in the story", "nested": {"value": 4}
        }; anotherFunction();""", "window.prefetched")
        assertEquals("A {brace} in the story", json.getString("text"))
        assertEquals(4, json.getJSONObject("nested").getInt("value"))
    }

    @Test fun readerAppearancePersistsAndFullscreenControlsCanBeRestored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("shiori_novels", 0)
        prefs.edit().putString("reader_theme", "Sepia").putString("reader_font", "Monospace")
            .putFloat("font_size", 24f).putBoolean("reader_fullscreen", true).commit()
        val shelf = NovelShelf(context)
        val chapter = NovelLink("Reader test chapter", "local:reader-settings/chapter", source="local")
        val book = NovelDetails("Reader settings test", "local:reader-settings", "", listOf(chapter), source="local")
        shelf.cacheDetails(book); shelf.cacheChapter(chapter.url, "<p>Offline reading sample.</p>".repeat(100))
        try {
            ActivityScenario.launch(NovelsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val method = NovelsActivity::class.java.getDeclaredMethod("openNovel", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(activity, book.url, chapter.url, 0)
                }
                var ready = false
                val deadline = System.currentTimeMillis() + 10000
                while (!ready && System.currentTimeMillis() < deadline) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<android.widget.LinearLayout>(R.id.novel_content)
                        ready = (0 until content.childCount).any { (content.getChildAt(it) as? android.widget.TextView)?.text?.contains("Offline reading sample") == true }
                    }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Cached chapter should open", ready)
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.widget.LinearLayout>(R.id.novel_content)
                    val text = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<android.widget.TextView>().first { it.text.contains("Offline reading sample") }
                    assertFalse(activity.findViewById<android.view.View>(R.id.toolbar).isShown)
                    assertEquals(NovelReaderOptions(activity).colors.second, text.currentTextColor)
                    assertEquals(24f * activity.resources.displayMetrics.scaledDensity, text.textSize, 0.1f)
                    text.performClick()
                    assertTrue(activity.findViewById<android.view.View>(R.id.toolbar).isShown)
                    text.performClick()
                    assertFalse(activity.findViewById<android.view.View>(R.id.toolbar).isShown)
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertTrue(activity.findViewById<android.view.View>(R.id.toolbar).isShown)
                }
                screenshot("novels-reader-sepia.png")
            }
        } finally { shelf.removeDownload(book.link()) }
    }

    @Test fun recentChapterCacheDoesNotEvictDownloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shelf = NovelShelf(context)
        val book = NovelDetails("Download stays", "local:cache-test", "", listOf(NovelLink("Saved", "local:cache-test/saved")), source="local")
        shelf.cacheDetails(book)
        shelf.cacheChapter(book.chapters[0].url, "Permanent chapter")
        try {
            repeat(35) { shelf.cacheRecentChapter("https://example.org/chapter/$it", "Recent $it") }
            assertEquals("Recent 34", NovelShelf(context).recentChapter("https://example.org/chapter/34"))
            assertEquals("Permanent chapter", shelf.cachedChapter(book.chapters[0].url))
            assertTrue(File(context.cacheDir, "novel_recent").listFiles()!!.count { it.extension == "html" } <= 30)
        } finally {
            shelf.removeDownload(book.link())
            File(context.cacheDir, "novel_recent").deleteRecursively()
        }
    }

    @Test fun personalCatalogueExcludesAdultBrokenAndUnselectedSources() {
        val policy = org.koitharu.kotatsu.explore.data.PersonalSourcePolicy
        val all = org.koitharu.kotatsu.parsers.model.MangaParserSource.entries
        val allowed = all.filter(policy::allows)
        assertTrue(allowed.isNotEmpty())
        assertTrue(allowed.all { it.name in policy.sourceNames && !it.isBroken && it.contentType != org.koitharu.kotatsu.parsers.model.ContentType.HENTAI })
        assertTrue(all.filter { it.contentType == org.koitharu.kotatsu.parsers.model.ContentType.HENTAI }.none(policy::allows))
        assertEquals(policy.sourceNames, all.filter { it.name in policy.sourceNames }.map { it.name }.toSet())
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ShioriTests")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun chapterStylesAreParsedOnAndroid() {
        val doc = Jsoup.parse("""
            <style>.hidden { display: none; } .visible { color: red; }</style>
            <div class="chapter-content"><p class="visible">A readable chapter.</p>
            <p class="hidden">Hidden text.</p><p style="visibility: hidden">Hidden inline.</p></div>
        """)
        val html = RoyalRoadRepository.parseChapter(doc)
        assertTrue(html.contains("A readable chapter."))
        assertFalse(html.contains("Hidden"))
    }
}
