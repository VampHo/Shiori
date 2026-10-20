/* Adapters adapted from QuickNovel providers, GPL-3.0. See THIRD_PARTY_NOTICES.md. */
package org.koitharu.kotatsu.novels

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.IOException

class SelectedNovelSources(private val request: suspend (String, Map<String, String>?) -> String) {
    private val wuxia = WuxiaNovelSource(request)
    private suspend fun doc(url: String) = Jsoup.parse(request(url, null), url)
    private fun url(base: String, path: String, params: Map<String, String> = emptyMap()): String =
        (base + path).toHttpUrl().newBuilder().apply { params.forEach { (k,v) -> addQueryParameter(k,v) } }.build().toString()
    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun link(source: NovelSource, title: String, address: String, cover: String): NovelLink? {
        val href = runCatching { java.net.URI(source.base).resolve(address).toString() }.getOrDefault("")
        if (title.isBlank() || NovelCatalogRepository.sourceFor(href)?.id != source.id) return null
        return NovelLink(title, href, NovelCatalogRepository.resolveImage(source.base, cover), source.id)
    }
    private fun fenrirBooks(raw: String, s: NovelSource) = JSONObject(raw).optJSONArray("data")?.objects().orEmpty().mapNotNull {
        link(s, it.optString("title"), "/series/${it.optString("slug")}", it.optString("cover"))
    }
    private fun wattpadBooks(raw: String, s: NovelSource) = JSONObject(raw).optJSONArray("stories")?.objects().orEmpty().mapNotNull {
        link(s, it.optString("title"), it.optString("url"), it.optString("cover"))
    }
    suspend fun browse(s: NovelSource, page: Int, order: String, genre: String): List<NovelLink> {
        return when (s.id) {
            "wuxiaworld" -> if (page > 1) emptyList() else wuxia.search(null, order, genre)
            "wattpad" -> wattpadBooks(request(url("https://api.wattpad.com", "/v5/hotlist", mapOf("tags" to genre.ifBlank { "fantasy" }.lowercase(), "offset" to ((page-1)*20).toString(), "limit" to "20")), null), s)
            "fenrir" -> {
                val params = mutableMapOf("page" to "$page", "per_page" to "24", "status" to "any", "sort" to order)
                val tag = mapOf("Action" to "1", "Adventure" to "3", "Fantasy" to "7", "Romance" to "18", "Sci-fi" to "20")[genre]
                if (tag != null) params["tags[]"] = tag
                fenrirBooks(request(url(s.base, "/api/new/v2/series", params), null), s)
            }
            "novelfire" -> parseBooks(doc(url(s.base, "/genre-${genre.ifBlank { "all" }.lowercase()}/$order/status-all/all-novel", mapOf("page" to "$page"))), s)
            "novelfull" -> parseBooks(doc(url(s.base, if (genre.isBlank()) "/$order" else "/genre/$genre", mapOf("page" to "$page"))), s)
            "readhive" -> parseBooks(doc("${s.base}/page/$page"), s)
            else -> emptyList()
        }
    }
    suspend fun search(s: NovelSource, query: String): List<NovelLink> = when (s.id) {
        "wuxiaworld" -> wuxia.search(query, "1", "")
        "fenrir" -> fenrirBooks(request(url(s.base, "/api/new/v2/series", mapOf("page" to "1", "per_page" to "24", "search" to query)), null), s)
        "readhive" -> JSONObject(request("${s.base}/ajax", mapOf("query" to query, "action" to "search"))).optJSONArray("data")?.objects().orEmpty().mapNotNull {
            link(s, it.optString("title"), it.optString("url").replace("\\", ""), it.optString("thumb").replace("\\", ""))
        }
        "wattpad" -> parseBooks(doc(s.base.toHttpUrl().newBuilder().addPathSegment("search").addPathSegment(query).build().toString()), s)
        else -> parseBooks(doc(url(s.base, "/search/", mapOf("keyword" to query))), s)
    }
    private fun parseBooks(d: Document, s: NovelSource): List<NovelLink> {
        val selector = when(s.id) { "novelfire" -> "li.novel-item"; "novelfull" -> "div.list > div.row"; "readhive" -> "a.peer, a.col-span-2"; "wattpad" -> ".story-card"; else -> return emptyList() }
        return d.select(selector).mapNotNull { row ->
            val a = if(row.tagName() == "a") row else row.selectFirst(if(s.id=="novelfull") "h3 a" else "a[title], a[href]") ?: return@mapNotNull null
            val title = when(s.id) {
                "readhive" -> a.selectFirst("img")?.attr("alt").orEmpty()
                "wattpad" -> a.selectFirst(".story-info .sr-only")?.text().orEmpty()
                else -> a.attr("title").ifBlank { a.selectFirst("h4, h3")?.text() ?: a.text() }
            }
            link(s, title, a.absUrl("href"), NovelCatalogRepository.cover(row))
        }.distinctBy { it.url }
    }
    suspend fun details(s: NovelSource, address: String): NovelDetails {
        if (s.id == "wuxiaworld") return wuxia.details(address)
        val d = doc(address)
        if (s.id == "wattpad") return wattpadDetails(d, address)
        val title = d.selectFirst(when(s.id) { "novelfire" -> "h1.novel-title"; "novelfull" -> "h3.title"; "fenrir" -> "#series-info h1"; else -> "main h1" })?.text()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Novel details unavailable. Open the source in your browser if verification is required.")
        val chapters: List<NovelLink> = when(s.id) {
            "novelfire" -> {
                val id = d.selectFirst("#novel-report")?.attr("report-post_id") ?: throw IOException("Novel ID unavailable")
                val params = mutableMapOf("draw" to "1", "post_id" to id, "start" to "0", "length" to "-1", "only_bookmark" to "false", "order[0][column]" to "0", "order[0][dir]" to "asc", "order[0][name]" to "cmm_posts_detail.n_sort", "search[value]" to "", "search[regex]" to "false")
                listOf("n_sort" to "cmm_posts_detail.n_sort", "bookmark_created_at" to "bookmark_chapters.created_at").forEachIndexed { i, fields ->
                    params["columns[$i][data]"] = fields.first; params["columns[$i][name]"] = fields.second
                    params["columns[$i][searchable]"] = (i == 0).toString(); params["columns[$i][orderable]"] = "true"
                    params["columns[$i][search][value]"] = ""; params["columns[$i][search][regex]"] = "false"
                }
                JSONObject(request(url(s.base, "/ajax/listChapterDataAjax", params), null)).optJSONArray("data")?.objects().orEmpty().mapNotNull {
                    val number = it.optInt("n_sort", -1)
                    if(number < 0) null else NovelLink(it.optString("title", "Chapter $number"), "${address.trimEnd('/')}/chapter-$number", source = s.id)
                }
            }
            "novelfull" -> {
                val id = d.selectFirst("#rating")?.attr("data-novel-id").orEmpty()
                val list = doc(url(s.base, "/ajax-chapter-option", mapOf("novelId" to id)))
                list.select("select > option, li[data-chapter-item] > a").mapNotNull {
                    val href = it.absUrl(if(it.hasAttr("value")) "value" else "href")
                    link(s, it.text(), href, "")
                }
            }
            "fenrir" -> {
                val slug = address.substringAfter("/series/").trimEnd('/')
                JSONArray(request("${s.base}/api/new/v2/series/$slug/chapters", null)).objects().filter {
                    it.optJSONObject("locked")?.optInt("price", 1) == 0
                }.map { NovelLink(listOf(it.optString("name"), it.optString("title")).filter { t -> t.isNotBlank() && t != "null" }.joinToString(" — "), "${address.trimEnd('/')}/${it.getString("slug")}/__data.json?x-sveltekit-invalidated=10001", source = s.id) }
            }
            "readhive" -> d.select(READHIVE_CHAPTERS).filter { it.children().none { child -> child.tagName() == "span" } }.mapNotNull {
                link(s, it.selectFirst("div > div > span")?.text().orEmpty(), it.absUrl("href"), "")
            }.reversed()
            else -> emptyList()
        }
        if(chapters.isEmpty()) throw IOException("No accessible chapters returned by ${s.name}. Locked chapters may require the source app.")
        return NovelDetails(title, address, d.select(when(s.id) { "novelfire" -> "div.summary > div"; "novelfull" -> "div.desc-text"; "fenrir" -> "div.synopsis"; else -> "main div.mb-4 > p" }).text(), chapters.distinctBy { it.url }, NovelCatalogRepository.detailCover(d), s.id,
            d.selectFirst(when(s.id) { "novelfire" -> "div.author > a"; "novelfull" -> ".info a"; "fenrir" -> "#series-info a"; else -> "main span.leading-7" })?.text().orEmpty())
    }
    private fun wattpadDetails(d: Document, address: String): NovelDetails {
        val script = d.select("script").firstOrNull { it.data().contains("__remixContext") }?.data() ?: throw IOException("Wattpad story data unavailable")
        val state = jsonObjectAfter(script, "__remixContext").getJSONObject("state").getJSONObject("loaderData")
        val story = state.keys().asSequence().mapNotNull { key ->
            val value = state.opt(key)
            val data = if(value is JSONObject) value else runCatching { JSONObject(value.toString()) }.getOrNull()
            data?.optJSONObject("story")
        }.firstOrNull() ?: throw IOException("Wattpad story unavailable")
        val parts = story.optJSONArray("parts")?.objects().orEmpty().filter { !it.optBoolean("isPaid", false) && !it.optBoolean("locked", false) }.mapNotNull {
            val href = it.optString("url"); if (NovelCatalogRepository.sourceFor(href)?.id != "wattpad") null else NovelLink(it.optString("title"), href, source="wattpad")
        }
        if(parts.isEmpty()) throw IOException("No accessible Wattpad chapters")
        return NovelDetails(story.getString("title"), address, story.optString("description"), parts, story.optString("cover"), "wattpad", story.optJSONObject("author")?.optString("username").orEmpty())
    }
    suspend fun chapter(s: NovelSource, address: String): String {
        if(s.id == "fenrir") {
            val nodes = JSONObject(request(address, null)).getJSONArray("nodes").objects().filter { it.optString("type") == "data" }
            val data = nodes.getOrNull(1)?.optJSONArray("data") ?: throw IOException("FenrirRealm chapter unavailable")
            return sanitize(data.optString(6).takeIf { it.contains('<') } ?: throw IOException("FenrirRealm chapter format changed"))
        }
        val d = doc(address)
        if(s.id == "wattpad") {
            val script = d.select("script").firstOrNull { it.data().contains("window.prefetched") }?.data() ?: throw IOException("Wattpad requires access through its website for this chapter")
            val root = jsonObjectAfter(script, "window.prefetched")
            val data = root.keys().asSequence().mapNotNull { root.optJSONObject(it)?.optJSONObject("data") }.firstOrNull { it.has("text_url") } ?: throw IOException("Wattpad chapter data unavailable")
            if(data.optBoolean("isPaid") || data.optBoolean("locked")) throw IOException("Read this locked chapter in Wattpad")
            val base = data.getJSONObject("text_url").getString("text")
            val pages = data.optInt("pages", 1); require(pages in 1..100) { "Unexpected chapter page count" }
            val html = StringBuilder()
            for(page in 1..pages) html.append(request("$base$page", null))
            return sanitize(html.toString())
        }
        val body = d.selectFirst(when(s.id) { "novelfire" -> "div#content"; "novelfull" -> "#chapter-content, #chr-content"; "wuxiaworld" -> "div.chapter-content"; "readhive" -> "main > div.prose"; else -> "div.chapter-content" }) ?: throw IOException("Chapter unavailable or locked on ${s.name}")
        return sanitize(body.html())
    }
    companion object {
        private const val READHIVE_CHAPTERS = "section.relative.grid.grid-cols-1.lg\\:grid-areas-series__body.lg\\:grid-cols-series.gap-x-4.px-4.py-2.sm\\:px-8 > div.lg\\:grid-in-content.mt-4 > div:nth-child(1) > div:nth-child(3) > div > div > a"
        fun sanitize(html: String): String {
            val d = Jsoup.parse(html); d.select("script, style, iframe, svg, noscript, [hidden], [aria-hidden=true]").remove()
            if(d.body().text().isBlank()) throw IOException("No readable chapter text")
            return Jsoup.clean(d.body().html(), Safelist.basic().removeTags("a").addTags("table", "tbody", "tr", "td", "th"))
        }
        /** Balanced scanning handles multiline JSON and braces inside strings on Android. */
        fun jsonObjectAfter(script: String, marker: String): JSONObject {
            val markerIndex = script.indexOf(marker); require(markerIndex >= 0)
            val start = script.indexOf('{', markerIndex); require(start >= 0)
            var depth = 0; var quoted = false; var escaped = false
            for(i in start until script.length) {
                val c = script[i]
                if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
                else when(c) { '"' -> quoted=true; '{' -> depth++; '}' -> { depth--; if(depth==0) return JSONObject(script.substring(start,i+1)) } }
            }
            throw IOException("Incomplete source data")
        }
    }
}
