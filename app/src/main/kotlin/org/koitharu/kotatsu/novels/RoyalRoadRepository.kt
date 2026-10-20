/* Royal Road selectors adapted from QuickNovel's RoyalRoadProvider.
 * Copyright QuickNovel contributors. GPL-3.0; see THIRD_PARTY_NOTICES.md.
 */
package org.koitharu.kotatsu.novels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.util.await
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NovelLink(val title: String, val url: String, val cover: String = "", val source: String = "royalroad")
data class NovelDetails(val title: String, val url: String, val synopsis: String, val chapters: List<NovelLink>, val cover: String = "", val source: String = "royalroad", val author: String = "")

class RoyalRoadRepository {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun search(query: String): List<NovelLink> = withContext(Dispatchers.IO) {
        val url = "$BASE/fictions/search".toHttpUrl().newBuilder().addQueryParameter("title", query).build()
        parseSearch(fetch(url.toString()))
    }

    suspend fun browse(page: Int, order: String, genre: String): List<NovelLink> = withContext(Dispatchers.IO) {
        val url = "$BASE/fictions/$order".toHttpUrl().newBuilder().addQueryParameter("page", page.toString())
        if (genre.isNotBlank()) url.addQueryParameter("genre", genre)
        parseSearch(fetch(url.build().toString()))
    }

    suspend fun details(url: String): NovelDetails = withContext(Dispatchers.IO) {
        parseDetails(fetch(url), url)
    }

    suspend fun chapter(url: String): String = withContext(Dispatchers.IO) {
        parseChapter(fetch(url))
    }

    private suspend fun fetch(url: String): Document {
        require(isAllowed(url)) { "Unsupported novel address" }
        client.newCall(Request.Builder().url(url).header("User-Agent", "Shiori/0.1 (Android novel reader)").build())
            .await().use { response ->
                if (!response.isSuccessful) throw IOException("Royal Road returned HTTP ${response.code}. Try again later or open the website.")
                val body = response.body ?: throw IOException("Empty response")
                return Jsoup.parse(body.string(), url)
            }
    }

    companion object {
        const val BASE = "https://www.royalroad.com"

        fun isAllowed(url: String): Boolean = runCatching {
            val parsed = url.toHttpUrl()
            parsed.scheme == "https" && parsed.host == "www.royalroad.com" && parsed.port == 443
        }.getOrDefault(false)

        fun parseSearch(document: Document): List<NovelLink> = document.select(".fiction-list-item h2.fiction-title a")
            .mapNotNull { link ->
                val url = link.absUrl("href")
                if (link.text().isBlank() || !isAllowed(url)) null else NovelLink(link.text(), url, link.closest(".fiction-list-item")?.selectFirst("img")?.absUrl("src").orEmpty())
            }.distinctBy { it.url }

        fun parseDetails(document: Document, url: String): NovelDetails {
            val title = document.selectFirst("h1.font-white")?.text()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Novel details unavailable. The site may require browser verification.")
            val chapters = document.select("#chapters tbody tr, div.portlet-body > table > tbody > tr").mapNotNull { row ->
                val link = row.selectFirst("td a[href]") ?: return@mapNotNull null
                val address = link.absUrl("href")
                if (link.text().isBlank() || !isAllowed(address)) null else NovelLink(link.text(), address)
            }.distinctBy { it.url }
            if (chapters.isEmpty()) throw IOException("No chapters available for this novel.")
            return NovelDetails(title, url, document.select("div.description > div").text(), chapters,
                document.selectFirst(".cover-art-container img")?.absUrl("src").orEmpty(), "royalroad",
                document.selectFirst("h4.font-white > span > a")?.text().orEmpty())
        }

        fun parseChapter(document: Document): String {
            val chapter = document.selectFirst("div.chapter-content")
                ?: throw IOException("Chapter unavailable. The site may require browser verification.")
            // Keep prose and tables; do not execute or load third-party active content.
            chapter.select("script, style, [hidden], [aria-hidden=true], .d-none").remove()
            chapter.select("[style]").filter {
                Regex("(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("style"))
            }.forEach { it.remove() }
            document.select("style").forEach { style ->
                Regex("""([^{}]+)\{([^{}]*)\}""").findAll(style.data()).forEach { rule ->
                    if (Regex("(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)", RegexOption.IGNORE_CASE).containsMatchIn(rule.groupValues[2])) {
                        runCatching { chapter.select(rule.groupValues[1].trim()).remove() }
                    }
                }
            }
            if (chapter.text().isBlank()) throw IOException("This chapter has no readable text.")
            return Jsoup.clean(chapter.html(), Safelist.basic().removeTags("a").addTags("table", "tr", "td", "th", "tbody"))
        }
    }
}
