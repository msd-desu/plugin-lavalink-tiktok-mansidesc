package dev.example.tiktok.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

/**
 * Resolves TikTok videos by fetching the public video page directly and
 * parsing the JSON blob TikTok embeds in the page for their own web app to
 * hydrate from.
 *
 * This is how most TikTok downloader tools actually work day to day — no
 * reverse-engineered signed API calls needed, because this is just reading
 * a normal public webpage, same as opening it in a browser. The main thing
 * that breaks this over time is TikTok renaming/restructuring the embedded
 * JSON blob (they've done this before: SIGI_STATE -> __UNIVERSAL_DATA...),
 * which is why we try both known formats below and fall back to
 * [TikTokApiClient] (tikwm) entirely if neither is found.
 */
class DirectTikTokResolver(private val httpClient: OkHttpClient) : TikTokResolver {

    private val mapper = ObjectMapper()

    companion object {
        private val log = LoggerFactory.getLogger(DirectTikTokResolver::class.java)

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        // Current format (as of writing): TikTok embeds page data under this
        // script tag id.
        private val UNIVERSAL_DATA_PATTERN = Pattern.compile(
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>",
            Pattern.DOTALL
        )

        // Older/alternate script tag id — kept as a fallback in case TikTok
        // serves a different page variant (e.g. to certain user agents).
        private val SIGI_STATE_PATTERN = Pattern.compile(
            "<script id=\"SIGI_STATE\"[^>]*>(.*?)</script>",
            Pattern.DOTALL
        )
    }

    override fun resolveByUrl(url: String): TikTokTrackInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.debug("Direct TikTok page fetch failed with HTTP {}", response.code)
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            log.debug("Direct TikTok page fetch threw: {}", e.message)
            return null
        } ?: return null

        return parseUniversalData(html) ?: parseSigiState(html)
    }

    private fun parseUniversalData(html: String): TikTokTrackInfo? {
        val matcher = UNIVERSAL_DATA_PATTERN.matcher(html)
        if (!matcher.find()) return null

        return try {
            val root = mapper.readTree(matcher.group(1))
            val itemStruct = root
                .path("__DEFAULT_SCOPE__")
                .path("webapp.video-detail")
                .path("itemInfo")
                .path("itemStruct")

            if (itemStruct.isMissingNode) null else buildFromItemStruct(itemStruct)
        } catch (e: Exception) {
            log.debug("Failed to parse __UNIVERSAL_DATA_FOR_REHYDRATION__ blob: {}", e.message)
            null
        }
    }

    private fun parseSigiState(html: String): TikTokTrackInfo? {
        val matcher = SIGI_STATE_PATTERN.matcher(html)
        if (!matcher.find()) return null

        return try {
            val root = mapper.readTree(matcher.group(1))
            val itemModule = root.path("ItemModule")
            val firstKey = itemModule.fieldNames().asSequence().firstOrNull() ?: return null
            buildFromItemStruct(itemModule.path(firstKey))
        } catch (e: Exception) {
            log.debug("Failed to parse SIGI_STATE blob: {}", e.message)
            null
        }
    }

    private fun buildFromItemStruct(item: JsonNode): TikTokTrackInfo? {
        if (item.isMissingNode) return null

        val id = item.path("id").asText(null) ?: return null
        val title = TitleSanitizer.sanitize(item.path("desc").asText(null))
        val author = item.path("author").let { a ->
            a.path("nickname").asText(null) ?: a.path("uniqueId").asText("Unknown")
        }
        val authorHandle = item.path("author").path("uniqueId").asText("i")

        val video = item.path("video")
        val durationSeconds = video.path("duration").asLong(0)
        val coverUrl = video.path("cover").asText(null) ?: video.path("originCover").asText(null)

        // Prefer the music-only stream so we're not carrying a video
        // container around for nothing, fall back to the muxed video's
        // play address (lavaplayer can still pull audio out of an mp4).
        val musicUrl = item.path("music").path("playUrl").asText(null)
        val playUrl = video.path("playAddr").asText(null) ?: video.path("downloadAddr").asText(null)

        val streamUrl = musicUrl ?: playUrl ?: return null
        val isAudioOnly = musicUrl != null

        log.debug("Direct-parsed: id={} title='{}' author='{}'", id, title, author)

        return TikTokTrackInfo(
            identifier = id,
            title = title,
            author = author,
            durationMs = durationSeconds * 1000,
            streamUrl = streamUrl,
            isAudioOnly = isAudioOnly,
            artworkUrl = coverUrl,
            sourceUrl = "https://www.tiktok.com/@$authorHandle/video/$id"
        )
    }
}
