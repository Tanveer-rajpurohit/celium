package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.search.ParsedQuery
import com.lagradost.cloudstream3.ui.search.RankCandidate
import com.lagradost.cloudstream3.ui.search.SearchRanking
import com.lagradost.cloudstream3.ui.search.TitleCleaner

/** One result from one source. [sourceId] is the catalog id ("" for a result taken from a loaded shelf). */
internal class SearchHit(val item: SearchResponse, val sourceId: String, val sourceOrder: Int)

/** One title, possibly offered by several sources. [best] is the hit shown and opened. */
internal class MergedHit(val best: SearchHit, val hits: List<SearchHit>, val year: Int?) {
    val sourceCount: Int get() = hits.map { it.item.apiName }.distinct().size
}

/**
 * Groups the same title across sources and ranks the groups (Celium search, steps 4 and 5).
 * A group needs an equal clean title, a compatible year (equal, or one missing) and no movie/series conflict.
 * Pure logic apart from the [SearchResponse] it reads; JVM tested.
 */
internal object CeliumSearchMerge {
    private enum class Kind { Movie, Series }

    private fun kindOf(type: TvType?): Kind? = when (type) {
        TvType.Movie, TvType.AnimeMovie -> Kind.Movie
        TvType.TvSeries, TvType.AsianDrama -> Kind.Series
        else -> null
    }

    fun yearOf(item: SearchResponse): Int? =
        SearchRanking.yearOf(item) ?: TitleCleaner.cleanCached(item.name).year

    private class Cluster(var year: Int?, var kind: Kind?) {
        val hits = ArrayList<SearchHit>()
    }

    /** Groups [hits] in order of first appearance. */
    fun group(hits: List<SearchHit>): List<MergedHit> {
        val clusters = ArrayList<Cluster>()
        val buckets = HashMap<String, MutableList<Cluster>>()
        for (hit in hits) {
            val clean = TitleCleaner.cleanCached(hit.item.name)
            val key = clean.baseCompact.ifEmpty { "url:" + CeliumDedup.key(hit.item.url) }
            val year = yearOf(hit.item)
            val kind = kindOf(hit.item.type)
            val bucket = buckets.getOrPut(key) { ArrayList() }
            val cluster = bucket.firstOrNull { c ->
                (c.year == null || year == null || c.year == year) &&
                    (c.kind == null || kind == null || c.kind == kind)
            } ?: Cluster(year, kind).also { bucket.add(it); clusters.add(it) }
            cluster.hits.add(hit)
            if (cluster.year == null) cluster.year = year
            if (cluster.kind == null) cluster.kind = kind
        }
        return clusters.map { c ->
            // Shown and opened: a hit with artwork first, then the better source order, then the shorter name.
            val best = c.hits.sortedWith(
                compareByDescending<SearchHit> { it.item.posterUrl != null }
                    .thenBy { it.sourceOrder }
                    .thenBy { it.item.name.length }
            ).first()
            MergedHit(best, c.hits.toList(), c.year)
        }
    }

    /** Groups then ranks. [fits] says whether a hit's own type matches the category being searched. */
    fun results(parsed: ParsedQuery, hits: List<SearchHit>, fits: (SearchResponse) -> Boolean): List<MergedHit> {
        val merged = group(hits)
        return SearchRanking.rankCandidates(parsed, merged.map { m ->
            RankCandidate(
                item = m,
                titles = m.hits.map { it.item.name }.distinct(),
                year = m.year,
                hasPoster = m.best.item.posterUrl != null,
                sourceCount = m.sourceCount,
                typeFit = fits(m.best.item),
                sourceOrder = m.hits.minOf { it.sourceOrder },
            )
        })
    }

    /** What the Movies, TV Shows and Anime categories treat as their own type when ranking. */
    fun typeFits(section: CeliumSection, home: Boolean): (SearchResponse) -> Boolean = when {
        home -> { _ -> false }
        section == CeliumSection.Movies -> { r -> r.type == TvType.Movie || r.type == TvType.AnimeMovie }
        section == CeliumSection.Series -> { r -> r.type == TvType.TvSeries || r.type == TvType.AsianDrama }
        section == CeliumSection.Anime -> { r -> r.type == TvType.Anime || r.type == TvType.AnimeMovie || r.type == TvType.OVA }
        else -> { _ -> false }
    }
}
