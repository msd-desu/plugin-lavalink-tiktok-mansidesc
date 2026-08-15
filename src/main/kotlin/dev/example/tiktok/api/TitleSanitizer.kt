package dev.example.tiktok.api

/**
 * TikTok captions are often stuffed with dozens of hashtags
 * ("#fyp #foryou #viral ...") which can push the raw caption well past
 * Discord's embed title limit (256 chars), silently breaking bots that
 * don't specifically guard against it. We treat hashtags as noise for
 * display purposes and strip them from the title, then hard-cap the
 * length as a safety net regardless.
 */
object TitleSanitizer {

    private const val MAX_TITLE_LENGTH = 150

    fun sanitize(rawDesc: String?): String {
        if (rawDesc.isNullOrBlank()) return "TikTok video"

        val withoutHashtags = rawDesc
            .replace(Regex("#\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val cleaned = withoutHashtags.ifBlank { "TikTok video" }

        return if (cleaned.length > MAX_TITLE_LENGTH) {
            cleaned.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
        } else {
            cleaned
        }
    }
}
