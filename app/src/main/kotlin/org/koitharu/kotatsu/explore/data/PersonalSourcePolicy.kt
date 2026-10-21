package org.koitharu.kotatsu.explore.data

import org.koitharu.kotatsu.parsers.model.MangaParserSource

/** Keep English and multilingual catalogues. No popularity or adult-content restriction. */
object PersonalSourcePolicy {
    fun allows(source: MangaParserSource): Boolean = source.locale.isNullOrBlank() ||
        source.locale?.substringBefore('-')?.substringBefore('_').equals("en", ignoreCase = true)
}
