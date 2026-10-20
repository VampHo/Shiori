/* Source selectors adapted from QuickNovel (GPL-3.0). See THIRD_PARTY_NOTICES.md. */
package org.koitharu.kotatsu.novels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.await
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NovelSource(val id: String, val name: String, val base: String, val orders: List<Pair<String, String>>) {
    val genres: List<Pair<String, String>> get() = if (id == "readhive") listOf("All genres" to "") else listOf("All genres" to "", "Action" to "Action", "Adventure" to "Adventure", "Fantasy" to "Fantasy", "Romance" to "Romance", "Sci-fi" to "Sci-fi")
}

class NovelCatalogRepository {
    private val royalRoad = RoyalRoadRepository()
    private val selected = SelectedNovelSources { url, form -> request(url, form) }
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun browse(source: NovelSource, page: Int, order: String, genre: String): List<NovelLink> = withContext(Dispatchers.IO) {
        if (source.id == "royalroad") return@withContext royalRoad.browse(page, order, genre.lowercase().replace('-', '_'))
        if (source.id !in setOf("freewebnovel", "libread")) return@withContext selected.browse(source, page, order, genre)
        val path = if (genre.isBlank()) "sort/$order/$page" else "genre/$genre/$page"
        parseList(fetch("${source.base}/$path"), source)
    }

    suspend fun search(source: NovelSource, query: String): List<NovelLink> = withContext(Dispatchers.IO) {
        if (source.id == "royalroad") return@withContext royalRoad.search(query)
        if (source.id !in setOf("freewebnovel", "libread")) return@withContext selected.search(source, query)
        val doc = if (source.id == "libread") fetch("${source.base}/search", mapOf("searchkey" to query)) else {
            fetch("${source.base}/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build().toString())
        }
        parseList(doc, source)
    }

    suspend fun details(url: String): NovelDetails = withContext(Dispatchers.IO) {
        val source = sourceFor(url) ?: throw IOException("Unsupported novel source")
        if (source.id == "royalroad") return@withContext royalRoad.details(url)
        if (source.id !in setOf("freewebnovel", "libread")) return@withContext selected.details(source, url)
        val doc = fetch(url)
        val title = doc.selectFirst("h1.tit")?.text()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Novel details unavailable. The source may require browser verification.")
        val id = doc.selectFirst("a.set-case.add")?.attr("data-articleid")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=image]")?.attr("content")?.substringAfterLast('/')?.substringBefore("s.jpg")
        var chapters = doc.select(".m-chapter a[href], .chapter-list a[href]").mapNotNull { chapterLink(it, source) }
        if (id != null) {
            var code = url.trimEnd('/').removeSuffix(".html").substringAfterLast('/')
            if (source.id == "libread") code = code.substringBeforeLast('-')
            val raw = request("https://freewebnovel.com/api/chapterlist.php", mapOf("aid" to id, "acode" to code, "cid" to "1"))
            val options = Jsoup.parse(JSONObject(raw).getString("html"), "https://freewebnovel.com")
            chapters = options.select("option").mapNotNull {
                val value = it.attr("value").substringAfterLast('/')
                if (value.isBlank()) null else NovelLink(it.text(), "https://freewebnovel.com/novel/$code/$value", source = "freewebnovel")
            }
        }
        if (chapters.isEmpty()) throw IOException("No readable chapters returned by this source.")
        NovelDetails(title, url, doc.select("div.inner").text(), chapters.distinctBy { it.url }, detailCover(doc), source.id,
            doc.selectFirst("a[href^=/author/]")?.text().orEmpty())
    }

    suspend fun chapter(url: String): String = withContext(Dispatchers.IO) {
        val source = sourceFor(url) ?: throw IOException("Unsupported chapter address")
        if (source.id == "royalroad") return@withContext royalRoad.chapter(url)
        if (source.id !in setOf("freewebnovel", "libread")) return@withContext selected.chapter(source, url)
        val doc = fetch(url)
        val body = doc.selectFirst("#article, .chapter-content, div.txt")
            ?: throw IOException("Chapter unavailable. The source may require browser verification.")
        body.select("script, style, iframe, ins, [hidden], .ads").remove()
        if (body.text().length < 20) throw IOException("No readable chapter text returned")
        Jsoup.clean(body.html(), Safelist.basic().removeTags("a").addTags("table", "tr", "td", "th", "tbody"))
    }

    suspend fun bookCover(url: String): String = withContext(Dispatchers.IO) { detailCover(fetch(url)) }

    private suspend fun fetch(url: String, form: Map<String, String>? = null): Document = Jsoup.parse(request(url, form), url)
    private suspend fun request(url: String, form: Map<String, String>?): String {
        require(sourceFor(url) != null || isServiceUrl(url)) { "Unsupported source address" }
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Referer", url.toHttpUrl().newBuilder().encodedPath("/").query(null).build().toString())
        if (form != null) request.post(FormBody.Builder().apply { form.forEach { (k,v) -> add(k,v) } }.build())
        return client.newCall(request.build()).await().use {
            if (!it.isSuccessful) throw IOException("Source returned HTTP ${it.code}. Try again later.")
            if (sourceFor(it.request.url.toString()) == null && !isServiceUrl(it.request.url.toString())) throw IOException("Source redirected to an unsupported address")
            it.body?.string() ?: throw IOException("Empty source response")
        }
    }

    companion object {
        private val legacySources = listOf(
            NovelSource("freewebnovel", "FreeWebNovel", "https://freewebnovel.com", listOf("Latest release" to "latest-release", "Latest novels" to "latest-novel", "Most popular" to "most-popular")),
            NovelSource("libread", "LibRead", "https://libread.com", listOf("Latest release" to "latest-release", "Latest novels" to "latest-novel", "Completed" to "completed-novel")),
            NovelSource("royalroad", "Royal Road", RoyalRoadRepository.BASE, listOf("Latest updates" to "latest-updates", "Best rated" to "best-rated", "Rising stars" to "rising-stars", "New releases" to "new-releases", "Completed" to "complete"))
        )
        val sources: List<NovelSource> = listOf(
            NovelSource("novelfire", "NovelFire", "https://novelfire.net", listOf("Popular" to "sort-popular", "New" to "sort-new", "Updates" to "sort-latest-release")),
            legacySources.first { it.id == "royalroad" },
            NovelSource("wattpad", "Wattpad", "https://www.wattpad.com", listOf("Popular" to "hot")),
            NovelSource("fenrir", "FenrirRealm", "https://fenrirealm.com", listOf("Popular" to "popular", "Latest" to "latest", "Updated" to "updated")),
            NovelSource("wuxiaworld", "WuxiaWorld", "https://www.wuxiaworld.com", listOf("Popular" to "1", "New" to "2", "Rating" to "6")),
            NovelSource("novelfull", "NovelFull", "https://novelfull.com", listOf("Hot novels" to "hot-novel", "Latest releases" to "latest-release-novel", "Completed" to "completed-novel")),
            NovelSource("readhive", "ReadHive", "https://readhive.org", listOf("Latest" to "latest"))
        )
        private fun isServiceUrl(url: String): Boolean = runCatching {
            val u = url.toHttpUrl()
            u.scheme == "https" && u.port == 443 && u.host in setOf("api.wattpad.com", "text.wattpad.com")
        }.getOrDefault(false)
        fun sourceFor(url: String): NovelSource? = runCatching {
            val parsed = url.toHttpUrl()
            (sources + legacySources).firstOrNull { parsed.scheme == "https" && parsed.port == 443 && parsed.host == it.base.toHttpUrl().host }
        }.getOrNull()
        fun cover(element: Element): String {
            val image = element.selectFirst(".pic img, figure img, img") ?: return ""
            return imageUrl(image)
        }
        private fun imageUrl(image: Element): String {
            val picture = image.closest("picture")?.selectFirst("source[srcset]")
            if (picture != null) {
                val best = picture.attr("srcset").split(',').lastOrNull()?.trim()?.substringBefore(' ').orEmpty()
                val url = resolveImage(image.baseUri(), best)
                if (url.isNotBlank()) return url
            }
            for (attr in listOf("data-src", "data-original", "src")) {
                val value = image.absUrl(attr)
                if (validCover(value)) return value
            }
            val srcset = image.attr("srcset").split(',').lastOrNull()?.trim()?.substringBefore(' ').orEmpty()
            return resolveImage(image.baseUri(), srcset)
        }
        fun validCover(url: String): Boolean = url.isNotBlank() && !Regex("(?i)(logo|favicon|placeholder|loading|no[-_]cover)").containsMatchIn(url) &&
            (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file:"))
        fun resolveImage(base: String, value: String): String {
            val url = runCatching { java.net.URI(base).resolve(value).toString() }.getOrDefault("")
            return url.takeIf { validCover(it) && value.isNotBlank() }.orEmpty()
        }
        fun detailCover(doc: Document): String {
            // Never use a page-wide img fallback: the first image is often the site logo.
            for (selector in listOf(".cover-art-container img", "figure.cover img", "div.book img", ".m-desc .pic img", ".m-info .pic img", "main img.object-cover", ".novel-info img", "div.pic img")) {
                doc.select(selector).forEach { val url = imageUrl(it); if (url.isNotBlank()) return url }
            }
            for (selector in listOf("meta[property=og:image]", "meta[name=twitter:image]", "meta[name=image]")) {
                val url = resolveImage(doc.baseUri(), doc.selectFirst(selector)?.attr("content").orEmpty())
                if (url.isNotBlank()) return url
            }
            return ""
        }
        fun parseList(doc: Document, source: NovelSource): List<NovelLink> = doc.select("div.li-row").mapNotNull { row ->
            val a = row.selectFirst("h3.tit a") ?: return@mapNotNull null
            val url = a.absUrl("href")
            val title = a.attr("title").ifBlank { a.text() }
            if (sourceFor(url) == null || title.isBlank()) null else NovelLink(title, url, cover(row), source.id)
        }.distinctBy { it.url }
        private fun chapterLink(a: Element, source: NovelSource): NovelLink? {
            val url = a.absUrl("href")
            return if (sourceFor(url) == null || a.text().isBlank()) null else NovelLink(a.text(), url, source = source.id)
        }
    }
}
