package com.example.audio

import java.util.Locale

/**
 * Turns the text we actually have (the Spotify ad title / advertiser, plus recognised speech when
 * available) into a few useful keywords and theme tags.
 *
 * It is a dictionary matcher, not a language model, and it never invents a transcript. If no
 * speech recognition result exists, [ExtractedMetadata.transcriptSnippet] is empty and the UI
 * shows "No transcript available".
 */
object SpeechKeywordExtractor {

    data class ExtractedMetadata(
        val keywords: List<String>,
        val themes: List<String>,
        val transcriptSnippet: String
    )

    private val THEME_RULES: List<Pair<String, List<String>>> = listOf(
        "Special offer" to listOf("discount", "save", "deal", "offer", "free", "promo", "sale", "% off", "cashback", "coupon"),
        "Subscription" to listOf("premium", "subscribe", "subscription", "unlimited", "monthly", "cancel anytime", "family plan", "student plan"),
        "Food & drink" to listOf("burger", "pizza", "coffee", "restaurant", "delivery", "taste", "menu", "snack", "drink", "brew"),
        "Tech & mobile" to listOf("app", "download", "smartphone", "5g", "cloud", "software", "device", "wifi", "phone"),
        "Fashion & retail" to listOf("collection", "outfit", "style", "shoes", "wardrobe", "shop", "wear", "brand"),
        "Automotive & travel" to listOf("car", "drive", "engine", "flight", "hotel", "trip", "destination", "insurance"),
        "Entertainment" to listOf("movie", "series", "stream", "watch", "game", "tickets", "concert"),
        "Health & fitness" to listOf("workout", "fitness", "health", "gym", "vitamin", "sleep", "therapy"),
        "Finance" to listOf("bank", "credit card", "loan", "invest", "save money", "crypto", "insurance")
    )

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "your", "you", "our", "this", "that", "from", "now",
        "get", "new", "all", "are", "was", "have", "has", "will", "can", "just", "spotify"
    )

    fun extract(
        title: String,
        advertiser: String,
        durationMillis: Long,
        speechRecognizedText: String? = null
    ): ExtractedMetadata {
        val transcript = speechRecognizedText?.trim().orEmpty()
        val available = buildString {
            if (transcript.isNotEmpty()) append(transcript).append(' ')
            append(title).append(' ').append(advertiser)
        }
        val haystack = available.lowercase(Locale.ROOT)

        val themes = linkedSetOf<String>()
        val keywords = linkedSetOf<String>()

        for ((theme, triggers) in THEME_RULES) {
            val matched = triggers.filter { trigger -> haystack.contains(trigger) }
            if (matched.isNotEmpty()) {
                themes += theme
                matched.take(2).forEach { keywords += it }
            }
        }

        // Prefer words that look like campaign-specific copy over the generic title words.
        if (transcript.isNotEmpty()) {
            transcript.split(Regex("[^A-Za-z0-9&'%]+"))
                .asSequence()
                .map { it.trim() }
                .filter { it.length in 3..18 }
                .filter { it.lowercase(Locale.ROOT) !in STOP_WORDS }
                .take(8)
                .forEach { keywords += it }
        }

        if (keywords.isEmpty() && title.isNotBlank()) {
            keywords += title.trim().take(40)
        }

        if (themes.isEmpty() && durationMillis in 1..20_000) {
            themes += "Short spot"
        }

        return ExtractedMetadata(
            keywords = keywords.take(6).toList(),
            themes = themes.take(3).toList(),
            transcriptSnippet = transcript.take(400)
        )
    }
}
