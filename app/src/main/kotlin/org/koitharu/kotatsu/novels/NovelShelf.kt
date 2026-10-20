package org.koitharu.kotatsu.novels

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Novel-only persistence. Keeps existing a2 favorites, history and progress keys. */
class NovelShelf(private val context: Context) {
    val prefs = context.getSharedPreferences("shiori_novels", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "novel_shelf").apply { mkdirs() }
    fun books(key: String): List<NovelLink> = runCatching {
        val items = JSONArray(prefs.getString(key, "[]"))
        (0 until items.length()).map { fromJson(items.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun save(key: String, books: List<NovelLink>) {
        val array = JSONArray(); books.forEach { array.put(toJson(it)) }
        prefs.edit { putString(key, array.toString()) }
    }
    fun remember(book: NovelLink) {
        listOf("favorites", "history", "downloads").forEach { key ->
            val items = books(key)
            if (items.any { it.url == book.url }) save(key, items.map { if (it.url == book.url) book else it })
        }
    }
    fun status(url: String) = prefs.getString("status:$url", "Plan to Read").orEmpty()
    fun setStatus(book: NovelLink, status: String) {
        save("favorites", listOf(book) + books("favorites").filterNot { it.url == book.url })
        prefs.edit { putString("status:${book.url}", status) }
    }
    private fun file(url: String, suffix: String): File = File(root, hash(url) + suffix)
    fun cachedDetails(url: String): NovelDetails? = runCatching {
        val json = JSONObject(file(url, ".json").readText())
        val link = fromJson(json)
        val array = json.getJSONArray("chapters")
        NovelDetails(link.title, link.url, json.optString("synopsis"), (0 until array.length()).map { fromJson(array.getJSONObject(it)) }, link.cover, link.source, json.optString("author"))
    }.getOrNull()
    fun cacheDetails(book: NovelDetails) {
        val json = toJson(book.link()).put("coverVersion", 2).put("synopsis", book.synopsis).put("author", book.author)
        val array = JSONArray(); book.chapters.forEach { array.put(toJson(it)) }; json.put("chapters", array)
        atomicWrite(file(book.url, ".json"), json.toString())
    }
    fun needsCoverRepair(book: NovelLink): Boolean = book.source != "local" &&
        (!NovelCatalogRepository.validCover(book.cover) || runCatching { JSONObject(file(book.url, ".json").readText()).optInt("coverVersion") < 2 }.getOrDefault(false))
    fun repairCover(book: NovelLink, cover: String): NovelLink {
        val fixed = book.copy(cover = cover)
        cachedDetails(book.url)?.let { cacheDetails(it.copy(cover = cover)) }
        remember(fixed)
        return fixed
    }
    fun cachedChapter(url: String): String? = file(url, ".html").takeIf { it.isFile }?.readText()
    fun hasChapter(url: String) = file(url, ".html").isFile
    fun cacheChapter(url: String, html: String) { atomicWrite(file(url, ".html"), html) }
    fun downloaded(book: NovelDetails) {
        save("downloads", listOf(book.link()) + books("downloads").filterNot { it.url == book.url })
    }
    fun removeDownload(book: NovelLink) {
        val details = cachedDetails(book.url)
        details?.chapters?.forEach { file(it.url, ".html").delete() }
        if (book.source == "local") {
            file(book.url, ".json").delete()
            file(book.url, ".cover").delete()
            save("favorites", books("favorites").filterNot { it.url == book.url })
            save("history", books("history").filterNot { it.url == book.url })
        }
        save("downloads", books("downloads").filterNot { it.url == book.url })
    }

    suspend fun importBook(uri: Uri): NovelDetails = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("novel-import-", ".zip", context.cacheDir)
        val url = "local:${UUID.randomUUID()}"
        val created = mutableListOf<File>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192); var count = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= 100L * 1024 * 1024) { "Book is larger than 100 MB" }; output.write(buffer, 0, n) }
                }
            } ?: throw IOException("Cannot open selected book")
            val chapters = mutableListOf<NovelLink>()
            var title = "Imported book"; var author = ""; var cover = ""
            val isZip = temp.inputStream().use { it.read() == 80 && it.read() == 75 }
            if (isZip) {
                ZipFile(temp).use { zip ->
                    var total = 0L
                    fun read(path: String): ByteArray {
                        val entry = zip.getEntry(path) ?: throw IOException("Missing EPUB content: $path")
                        zip.getInputStream(entry).use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                require(output.size() + n <= 8 * 1024 * 1024) { "EPUB entry is too large" }
                                output.write(buffer, 0, n)
                            }
                            val data = output.toByteArray()
                            total += data.size
                            require(data.size <= 8 * 1024 * 1024 && total <= 64L * 1024 * 1024) { "EPUB content is too large" }
                            return data
                        }
                    }
                    val container = Jsoup.parse(read("META-INF/container.xml").toString(Charsets.UTF_8), "", Parser.xmlParser())
                    val opf = container.selectFirst("rootfile")?.attr("full-path") ?: throw IOException("Invalid EPUB container")
                    val pkg = Jsoup.parse(read(opf).toString(Charsets.UTF_8), "", Parser.xmlParser())
                    title = pkg.getElementsByTag("dc:title").firstOrNull()?.text()?.ifBlank { "Imported book" } ?: title
                    author = pkg.getElementsByTag("dc:creator").firstOrNull()?.text().orEmpty()
                    val manifest = pkg.select("manifest > item").associateBy { it.attr("id") }
                    fun path(href: String): String {
                        val resolved = java.net.URI("https://epub.local/$opf").resolve(href.substringBefore('#')).normalize()
                        require(resolved.host == "epub.local") { "External EPUB content is unsupported" }
                        return resolved.path.removePrefix("/")
                    }
                    val coverId = pkg.selectFirst("meta[name=cover]")?.attr("content")
                    val coverItem = manifest[coverId] ?: manifest.values.firstOrNull { it.attr("properties").contains("cover-image") }
                    if (coverItem != null) {
                        val target = file(url, ".cover"); created += target
                        target.writeBytes(read(path(coverItem.attr("href")))); cover = target.toURI().toString()
                    }
                    pkg.select("spine > itemref").filter { it.attr("linear") != "no" }.forEachIndexed { index, ref ->
                        val item = manifest[ref.attr("idref")] ?: return@forEachIndexed
                        val doc = Jsoup.parse(read(path(item.attr("href"))).toString(Charsets.UTF_8))
                        doc.select("script, style, nav").remove()
                        if (doc.body().text().isNotBlank()) {
                            val chapterUrl = "$url/chapter/$index"
                            val chapterTitle = doc.selectFirst("h1, h2, h3")?.text()?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"
                            created += file(chapterUrl, ".html")
                            cacheChapter(chapterUrl, Jsoup.clean(doc.body().html(), Safelist.basic().removeTags("a")))
                            chapters += NovelLink(chapterTitle, chapterUrl, source = "local")
                        }
                    }
                }
            } else {
                require(temp.length() <= 8 * 1024 * 1024) { "Text book is larger than 8 MB" }
                val text = temp.readText().trim(); require(text.isNotBlank()) { "The book is empty" }
                title = text.lineSequence().first().take(100)
                // Bound each chapter so long text files remain responsive in the native reader.
                text.chunked(30000).forEachIndexed { index, part ->
                    val chapterUrl = "$url/chapter/$index"; created += file(chapterUrl, ".html")
                    val html = part.split("\n").joinToString("") { "<p>${org.jsoup.nodes.Entities.escape(it)}</p>" }
                    cacheChapter(chapterUrl, html); chapters += NovelLink("Part ${index + 1}", chapterUrl, source = "local")
                }
            }
            require(chapters.isNotEmpty()) { "No readable chapters in this book" }
            val book = NovelDetails(title, url, "Imported book · Available offline", chapters, cover, "local", author)
            cacheDetails(book); downloaded(book); setStatus(book.link(), "Plan to Read")
            book
        } catch (e: Exception) { created.forEach { it.delete() }; throw e } finally { temp.delete() }
    }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun atomicWrite(target: File, text: String) {
            val tmp = File(target.path + ".tmp"); tmp.writeText(text)
            if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("Cannot save novel data") }
        }
        fun toJson(book: NovelLink): JSONObject = JSONObject().put("title", book.title).put("url", book.url).put("cover", book.cover).put("source", book.source)
        fun fromJson(json: JSONObject) = NovelLink(json.getString("title"), json.getString("url"), json.optString("cover"), json.optString("source", "royalroad"))
    }
}
fun NovelDetails.link() = NovelLink(title, url, cover, source)
