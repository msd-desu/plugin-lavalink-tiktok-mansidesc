package dev.example.tiktok

import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import org.slf4j.LoggerFactory

/**
 * A single TikTok video/sound.
 *
 * TikTok's CDN links expire after a while, so instead of caching the direct
 * URL forever, we re-resolve it right before playback starts. That makes the
 * track resilient to sitting in a queue for a long time before it plays.
 */
class TikTokAudioTrack(
    info: AudioTrackInfo,
    private val manager: TikTokAudioSourceManager
) : DelegatedAudioTrack(info) {

    companion object {
        private val log = LoggerFactory.getLogger(TikTokAudioTrack::class.java)

        // Browser-like User-Agent — TikTok/tikwm CDN links often refuse to
        // serve real media bytes to requests that don't look like a browser,
        // returning an HTML/error page instead. lavaplayer's format probing
        // then (correctly) reports that as "Unknown file format" since it's
        // not actually audio/video data.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    // Kept alive purely so lavaplayer doesn't garbage collect / close it early.
    private val httpSourceManager = HttpAudioSourceManager().also {
        it.configureBuilder { builder -> builder.setUserAgent(USER_AGENT) }
    }

    override fun process(executor: LocalAudioTrackExecutor) {
        // Re-resolve right before playback to get a non-expired CDN url.
        // Goes through the same direct-scrape-first, tikwm-fallback chain
        // as the initial lookup.
        val resolved = manager.resolveTrack(info.uri)
            ?: throw RuntimeException("TikTok video is no longer available: ${info.uri}")

        log.info("Resolved TikTok stream for '{}': {}", info.uri, resolved.streamUrl)

        // Delegate the actual downloading/decoding to lavaplayer's generic
        // HTTP source manager, which auto-detects mp3/mp4/etc containers
        // from a raw stream URL — we don't need to write our own decoder.
        val delegateItem = httpSourceManager.loadItem(
            null,
            AudioReference(resolved.streamUrl, info.title)
        )

        val delegateTrack = delegateItem as? InternalAudioTrack
            ?: throw RuntimeException(
                "Could not resolve a playable stream for TikTok video: ${info.uri} " +
                    "(resolved CDN url: ${resolved.streamUrl})"
            )

        processDelegate(delegateTrack, executor)
    }

    override fun makeShallowClone(): AudioTrack = TikTokAudioTrack(trackInfo, manager)

    override fun getSourceManager(): TikTokAudioSourceManager = manager
}
