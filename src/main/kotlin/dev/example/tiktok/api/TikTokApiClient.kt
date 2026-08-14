package dev.example.tiktok.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

/**
 * Talks to TikTok's video metadata for us.
 *
 * IMPORTANT / MAINTENANCE NOTE:
 * TikTok has no official public API for this. Reverse-engineering their own
 * signed endpoints (X-Bogus / msToken / etc.) breaks every few weeks and is a
 * huge time sink to keep up with (this is literally why the DuncteBot plugin
 * dropped TikTok support).
 *
 * Instead, this client uses tikwm.com — a widely-used free third-party
 * resolver that a lot of TikTok download bots rely on because it absorbs the
 * signing churn for you. Trade-off: you now depend on a third party's
 * uptime/rate limits instead of TikTok's directly.
 *
 * If tikwm ever dies or starts blocking you, the fix is localized to this one
 * file: swap BASE_URL / parsing logic for another resolver, or implement your
 * own signing here. Everything else in the plugin (source manager, track,
 * encode/decode) does not need to change.
 */
class TikTokApiClient(private val httpClient: OkHttpClient) {

    private val mapper = ObjectMapper()

    companion object {
        private const val BASE_URL = "https://www.tikwm.com/api"
        private val VIDEO_URL_PATTERN: Pattern = Pattern.compile(
            "https?://(www\\.|vm\\.|vt\\.|m\\.)?tiktok\\.com/\\S+",
            Pattern.CASE_INSENSITIVE
        )
    }

    fun looksLikeTikTokUrl(input: String): Boolean = VIDEO_URL_PATTERN.matcher(input).find()

    /**
     * Resolves a single TikTok video/sound URL into playable metadata.
     * Returns null if the API couldn't resolve it (private video, deleted, etc.)
     */
    fun resolveByUrl(url: String): TikTokTrackInfo? {
        val request = Request.Builder()
            .url("$BASE_URL/?url=${java.net.URLEncoder.encode(url, "UTF-8")}&hd=1")
            .header("User-Agent", "Mozilla/5.0 (Lavalink TikTok plugin)")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = mapper.readTree(body)
            if (root.path("code").asInt(-1) != 0) return null
            return parseTrackInfo(root.path("data"))
        }
    }

    /**
     * Searches TikTok "sounds"/videos by keyword. Used for `tiktoksearch:` prefix.
     */
    fun search(query: String, limit: Int = 10): List<TikTokTrackInfo> {
        val request = Request.Builder()
            .url(
                "$BASE_URL/feed/search?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$limit"
            )
            .header("User-Agent", "Mozilla/5.0 (Lavalink TikTok plugin)")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = mapper.readTree(body)
            if (root.path("code").asInt(-1) != 0) return emptyList()

            val videos = root.path("data").path("videos")
            if (!videos.isArray) return emptyList()

            return videos.mapNotNull { parseTrackInfo(it) }
        }
    }

    private fun parseTrackInfo(node: JsonNode): TikTokTrackInfo? {
        if (node.isMissingNode) return null

        val id = node.path("id").asText(null) ?: return null
        val title = node.path("title").asText("TikTok video").let {
            if (it.isBlank()) "TikTok video" else it
        }
        val author = node.path("author").path("nickname").asText(
            node.path("author").path("unique_id").asText("Unknown")
        )
        val durationSeconds = node.path("duration").asLong(0)
        val coverUrl = node.path("cover").asText(null) ?: node.path("origin_cover").asText(null)

        // Prefer the music-only stream (pure audio) when TikTok provides one,
        // fall back to the muxed video URL — lavaplayer can still pull the
        // audio track out of an mp4 container.
        val musicUrl = node.path("music").asText(null)
        val playUrl = node.path("play").asText(null) ?: node.path("hdplay").asText(null)

        val streamUrl = musicUrl ?: playUrl ?: return null
        val isAudioOnly = musicUrl != null

        return TikTokTrackInfo(
            identifier = id,
            title = title,
            author = author,
            durationMs = durationSeconds * 1000,
            streamUrl = streamUrl,
            isAudioOnly = isAudioOnly,
            artworkUrl = coverUrl,
            sourceUrl = "https://www.tiktok.com/@${node.path("author").path("unique_id").asText("i")}/video/$id"
        )
    }
}

data class TikTokTrackInfo(
    val identifier: String,
    val title: String,
    val author: String,
    val durationMs: Long,
    val streamUrl: String,
    val isAudioOnly: Boolean,
    val artworkUrl: String?,
    val sourceUrl: String
)
