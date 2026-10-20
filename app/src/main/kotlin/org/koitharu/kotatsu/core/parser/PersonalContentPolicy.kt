package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga

object PersonalContentPolicy {
    private val adultTags = setOf("adult", "hentai", "pornographic", "pornography", "smut", "erotica", "18+", "r18", "r-18")
    fun allows(manga: Manga): Boolean = manga.contentRating != ContentRating.ADULT &&
        manga.tags.none { it.title.trim().lowercase(java.util.Locale.ROOT) in adultTags }
}
