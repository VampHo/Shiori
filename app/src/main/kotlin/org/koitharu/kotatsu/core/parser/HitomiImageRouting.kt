package org.koitharu.kotatsu.core.parser

/** Parse routing data, never execute source JavaScript. */
internal data class HitomiImageRouting(val prefix: String, val defaultOffset: Int, val alternateOffset: Int, val cases: Set<Int>) {
    fun offset(id: Int): Int = if(id in cases) alternateOffset else defaultOffset
    fun pageUrl(hash: String): String {
        require(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid Hitomi image hash" }
        val id=(hash.takeLast(1)+hash.takeLast(3).take(2)).toInt(16)
        return "https://a${offset(id)+1}.gold-usergeneratedcontent.net/$prefix$id/$hash.avif"
    }
    companion object {
        fun parse(script: String): HitomiImageRouting {
            fun match(pattern:String)=Regex(pattern).find(script)?.groupValues?.get(1)
                ?: throw java.io.IOException("Hitomi image routing changed; please retry later")
            val default=match("var\\s+o\\s*=\\s*([01])").toInt()
            val alternate=match("o\\s*=\\s*([01])\\s*;\\s*break\\s*;").toInt()
            val prefix=match("b\\s*:\\s*['\"]([0-9]+/)['\"]")
            val cases=Regex("case\\s+(\\d+)\\s*:").findAll(script).map { it.groupValues[1].toInt() }.toSet()
            return HitomiImageRouting(prefix,default,alternate,cases)
        }
    }
}
