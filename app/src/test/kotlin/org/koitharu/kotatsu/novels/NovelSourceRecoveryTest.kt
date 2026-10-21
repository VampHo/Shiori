package org.koitharu.kotatsu.novels

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovelSourceRecoveryTest {
    @Test fun sourceAliasesAllowOfficialLiteButRejectLookalikes() {
        assertEquals("wuxiaworld", NovelCatalogRepository.sourceFor("https://lite.wuxiaworld.com/novel/test")?.id)
        assertEquals("novelfire", NovelCatalogRepository.sourceFor("https://www.novelfire.net/book")?.id)
        assertNull(NovelCatalogRepository.sourceFor("https://lite.wuxiaworld.com.attacker.test/novel/test"))
        assertNull(NovelCatalogRepository.sourceFor("https://other.wuxiaworld.com/novel/test"))
        assertNull(NovelCatalogRepository.sourceFor("http://lite.wuxiaworld.com/novel/test"))
    }

    @Test fun fenrirResolvesNamedReferencesInsteadOfFixedOffsets() {
        val raw = """{"nodes":[{"type":"data","data":[{"hasSession":1},false]},
            {"type":"skip"},{"type":"data","data":[{"chapterData":4},"html",
            "<p>A readable chapter.</p><script>bad()</script>","Unrelated title",
            {"content":2,"content_format":1}]}]}"""
        val html = SelectedNovelSources.fenrirChapter(raw, "https://fenrirealm.com/series/book/one/__data.json")
        assertTrue(html.contains("A readable chapter."))
        assertFalse(html.contains("Unrelated title"))
        assertFalse(html.contains("script"))
    }

    @Test fun fenrirLockedContentOffersTheActualChapterPage() {
        val raw = """{"nodes":[{"type":"data","data":[{"chapterData":1},
            {"content":2,"content_format":3},"","locked"]}]}"""
        try {
            SelectedNovelSources.fenrirChapter(raw, "https://fenrirealm.com/series/book/one/__data.json?x=1")
            fail("Locked response must not become an empty chapter")
        } catch (e: NovelAccessException) {
            assertEquals("https://fenrirealm.com/series/book/one", e.address)
        }
    }

    @Test fun wuxiaReadsOfficialLiteContentAndPreservesBookAddress() = runBlocking {
        val source = NovelCatalogRepository.sources.first { it.id == "wuxiaworld" }
        var requested = ""
        val adapter = SelectedNovelSources { url, _ ->
            requested = url
            "<main><div id='chapter-body'><p>A readable chapter.</p></div></main>"
        }
        val html = adapter.chapter(source, "https://www.wuxiaworld.com/novel/book/chapter-1")
        assertEquals("https://lite.wuxiaworld.com/novel/book/chapter-1", requested)
        assertTrue(html.contains("A readable chapter."))
    }
}
