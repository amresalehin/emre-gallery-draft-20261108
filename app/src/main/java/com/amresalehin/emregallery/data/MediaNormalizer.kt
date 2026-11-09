package com.amresalehin.emregallery.data

import java.util.Locale

/**
 * MediaNormalizer - The single source of truth for media candidate processing.
 *
 * Unifies:
 * 1. URL canonicalization and deduplication.
 * 2. Quality-based variant selection (preferring original master files and higher resolutions).
 * 3. Video-poster folding (deduplicating standalone poster images into their parent video).
 * 4. Provenance merging (e.g. "Network + DOM").
 * 5. Download headers and resolution hint retention.
 */
object MediaNormalizer {

    /**
     * Builds and validates a clean ScrapedMediaItem using MediaCaptureEngine.
     */
    fun normalize(
        rawUrl: String,
        source: ExtractionSource,
        baseUrl: String,
        poster: String? = null,
        resolutionHint: String? = null,
        mimeHint: String? = null,
        indexHint: Int = 0,
        titleHint: String? = null,
        domSelector: String? = null,
        declaredWidth: Int? = null,
        declaredHeight: Int? = null,
        customId: String? = null
    ): ScrapedMediaItem? {
        return MediaCaptureEngine.buildMediaItem(
            rawUrl = rawUrl,
            source = source,
            baseUrl = baseUrl,
            poster = poster,
            resolutionHint = resolutionHint,
            mimeHint = mimeHint,
            indexHint = indexHint,
            titleHint = titleHint,
            domSelector = domSelector,
            declaredWidth = declaredWidth,
            declaredHeight = declaredHeight,
            customId = customId
        )
    }

    /**
     * Merges a list of incoming items into an existing mutable map keyed by canonical deduplicationKey.
     * Returns true if any item was added, replaced, or upgraded.
     */
    fun mergeCandidates(
        targetMap: MutableMap<String, ScrapedMediaItem>,
        incoming: List<ScrapedMediaItem>
    ): Boolean {
        return MediaClassifier.mergeMediaItems(targetMap, incoming)
    }

    /**
     * Deduplicates a list of scraped items, folding video poster images and retaining
     * the highest-quality master file for each media asset.
     */
    fun deduplicate(items: List<ScrapedMediaItem>): List<ScrapedMediaItem> {
        return MediaClassifier.deduplicateScrapedItems(items)
    }

    fun deduplicateList(items: List<ScrapedMediaItem>): List<ScrapedMediaItem> {
        return deduplicate(items)
    }

    /**
     * Compares quality between two candidates. Returns > 0 if candidate a is higher quality.
     */
    fun compareQuality(a: ScrapedMediaItem, b: ScrapedMediaItem): Int {
        return MediaClassifier.compareMediaQuality(a, b)
    }

    /**
     * Computes the canonical deduplication key for a media URL.
     */
    fun canonicalKey(url: String): String {
        return MediaClassifier.deduplicationKey(url)
    }
}
