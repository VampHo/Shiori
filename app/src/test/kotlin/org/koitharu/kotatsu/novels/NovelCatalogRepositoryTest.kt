package org.koitharu.kotatsu.novels

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NovelCatalogRepositoryTest {
    @Test fun coverPrefersA3ImageBeforePictureVariants() {
        val doc = Jsoup.parse("""<div class="pic"><picture>
            <source srcset="/alternative.avif 900w"><img data-src="/original.jpg" src="/loading.gif">
            </picture></div>""", "https://novelfire.net")
        assertEquals("https://novelfire.net/original.jpg", NovelCatalogRepository.cover(doc))
        assertEquals("https://novelfire.net/original.jpg", NovelCatalogRepository.detailCover(doc))
    }

    @Test fun detailCoverNeverUsesTheSiteLogo() {
        val doc = Jsoup.parse("""<header><img src="/logo.png"></header>
            <div class="pic"><picture><source srcset="/cover-small.jpg 200w, /cover-large.jpg 600w"><img src="/loading.gif"></picture></div>""", "https://freewebnovel.com")
        assertEquals("https://freewebnovel.com/cover-large.jpg", NovelCatalogRepository.detailCover(doc))
        assertEquals("", NovelCatalogRepository.detailCover(Jsoup.parse("<header><img src='/site.png'></header>", "https://freewebnovel.com")))
    }
    @Test fun catalogueContainsExactlyRequestedSources() {
        assertEquals(listOf("NovelFire", "Royal Road", "Wattpad", "FenrirRealm", "WuxiaWorld", "NovelFull", "ReadHive"), NovelCatalogRepository.sources.map { it.name })
        // Existing downloads from retired catalogue sources must remain readable.
        assertNotNull(NovelCatalogRepository.sourceFor("https://freewebnovel.com/novel/one"))
    }
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
