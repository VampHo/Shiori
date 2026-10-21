/* WuxiaWorld protobuf adapter adapted from QuickNovel, GPL-3.0. */
package org.koitharu.kotatsu.novels

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.util.await
import java.io.IOException
import java.util.concurrent.TimeUnit

class WuxiaNovelSource(private val request: suspend (String, Map<String, String>?) -> String) {
    private val mainUrl = "https://www.wuxiaworld.com"
    private val api = "https://api2.wuxiaworld.com/wuxiaworld.api.v2"
    private val getChaptersList = "$api.Chapters/GetChapterList"
    private val client by lazy { NovelHttp.client }
    data class NovelDetail(val id: Int, val slug: String)

    suspend fun search(title: String?, order: String, genre: String): List<NovelLink> {
        val novels = mutableListOf<NovelLink>()
        grpcPost("$api.Novels/SearchNovels", buildARequestBody(title=title, sortType=order.toIntOrNull(), genres=listOf(genre)), "$mainUrl/").use { source ->
            source.processGrpcStream {
                while(!exhausted()) {
                    val tag = readVarint().toInt()
                    if(tag shr 3 == 1) readMessage { decodeNovelItem()?.let { novels.add(it) } }
                    else skipField(tag and 7)
                }
            }
        }
        return novels
    }
    suspend fun details(url: String): NovelDetails {
        val html = request(url, null)
        val state = SelectedNovelSources.jsonObjectAfter(html, "__REACT_QUERY_STATE__")
        val queries = state.getJSONArray("queries")
        val query = (0 until queries.length()).map { queries.getJSONObject(it) }.firstOrNull {
            it.optJSONArray("queryKey")?.optString(0) == "novel"
        } ?: throw IOException("WuxiaWorld novel state unavailable")
        val item = query.getJSONObject("state").getJSONObject("data").getJSONObject("item")
        val data = NovelDetail(item.getInt("id"), item.getString("slug"))
        val chapters = getChapters(data, url)
        if(chapters.isEmpty()) throw IOException("No free accessible chapters. Open WuxiaWorld to access locked chapters.")
        val description = item.optJSONObject("synopsis")?.optString("value") ?: item.optJSONObject("description")?.optString("value").orEmpty()
        return NovelDetails(item.getString("name"), url, Jsoup.parse(description).text(), chapters,
            item.optJSONObject("coverUrl")?.optString("value").orEmpty(), "wuxiaworld", item.optJSONObject("authorName")?.optString("value").orEmpty())
    }
    private suspend fun grpcPost(url: String, fields: Map<Int, Any>, referer: String): BufferedSource {
        val body = fields.toList().encodeProto().toGrpcWeb().toRequestBody("application/grpc-web+proto".toMediaType())
        val req = Request.Builder().url(url).post(body).header("Referer",referer).header("Accept","application/grpc-web+proto")
            .header("User-Agent", NovelHttp.userAgent).header("Origin", mainUrl)
            .header("X-User-Agent","grpc-web-javascript/0.1").header("x-grpc-web","1").build()
        return client.newCall(req).await().use { response ->
            if (response.code == 401 || response.code == 403) throw NovelAccessException(referer, "WuxiaWorld requires verification or sign-in. Open the source, then return to retry.")
            if(!response.isSuccessful) throw IOException("WuxiaWorld returned HTTP ${response.code}")
            val data = response.body?.source() ?: throw IOException("Empty WuxiaWorld response")
            val out = Buffer()
            while(!data.exhausted()) {
                val n=data.read(out,8192); if(n<0) break
                if(out.size > 16L*1024*1024) throw IOException("WuxiaWorld response too large")
            }
            out
        }
    }
    suspend fun getChapters(item: NovelDetail, url: String): List<NovelLink> {
        val chapters = mutableListOf<NovelLink>()
        grpcPost(getChaptersList, mapOf(1 to item.id), url).use { source ->
            source.processGrpcStream {
                while (!exhausted()) {
                    val tagNum = readVarint().toInt()
                    if (tagNum shr 3 == 1) { // Field 1: List of chapter groups
                        readMessage {
                            while (!exhausted()) {
                                val groupTagNum = readVarint().toInt()
                                if (groupTagNum shr 3 == 6) { // Field 6: Individual chapter message
                                    readMessage {
                                        var name = ""
                                        var chSlug = ""
                                        var isFree = false
                                        while (!exhausted()) {
                                            val chTagNum = readVarint().toInt()
                                            when (chTagNum shr 3) {
                                                2 -> name = readString() // Field 2: Chapter title
                                                3 -> chSlug = readString() // Field 3: Chapter slug
                                                20 -> readMessage { // Field 20: Access Info
                                                    while (!exhausted()) {
                                                        val accTagNum = readVarint().toInt()
                                                        if (accTagNum shr 3 == 1) isFree =
                                                            readVarint() == 1L // Sub-field 1: Is Accessible/Free
                                                        else skipField(accTagNum and 0x7)
                                                    }
                                                }

                                                else -> skipField(chTagNum and 0x7)
                                            }
                                        }
                                        if (name.isNotEmpty() && isFree) {
                                            chapters.add(
                                                NovelLink(
                                                    name,
                                                    "$mainUrl/novel/${item.slug}/$chSlug", source = "wuxiaworld"
                                                )
                                            )
                                        }
                                    }
                                } else skipField(groupTagNum and 0x7)
                            }
                        }
                    } else skipField(tagNum and 0x7)
                }
            }
        }
        return chapters
    }

    private fun buildARequestBody(
        title: String? = null,
        status: Long? = -1L, // status: All
        sortType: Int? = 1, // sortType
        sortDirection: Long = 1L, // sortDirection: 1 DESC, 0 ASC
        count: Int = 500, // count
        genres: List<String?>? = null,
        genresFilter: Int = 1, //1 OR, 0 AND
    ): MutableMap<Int, Any> {
        val requestMap = mutableMapOf<Int, Any>()
        if (title != null) requestMap[1] = mapOf(1 to title)
        requestMap[3] = status ?: -1L
        requestMap[4] = sortType ?: 1
        requestMap[5] = sortDirection
        requestMap[7] = count
        val genresAux = genres?.filter { !it.isNullOrEmpty() }
        if (!genresAux.isNullOrEmpty()) requestMap[10] =
            genresAux.map { 1 to it } + (2 to genresFilter)

        return requestMap
    }

    /**
     * Helper class to decode Protobuf messages directly from a network stream (BufferedSource).
     * This prevents OutOfMemory errors by not loading the entire response into a ByteArray.
     */
    private class ProtoReader(
        private val source: BufferedSource,
        var limit: Long = Long.MAX_VALUE
    ) {
        /** Returns true if the current message scope or the source is exhausted. */
        fun exhausted() = source.exhausted() || limit <= 0

        /** Decrements the remaining bytes allowed to be read in the current scope. */
        private fun consume(bytes: Long) {
            if (limit != Long.MAX_VALUE) {
                limit -= bytes
            }
        }

        /** Reads a Variable Integer (Varint) from the stream, common in Protobuf for tags and lengths. */
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = source.readByte().toInt()
                consume(1)
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                require(shift < 64) { "Invalid protobuf integer" }
            }
            return result
        }

        /** Skips a field in the stream based on its wire type if we don't need its value. */
        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint() // Varint
                1 -> {
                    source.skip(8); consume(8)
                } // 64-bit
                2 -> {
                    val len = readVarint(); source.skip(len); consume(len)
                } // Length-delimited
                5 -> {
                    source.skip(4); consume(4)
                } // 32-bit
                else -> throw Exception("Unknown wire type $wireType")
            }
        }

        /** Reads a UTF-8 string from a length-delimited field. */
        fun readString(): String {
            val len = readVarint()
            consume(len)
            return source.readUtf8(len)
        }

        /** Reads raw bytes from a length-delimited field. */
        fun readBytes(): ByteArray {
            val len = readVarint()
            consume(len)
            return source.readByteArray(len)
        }

        /**
         * Enters a nested Protobuf message (Tag with wire type 2).
         * Creates a fresh child reader with a strict byte limit to ensure isolation.
         */
        inline fun readMessage(callback: ProtoReader.() -> Unit) {
            val len = readVarint()
            consume(len)
            val subReader = ProtoReader(source, len)
            subReader.callback()
            // If the callback didn't consume all bytes, skip them to maintain parent stream alignment
            if (subReader.limit > 0) {
                source.skip(subReader.limit)
            }
        }
    }

    /** Helper class to build Protobuf messages into a Buffer. */
    private class ProtoWriter {
        private val buffer = Buffer()

        /** Writes an integer as a Protobuf Varint. */
        fun writeVarint(value: Long) {
            var v = value
            if (v < 0) {
                repeat(9) {
                    buffer.writeByte(((v and 0x7F) or 0x80).toInt())
                    v = v ushr 7
                }
                buffer.writeByte(v.toInt())
            } else {
                while (v and 0x7F.inv() != 0L) {
                    buffer.writeByte(((v and 0x7F) or 0x80).toInt())
                    v = v ushr 7
                }
                buffer.writeByte(v.toInt())
            }
        }

        /** Encodes a Tag (Field Number + Wire Type). */
        fun writeTag(field: Int, wireType: Int) {
            writeVarint(((field shl 3) or wireType).toLong())
        }

        /** Writes a field with a Varint value. */
        fun writeVarintTag(field: Int, value: Long) {
            writeTag(field, 0)
            writeVarint(value)
        }

        /** Writes a length-delimited string field. */
        fun writeString(field: Int, value: String) {
            writeTag(field, 2)
            val bytes = value.toByteArray()
            writeVarint(bytes.size.toLong())
            buffer.write(bytes)
        }

        /** Writes a raw byte array field. */
        fun writeBytes(field: Int, value: ByteArray) {
            writeTag(field, 2)
            writeVarint(value.size.toLong())
            buffer.write(value)
        }

        /** Finalizes the message and returns the byte array. */
        fun build(): ByteArray = buffer.readByteArray()
    }

    private fun ProtoReader.decodeNovelItem(): NovelLink? {
        var name = ""
        var slug = ""
        var cover = ""

        while (!exhausted()) {
            val tagNum = readVarint().toInt()
            val field = tagNum shr 3
            val wireType = tagNum and 0x7
            when (field) {
                2 -> name = readString() // Field 2: Title
                3 -> slug = readString() // Field 3: Slug
                10 -> { // Field 10: Nested cover info
                    readMessage {
                        while (!exhausted()) {
                            val innerTagNum = readVarint().toInt()
                            if (innerTagNum shr 3 == 1) cover = readString() // Field 1: URL
                            else skipField(innerTagNum and 0x7)
                        }
                    }
                }

                else -> skipField(wireType)
            }
        }

        if (name.isEmpty() || slug.isEmpty()) return null
        return NovelLink(name, "$mainUrl/novel/$slug", cover, "wuxiaworld")
    }

    /**
     * Recursively encodes a list of Field-Value pairs into Protobuf binary format.
     * Supports nested messages (as Iterables or Maps).
     */
    private fun Iterable<Pair<Int, Any>>.encodeProto(): ByteArray {
        val writer = ProtoWriter()
        this.sortedBy { it.first }.forEach { (field, value) ->
            when (value) {
                is Int -> writer.writeVarintTag(field, value.toLong())
                is Long -> writer.writeVarintTag(field, value)
                is String -> writer.writeString(field, value)
                is ByteArray -> writer.writeBytes(field, value)
                is Iterable<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    writer.writeBytes(field, (value as Iterable<Pair<Int, Any>>).encodeProto())
                }

                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    writer.writeBytes(field, (value as Map<Int, Any>).toList().encodeProto())
                }
            }
        }
        return writer.build()
    }

    /** Wraps Protobuf bytes into a gRPC-Web frame (1-byte flag + 4-byte length + payload). */
    private fun ByteArray.toGrpcWeb(): ByteArray {
        val out = Buffer()
        out.writeByte(0) // Flags: 0 means Data, 1 means Trailers
        out.writeInt(this.size) // Length in Big Endian
        out.write(this)
        return out.readByteArray()
    }

    private inline fun BufferedSource.processGrpcStream(callback: ProtoReader.() -> Unit) {
        while (!exhausted()) {
            val flags = readByte()
            val length = readInt()
            if (length < 0 || length > 16 * 1024 * 1024) throw IOException("Invalid WuxiaWorld response")
            if ((flags.toInt() and 0x80) != 0) {
                val trailers = readUtf8(length.toLong())
                val status = trailers.lineSequence().firstOrNull { it.startsWith("grpc-status:") }
                    ?.substringAfter(':')?.trim()
                if (status != null && status != "0") throw IOException("WuxiaWorld could not complete the request (status $status). Try again later.")
                continue
            }
            if (flags.toInt() != 0) throw IOException("Unsupported WuxiaWorld response encoding")
            // Process the data frame
            ProtoReader(this, length.toLong()).callback()
        }
    }
}
