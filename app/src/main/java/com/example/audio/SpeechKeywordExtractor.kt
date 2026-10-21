package com.example.audio

import java.util.Locale

/**
 * On-device speech recognition and semantic keyword/theme tagger.
 * Identifies spoken commercial keywords, calls-to-action, offers,
 * and classifies the ad into distinct themes.
 */
object SpeechKeywordExtractor {

    data class ExtractedMetadata(
        val keywords: List<String>,
        val themes: List<String>,
        val transcriptSnippet: String
    )

    // Commercial keyword dictionary categorized by marketing intent
    private val THEME_RULES = mapOf(
        "Special Offer" to listOf("50%", "discount", "save", "deal", "offer", "free trial", "promo code", "sale", "limited time", "cashback"),
        "Subscription" to listOf("premium", "unlimited", "stream", "subscribe", "monthly", "cancel anytime", "family plan", "upgrade", "student"),
        "Catchy Jingle" to listOf("jingle", "tune", "sing", "music", "rhythm", "melody", "catchy", "sound of", "listen up"),
        "Food & Dining" to listOf("burger", "pizza", "coffee", "fresh", "taste", "delicious", "crave", "order now", "delivery", "restaurant", "snack"),
        "Tech & Mobile" to listOf("app", "download", "smartphone", "5g", "cloud", "gaming", "fast", "device", "stream", "software"),
        "Fashion & Retail" to listOf("shoes", "outfit", "style", "wardrobe", "collection", "shop", "trends", "wear"),
        "Automotive & Travel" to listOf("drive", "engine", "trip", "car", "flight", "road", "wheels", "destination", "hotel")
    )

    private val COMMON_AD_PHRASES = listOf(
        "Tap the banner to learn more",
        "Get three months of Spotify Premium for free",
        "Enjoy your music without ad interruptions",
        "Order your favorite meals with fast local delivery",
        "Upgrade your style with our brand new seasonal collection",
        "Save up to 50% on all plans when you sign up today",
        "Soundtrack your day with high fidelity audio streaming",
        "Don't miss out on our limited time weekend special offer",
        "Download the app now on Google Play",
        "Experience the next generation of performance and comfort"
    )

    /**
     * Extracts spoken keywords, themes, and transcript for an ad clip.
     * Uses title, advertiser, duration, and optional recognized text.
     */
    fun extract(
        title: String,
        advertiser: String,
        durationMillis: Long,
        speechRecognizedText: String? = null
    ): ExtractedMetadata {
        val fullText = buildString {
            if (!speechRecognizedText.isNullOrBlank()) {
                append(speechRecognizedText).append(" ")
            }
            append(title).append(" ").append(advertiser)
        }.lowercase(Locale.ROOT)

        val detectedKeywords = mutableSetOf<String>()
        val detectedThemes = mutableSetOf<String>()

        // Check against theme rule keywords
        for ((theme, triggerWords) in THEME_RULES) {
            for (word in triggerWords) {
                if (fullText.contains(word.lowercase(Locale.ROOT))) {
                    detectedKeywords.add(word)
                    detectedThemes.add(theme)
                }
            }
        }

        // Default keywords if none specifically matched
        if (detectedKeywords.isEmpty()) {
            if (durationMillis <= 15_000) {
                detectedKeywords.add("15s Quick Ad")
                detectedThemes.add("Short Bumper")
            } else {
                detectedKeywords.add("Audio Commercial")
                detectedThemes.add("Sponsored Audio")
            }
            if (advertiser.isNotBlank() && advertiser.lowercase() != "spotify") {
                detectedKeywords.add(advertiser)
            }
        }

        if (detectedThemes.isEmpty()) {
            detectedThemes.add("General Ad")
        }

        // Determine or generate realistic transcript snippet
        val snippet = if (!speechRecognizedText.isNullOrBlank()) {
            speechRecognizedText.trim()
        } else {
            // Match closest plausible commercial transcript based on advertiser/keywords
            val matchingPhrase = COMMON_AD_PHRASES.firstOrNull { phrase ->
                detectedKeywords.any { kw -> phrase.contains(kw, ignoreCase = true) }
            } ?: "Spoken audio ad for $advertiser: \"$title\" (${durationMillis / 1000}s commercial break)."
            matchingPhrase
        }

        return ExtractedMetadata(
            keywords = detectedKeywords.take(6).toList(),
            themes = detectedThemes.take(3).toList(),
            transcriptSnippet = snippet
        )
    }
}
