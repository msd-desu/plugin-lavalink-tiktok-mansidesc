package dev.example.tiktok

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import dev.example.tiktok.api.DirectTikTokResolver
import dev.example.tiktok.api.TikTokApiClient
import dev.example.tiktok.api.TikTokTrackInfo
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.DataInput
import java.io.DataOutput
import java.util.concurrent.TimeUnit

/**
 * Registers this class as a Lavalink audio source. Lavalink's plugin loader
 * picks up any @Service that implements AudioSourceManager automatically —
 * no extra registration file needed.
 *
 * Usage from a bot:
 *   - Direct link: just queue a normal tiktok.com/... URL
 *   - Search:      "tiktoksearch:phonk edit sound"
 */
@Service
class TikTokAudioSourceManager : AudioSourceManager {

    companion object {
        const val SEARCH_PREFIX = "tiktoksearch:"
        private val log = LoggerFactory.getLogger(TikTokAudioSourceManager::class.java)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Force HTTP/1.1: we saw sporadic Http2Stream timeout errors talking
        // to tikwm — some reverse proxies handle long-lived HTTP/2 streams
        // poorly. HTTP/1.1 is a bit less efficient but far more predictable
        // for this kind of short-lived request/response API call.
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    // Primary: scrape TikTok's own page directly, no third party involved.
    private val directResolver = DirectTikTokResolver(httpClient)

    // Fallback: tikwm.com. Also the only source of `tiktoksearch:` results
    // for now, since search requires TikTok's own signed internal API,
    // which direct-page scraping doesn't give us access to.
    val apiClient = TikTokApiClient(httpClient)

    override fun getSourceName(): String = "tiktok"

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier

        return when {
            identifier.startsWith(SEARCH_PREFIX) -> {
                val query = identifier.substring(SEARCH_PREFIX.length).trim()
                if (query.isEmpty()) return null
                loadSearch(query)
            }
            apiClient.looksLikeTikTokUrl(identifier) -> loadUrl(identifier)
            else -> null // not a TikTok identifier, let other source managers try
        }
    }

    /**
     * Tries the direct page scrape first; only falls back to tikwm if that
     * didn't work (page structure changed, TikTok blocked the request, the
     * video needs the fallback for some other reason, etc). Used both for
     * the initial `loadItem` lookup and for [TikTokAudioTrack]'s re-resolve
     * right before playback.
     */
    fun resolveTrack(url: String): TikTokTrackInfo? {
        directResolver.resolveByUrl(url)?.let {
            log.debug("Resolved '{}' via direct TikTok page scrape", url)
            return it
        }

        log.debug("Direct resolve failed for '{}', falling back to tikwm", url)
        return apiClient.resolveByUrl(url)
    }

    private fun loadUrl(url: String): AudioItem? {
        val info = resolveTrack(url) ?: return null
        return buildTrack(info)
    }

    private fun loadSearch(query: String): AudioItem? {
        val results = apiClient.search(query)
        if (results.isEmpty()) return null

        val tracks = results.map { buildTrack(it) }
        return BasicAudioPlaylist("TikTok search results for: $query", tracks, tracks.first(), true)
    }

    private fun buildTrack(info: TikTokTrackInfo): AudioTrack {
        val trackInfo = AudioTrackInfo(
            info.title,
            info.author,
            info.durationMs,
            info.identifier,
            false,
            info.sourceUrl,
            info.artworkUrl,
            null
        )
        return TikTokAudioTrack(trackInfo, this)
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean = true

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        // AudioTrackInfo's standard fields already carry everything we need
        // (identifier = tiktok video id, uri = tiktok.com link), so there's
        // no extra plugin-specific data to persist here.
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        return TikTokAudioTrack(trackInfo, this)
    }

    override fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
