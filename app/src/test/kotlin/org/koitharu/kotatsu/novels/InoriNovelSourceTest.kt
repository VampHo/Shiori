package org.koitharu.kotatsu.novels

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class InoriNovelSourceTest {
    @Test fun searchesAccentsAndResolvesCover() {
        val doc=Jsoup.parse("""<article class="card" data-t="Cafe" data-d="2024" data-v="2" data-tags="fantasy">
            <a href="/novel/one"><img src="//cdn.lnori.com/one.jpg"><h2>Café Stories</h2></a></article>""",InoriNovelSource.BASE)
        val books=InoriNovelSource.parseCatalogue(doc,"cafe","data-d","Fantasy")
        assertEquals(1,books.size)
        assertEquals("https://lnori.com/novel/one",books.single().url)
        assertEquals("https://cdn.lnori.com/one.jpg",books.single().cover)
    }
    @Test fun readsVolumeLinksInSourceOrder() {
        val doc=Jsoup.parse("""<section class="hero-section"><h1>Sample</h1><figure><img src="/cover.jpg"></figure></section>
            <section class="content-section"><section class="vol-grid">
            <article><h3 class="card-title">Volume 1</h3><figure><a href="/book/one">Read</a></figure></article>
            <article><h3 class="card-title">Volume 2</h3><figure><a href="/book/two">Read</a></figure></article>
            </section></section>""",InoriNovelSource.BASE)
        val book=InoriNovelSource.parseDetails(doc,"https://lnori.com/novel/sample")
        assertEquals(listOf("Volume 1","Volume 2"),book.chapters.map { it.title })
        assertEquals("https://lnori.com/book/one",book.chapters.first().url)
    }
}
