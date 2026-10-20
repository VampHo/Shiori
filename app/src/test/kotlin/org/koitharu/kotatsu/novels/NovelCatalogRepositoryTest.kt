package org.koitharu.kotatsu.novels

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NovelCatalogRepositoryTest {
    @Test fun coverAndTitleBelongToTheirOwnBook() {
        val source = NovelCatalogRepository.sources.first()
        val html = """
            <div class="li-row"><h3 class="tit"><a href="/novel/one" title="One">One</a></h3><div class="pic"><img src="/one.jpg"></div></div>
            <div class="li-row"><h3 class="tit"><a href="/novel/two">Two</a></h3><div class="pic"><img data-src="/two.jpg"></div></div>
            <div class="li-row"><h3 class="tit"><a href="https://example.org/novel">External</a></h3></div>
        """
        val books = NovelCatalogRepository.parseList(Jsoup.parse(html, source.base), source)
        assertEquals(listOf("One", "Two"), books.map { it.title })
        assertEquals(listOf("${source.base}/one.jpg", "${source.base}/two.jpg"), books.map { it.cover })
    }
    @Test fun royalRoadCoverIsResolved() {
        val doc = Jsoup.parse("""<div class="fiction-list-item"><figure><img src="/cover.jpg"></figure><h2 class="fiction-title"><a href="/fiction/1">Book</a></h2></div>""", RoyalRoadRepository.BASE)
        assertEquals("${RoyalRoadRepository.BASE}/cover.jpg", RoyalRoadRepository.parseSearch(doc).single().cover)
    }
    @Test fun sourceLookupRejectsLookalikeDomainsAndInsecureUrls() {
        assertNotNull(NovelCatalogRepository.sourceFor("https://freewebnovel.com/novel/test"))
        assertNull(NovelCatalogRepository.sourceFor("https://freewebnovel.com.example.org/test"))
        assertNull(NovelCatalogRepository.sourceFor("http://libread.com/test"))
        assertNull(NovelCatalogRepository.sourceFor("https://libread.com:8443/test"))
    }
}
