package dev.example.tiktok.api

/**
 * Something that can turn a TikTok video URL into playable metadata.
 *
 * We have more than one implementation of this (direct page scrape, tikwm
 * fallback) so that a hiccup in one doesn't take the whole plugin down —
 * see [dev.example.tiktok.TikTokAudioSourceManager] for how they're chained.
 */
interface TikTokResolver {
    fun resolveByUrl(url: String): TikTokTrackInfo?
}
