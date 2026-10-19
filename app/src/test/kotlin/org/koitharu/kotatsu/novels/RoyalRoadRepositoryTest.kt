package org.koitharu.kotatsu.novels

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RoyalRoadRepositoryTest {
    @Test fun searchResolvesUrlsAndRejectsExternalLinks() {
        val doc = Jsoup.parse("""
            <div class="fiction-list-item"><h2 class="fiction-title"><a href="/fiction/123/story">Story &amp; Friends</a></h2></div>
            <div class="fiction-list-item"><h2 class="fiction-title"><a href="https://example.com/fiction/1">Other</a></h2></div>
        """, RoyalRoadRepository.BASE)
        assertEquals(listOf(NovelLink("Story & Friends", "${RoyalRoadRepository.BASE}/fiction/123/story")), RoyalRoadRepository.parseSearch(doc))
    }

    @Test fun chapterListPreservesOrderAndDeduplicates() {
        val doc = Jsoup.parse("""
            <h1 class="font-white">Story</h1><div class="description"><div>Summary</div></div>
            <table id="chapters"><tbody>
                <tr><td><a href="/fiction/1/chapter/10/one">One</a></td></tr>
                <tr><td><a href="/fiction/1/chapter/20/two">Two</a></td></tr>
                <tr><td><a href="/fiction/1/chapter/20/two">Two</a></td></tr>
            </tbody></table>
        """, RoyalRoadRepository.BASE)
        val details = RoyalRoadRepository.parseDetails(doc, "${RoyalRoadRepository.BASE}/fiction/1")
        assertEquals(listOf("One", "Two"), details.chapters.map { it.title })
        assertEquals("Summary", details.synopsis)
    }

    @Test fun removesHiddenAndExecutableContentButKeepsVisibleText() {
        val doc = Jsoup.parse("""
            <style>.hidden-text { display:none; } .visible-text { color:red; }</style>
            <div class="chapter-content"><p class="visible-text">Keep this</p><p class="hidden-text">Remove hidden</p>
            <p style="display: none">Remove inline</p><script>alert('bad')</script><iframe src="https://example.com"></iframe></div>
        """)
        val html = RoyalRoadRepository.parseChapter(doc)
        assertTrue(html.contains("Keep this"))
        assertFalse(html.contains("Remove"))
        assertFalse(html.contains("script"))
        assertFalse(html.contains("iframe"))
    }

    @Test(expected = IOException::class) fun rejectsVerificationPage() {
        RoyalRoadRepository.parseChapter(Jsoup.parse("<h1>Verify your browser</h1>"))
    }

    @Test fun allowsOnlyHttpsRoyalRoad() {
        assertTrue(RoyalRoadRepository.isAllowed("https://www.royalroad.com/fiction/1"))
        assertFalse(RoyalRoadRepository.isAllowed("http://www.royalroad.com/fiction/1"))
        assertFalse(RoyalRoadRepository.isAllowed("https://www.royalroad.com.evil.example/"))
    }
}
