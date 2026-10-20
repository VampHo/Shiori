package org.koitharu.kotatsu.explore.data

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Small catalogue for this personal build. Upstream broken flags always take precedence. */
object PersonalSourcePolicy {
    val sourceNames = setOf("MANGADEX", "MANGAPLUSPARSER_EN", "WEEBCENTRAL", "ASURASCANS",
        "FLAMECOMICS", "HIVECOMIC", "RIZZCOMIC", "GOURMETSCANS")
    fun allows(source: MangaParserSource): Boolean = source.name in sourceNames &&
        !source.isBroken && source.contentType != ContentType.HENTAI
}
