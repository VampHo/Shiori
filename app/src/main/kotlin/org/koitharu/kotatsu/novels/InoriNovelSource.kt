/* Adapted from QuickNovel LnoriProvider (GPL-3.0); see THIRD_PARTY_NOTICES.md. */
package org.koitharu.kotatsu.novels

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.text.Normalizer

class InoriNovelSource(private val request: suspend (String, Map<String,String>?) -> String) {
    private val mutex=Mutex()
    private var library:Document?=null
    private var fetchedAt=0L
    private suspend fun catalogue():Document=mutex.withLock {
        val now=System.currentTimeMillis()
        library?.takeIf { now-fetchedAt < 10*60*1000 } ?: Jsoup.parse(request("$BASE/library",null),"$BASE/library").also {
            if(it.select("article.card").isEmpty()) throw IOException("Inori catalogue unavailable. The source may require browser verification.")
            library=it; fetchedAt=now
        }
    }
    suspend fun browse(page:Int,order:String,genre:String):List<NovelLink> = parseCatalogue(catalogue(),"",order,genre).drop((page-1).coerceAtLeast(0)*24).take(24)
    suspend fun search(query:String):List<NovelLink> = parseCatalogue(catalogue(),query,"","")
    suspend fun details(url:String):NovelDetails = parseDetails(Jsoup.parse(request(url,null),url),url)
    suspend fun chapter(url:String):String {
        val doc=Jsoup.parse(request(url,null),url)
        val sections=doc.select("main > article > section")
        if(sections.text().length < 20) throw IOException("Inori reading text unavailable. Open the source to check availability.")
        sections.select("script,style,iframe,nav,button,[hidden]").remove()
        return Jsoup.clean(sections.joinToString("\n") { it.html() },Safelist.basic().removeTags("a"))
    }
    companion object {
        const val BASE="https://lnori.com"
        private fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase().replace(Regex("[^a-z0-9]"),"")
        fun parseCatalogue(doc:Document,query:String,order:String,genre:String):List<NovelLink> {
            val cards=doc.select("article.card").filter { card ->
                (genre.isBlank() || card.attr("data-tags").lowercase().contains(genre.lowercase())) && normalize(card.selectFirst("h2")?.text().orEmpty()).contains(normalize(query))
            }
            val sorted=when(order) {
                "data-t" -> cards.sortedBy { it.attr(order).ifBlank { it.selectFirst("h2")?.text().orEmpty() }.lowercase() }
                "data-d","data-v" -> cards.sortedByDescending { it.attr(order).toIntOrNull() ?: 0 }
                else -> cards
            }
            return sorted.mapNotNull { card ->
                val a=card.selectFirst("a[href]") ?: return@mapNotNull null
                val title=card.selectFirst("h2")?.text().orEmpty()
                val url=a.absUrl("href")
                if(title.isBlank() || NovelCatalogRepository.sourceFor(url)?.id!="inori") null else NovelLink(title,url,NovelCatalogRepository.cover(card),"inori")
            }.distinctBy { it.url }
        }
        fun parseDetails(doc:Document,url:String):NovelDetails {
            val info=doc.selectFirst("section.hero-section") ?: throw IOException("Inori details unavailable")
            val title=info.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: throw IOException("Inori title unavailable")
            val chapters=doc.select("section.content-section > section.vol-grid > article").mapNotNull {
                val name=it.selectFirst("h3.card-title")?.text().orEmpty()
                val href=it.selectFirst("figure > a")?.absUrl("href").orEmpty()
                if(name.isBlank() || NovelCatalogRepository.sourceFor(href)?.id!="inori") null else NovelLink(name,href,source="inori")
            }.distinctBy { it.url }
            if(chapters.isEmpty()) throw IOException("No readable Inori volumes found")
            return NovelDetails(title,url,doc.selectFirst("p.description.desc-wrapper")?.text().orEmpty(),chapters,NovelCatalogRepository.cover(info),"inori",info.selectFirst("header.s-info p")?.text().orEmpty())
        }
    }
}
