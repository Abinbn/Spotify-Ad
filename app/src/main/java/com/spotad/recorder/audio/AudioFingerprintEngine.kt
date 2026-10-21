package com.spotad.recorder.audio

import java.util.Locale

/**
 * Decides whether the metadata Spotify is currently exposing belongs to an ad break.
 *
 * This is intentionally a metadata classifier and not "acoustic AI". Reading a few PCM samples
 * from a phone cannot reliably separate a voice-over from a sung vocal, and the previous
 * implementation produced confident-looking scores that were effectively made up. Spotify
 * clearly labels ads in its media session / notification ("Advertisement", "Ad break",
 * sponsored artist, `spotify:ad:` URIs), and that is what we use.
 *
 * Signals are combined into a confidence score. A single weak signal can never trigger a
 * recording, which keeps normal short tracks out of the library.
 */
object AudioFingerprintEngine {

    data class FingerprintResult(
        val isAdBreak: Boolean,
        val confidence: Float,
        val reason: String
    )

    enum class ContentType { ADVERTISEMENT, PODCAST, MUSIC, UNKNOWN }

    private data class Signal(val label: String, val weight: Float, val strong: Boolean)

    private val AD_TITLE_PATTERN = Regex(
        "(^|\\b)(advertisement|advert|ad break|commercial break|sponsored|promoted)(\\b|$)",
        RegexOption.IGNORE_CASE
    )

    fun isAdvertisementLabel(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (text.equals("ad", ignoreCase = true)) return true
        if (text.equals("ads", ignoreCase = true)) return true
        return AD_TITLE_PATTERN.containsMatchIn(text)
    }

    /**
     * @param trackId Spotify's track URI when available (`spotify:ad:...` for ads).
     * @param trackTitle Title shown in the media notification / session.
     * @param artist Artist or "advertiser" field shown next to the title.
     * @param lengthMillis Track duration when known. `0` means unknown.
     */
    fun evaluateAdBreak(
        trackId: String?,
        trackTitle: String?,
        artist: String?,
        lengthMillis: Long
    ): FingerprintResult {
        val title = trackTitle?.trim().orEmpty()
        val artistName = artist?.trim().orEmpty()
        val id = trackId?.trim().orEmpty().lowercase(Locale.ROOT)

        val signals = mutableListOf<Signal>()

        if (id.startsWith("spotify:ad") || id.contains(":ad:")) {
            signals += Signal("Ad track URI", 0.95f, strong = true)
        }
        if (isAdvertisementLabel(title)) {
            signals += Signal("Ad title", 0.90f, strong = true)
        }
        if (isAdvertisementLabel(artistName)) {
            signals += Signal("Advertiser label", 0.85f, strong = true)
        }
        if (artistName.equals("spotify", ignoreCase = true)) {
            signals += Signal("Spotify-owned audio", 0.35f, strong = false)
        }
        if (title.contains("podcast", ignoreCase = true) && lengthMillis in 1..90_000L) {
            // Short "podcast" entries are usually dynamically inserted host-read ads.
            signals += Signal("Short podcast segment", 0.25f, strong = false)
        }
        if (lengthMillis in 5_000..75_000) {
            signals += Signal("Ad-length clip", 0.20f, strong = false)
        }

        val strong = signals.filter { it.strong }
        val weakScore = signals.filterNot { it.strong }.sumOf { it.weight.toDouble() }.toFloat()
        val strongScore = strong.maxOfOrNull { it.weight } ?: 0f

        val confidence = when {
            strong.isNotEmpty() -> (strongScore + weakScore * 0.25f).coerceAtMost(1f)
            weakScore >= 0.55f -> weakScore.coerceAtMost(0.75f)
            else -> weakScore
        }.coerceIn(0f, 1f)

        // Requiring a strong label (or a very strong combination of weak ones) is what keeps
        // 20-second songs by "Spotify" out of the library.
        val isAd = strong.isNotEmpty() || weakScore >= 0.55f

        val reason = if (signals.isEmpty()) {
            "No ad signals in the current Spotify metadata"
        } else {
            signals.joinToString(" · ") { it.label }
        }

        return FingerprintResult(
            isAdBreak = isAd,
            confidence = if (isAd) maxOf(confidence, 0.7f) else confidence,
            reason = reason
        )
    }

    fun classify(
        isAd: Boolean,
        title: String,
        album: String,
        lengthMillis: Long
    ): ContentType = when {
        isAd -> ContentType.ADVERTISEMENT
        lengthMillis > 600_000L -> ContentType.PODCAST
        album.contains("podcast", ignoreCase = true) -> ContentType.PODCAST
        title.isBlank() -> ContentType.UNKNOWN
        else -> ContentType.MUSIC
    }

    /**
     * Builds a human-readable label for the library. Spotify often exposes only
     * "Advertisement" as the title, which is not useful once the clip is saved, so the
     * advertiser name is folded into the title.
     */
    fun libraryTitle(title: String, advertiser: String): String {
        val cleanTitle = title.trim()
        val cleanAdvertiser = advertiser.trim()
        val titleIsGeneric = cleanTitle.isEmpty() ||
            isAdvertisementLabel(cleanTitle) ||
            cleanTitle.equals("Spotify audio", ignoreCase = true) ||
            cleanTitle.equals("Spotify", ignoreCase = true)
        val advertiserIsGeneric = cleanAdvertiser.isEmpty() ||
            cleanAdvertiser.equals("spotify", ignoreCase = true) ||
            isAdvertisementLabel(cleanAdvertiser)

        return when {
            titleIsGeneric && !advertiserIsGeneric -> "Ad by $cleanAdvertiser"
            titleIsGeneric -> "Spotify ad break"
            else -> cleanTitle
        }
    }

    fun advertiserName(title: String, artist: String): String {
        val cleanArtist = artist.trim()
        val isGeneric = cleanArtist.isEmpty() ||
            cleanArtist.equals("spotify", ignoreCase = true) ||
            isAdvertisementLabel(cleanArtist)
        return if (isGeneric) {
            // Try "Brought to you by <brand>" style titles before giving up.
            val match = Regex("(?:by|from)\\s+([A-Z][\\w&.'-]*(?:\\s+[A-Z][\\w&.'-]*){0,3})")
                .find(title)
            match?.groupValues?.getOrNull(1)?.trim().orEmpty()
        } else {
            cleanArtist
        }
    }
}
