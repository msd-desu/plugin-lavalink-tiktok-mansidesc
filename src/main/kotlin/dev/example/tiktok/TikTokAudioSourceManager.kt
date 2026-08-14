package dev.example.tiktok

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import dev.example.tiktok.api.TikTokApiClient
import dev.example.tiktok.api.TikTokTrackInfo
import okhttp3.OkHttpClient
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val apiClient = TikTokApiClient(httpClient)

    companion object {
        const val SEARCH_PREFIX = "tiktoksearch:"
    }

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

    private fun loadUrl(url: String): AudioItem? {
        val info = apiClient.resolveByUrl(url) ?: return null
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
