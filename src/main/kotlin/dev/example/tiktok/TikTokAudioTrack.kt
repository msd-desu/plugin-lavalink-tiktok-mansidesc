package dev.example.tiktok

import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor

/**
 * A single TikTok video/sound.
 *
 * TikTok's CDN links expire after a while, so instead of caching the direct
 * URL forever, we re-resolve it right before playback starts. That makes the
 * track resilient to sitting in a queue for a long time before it plays.
 */
class TikTokAudioTrack(
    info: AudioTrackInfo,
    val sourceManager: TikTokAudioSourceManager
) : DelegatedAudioTrack(info) {

    // Kept alive purely so lavaplayer doesn't garbage collect / close it early.
    private val httpSourceManager = HttpAudioSourceManager()

    override fun process(executor: LocalAudioTrackExecutor) {
        // Re-resolve right before playback to get a non-expired CDN url.
        val resolved = sourceManager.apiClient.resolveByUrl(info.uri)
            ?: throw RuntimeException("TikTok video is no longer available: ${info.uri}")

        // Delegate the actual downloading/decoding to lavaplayer's generic
        // HTTP source manager, which auto-detects mp3/mp4/etc containers
        // from a raw stream URL — we don't need to write our own decoder.
        // Passing null for the AudioPlayerManager here: HttpAudioSourceManager's
        // format probing does not need it for a plain direct-URL lookup.
        val delegateItem = httpSourceManager.loadItem(
            null,
            AudioReference(resolved.streamUrl, info.title)
        )

        val delegateTrack = delegateItem as? InternalAudioTrack
            ?: throw RuntimeException("Could not resolve a playable stream for TikTok video: ${info.uri}")

        processDelegate(delegateTrack, executor)
    }

    override fun makeShallowClone(): AudioTrack = TikTokAudioTrack(trackInfo, sourceManager)

    override fun getSourceManager(): TikTokAudioSourceManager = sourceManager
}
