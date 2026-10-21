package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioFingerprintEngine
import com.example.audio.AudioRecorderManager
import com.example.audio.SpeechKeywordExtractor
import com.example.data.AppDatabase
import com.example.data.RecordedAdEntity
import com.example.data.RecordedAdRepository
import com.example.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RecordedAdRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RecordedAdRepository(db.recordedAdDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun appNameIsSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("SpotAd", context.getString(R.string.app_name))
    }

    @Test
    fun adTrackUriIsDetected() {
        val result = AudioFingerprintEngine.evaluateAdBreak(
            trackId = "spotify:ad:12345",
            trackTitle = "Advertisement",
            artist = "Spotify",
            lengthMillis = 30_000L
        )
        assertTrue(result.isAdBreak)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun normalTrackIsNotAnAd() {
        val result = AudioFingerprintEngine.evaluateAdBreak(
            trackId = "spotify:track:98765",
            trackTitle = "Bohemian Rhapsody",
            artist = "Queen",
            lengthMillis = 354_000L
        )
        assertFalse(result.isAdBreak)
    }

    @Test
    fun shortSongIsNotAnAd() {
        // A 30-second interlude must not be flagged just because it is short.
        val result = AudioFingerprintEngine.evaluateAdBreak(
            trackId = null,
            trackTitle = "Interlude",
            artist = "Some Artist",
            lengthMillis = 30_000L
        )
        assertFalse(result.isAdBreak)
    }

    @Test
    fun genericAdTitleUsesAdvertiser() {
        assertEquals(
            "Ad by Acme",
            AudioFingerprintEngine.libraryTitle("Advertisement", "Acme")
        )
        assertEquals(
            "Ad by Acme",
            AudioFingerprintEngine.libraryTitle("", "Acme")
        )
        assertEquals(
            "Acme summer sale",
            AudioFingerprintEngine.libraryTitle("Acme summer sale", "Acme")
        )
    }

    @Test
    fun keywordExtractorOnlyUsesProvidedText() {
        val withoutSpeech = SpeechKeywordExtractor.extract(
            title = "Advertisement",
            advertiser = "Acme",
            durationMillis = 20_000L
        )
        assertEquals("", withoutSpeech.transcriptSnippet)

        val withSpeech = SpeechKeywordExtractor.extract(
            title = "Advertisement",
            advertiser = "Acme",
            durationMillis = 20_000L,
            speechRecognizedText = "Get three months of Spotify Premium for free."
        )
        assertTrue(withSpeech.keywords.any { it.contains("premium", ignoreCase = true) })
        assertTrue(withSpeech.themes.contains("Subscription") || withSpeech.themes.contains("Special offer"))
        assertTrue(withSpeech.transcriptSnippet.contains("three months"))
    }

    @Test
    fun wavHeaderIsValid() {
        val dataLength = 44_100L
        val header = AudioRecorderManager.wavHeader(dataLength, 44_100)
        assertEquals(44, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", header.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))

        val riffSize = (header[4].toInt() and 0xFF) or
            ((header[5].toInt() and 0xFF) shl 8) or
            ((header[6].toInt() and 0xFF) shl 16) or
            ((header[7].toInt() and 0xFF) shl 24)
        assertEquals((36 + dataLength).toInt(), riffSize)

        val sampleRate = (header[24].toInt() and 0xFF) or
            ((header[25].toInt() and 0xFF) shl 8) or
            ((header[26].toInt() and 0xFF) shl 16) or
            ((header[27].toInt() and 0xFF) shl 24)
        assertEquals(44_100, sampleRate)
    }

    @Test
    fun waveformResamplingReturnsRequestedNumberOfBars() {
        val recorder = AudioRecorderManager(ApplicationProvider.getApplicationContext())
        val bars = recorder.resampleWaveform(List(500) { 0.5f }, bars = 32)
        assertEquals(32, bars.size)
        assertTrue(bars.all { it in 0f..1f })
        assertTrue(recorder.resampleWaveform(emptyList(), 32).isEmpty())
    }

    @Test
    fun roomDatabaseRoundTrip() = runBlocking {
        val dao = db.recordedAdDao()
        val entity = RecordedAdEntity(
            title = "Test Ad",
            advertiser = "Test Advertiser",
            filePath = "/data/user/0/com.example/files/ads/test.wav",
            durationMillis = 15_000L,
            fileSizeBytes = 25000L,
            spokenKeywords = "save, promo",
            themeTags = "Special offer",
            sampleRate = 44_100,
            source = "Test"
        )

        val id = dao.insert(entity)
        assertTrue(id > 0)

        val retrieved = dao.getById(id)
        assertNotNull(retrieved)
        assertEquals("Test Ad", retrieved?.title)
        assertEquals(44_100, retrieved?.sampleRate)
        assertEquals(listOf("save", "promo"), retrieved?.keywords)

        dao.setFavorite(id, true)
        assertTrue(dao.getById(id)?.isFavorite == true)

        assertEquals(1, dao.observeAll().first().size)

        dao.delete(retrieved!!)
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun cleanupDeletesOldUnstarredButKeepsStarredAndRecent() = runBlocking {
        val dao = db.recordedAdDao()
        val now = System.currentTimeMillis()
        val thirtyFiveDaysAgo = now - (35L * 24 * 60 * 60 * 1000)
        val fiveDaysAgo = now - (5L * 24 * 60 * 60 * 1000)

        dao.insert(
            RecordedAdEntity(
                title = "Old Promo", advertiser = "Brand A", filePath = "/tmp/old.wav",
                durationMillis = 15_000L, timestamp = thirtyFiveDaysAgo, isFavorite = false
            )
        )
        dao.insert(
            RecordedAdEntity(
                title = "Favorite Jingle", advertiser = "Brand B", filePath = "/tmp/fav.wav",
                durationMillis = 20_000L, timestamp = thirtyFiveDaysAgo, isFavorite = true
            )
        )
        dao.insert(
            RecordedAdEntity(
                title = "Recent Ad", advertiser = "Brand C", filePath = "/tmp/recent.wav",
                durationMillis = 30_000L, timestamp = fiveDaysAgo, isFavorite = false
            )
        )

        assertEquals(3, dao.observeAll().first().size)
        assertEquals(1, repository.countOlderThan(30))

        val deleted = repository.deleteOlderThan(30)
        assertEquals(1, deleted)

        val remaining = dao.observeAll().first()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.title == "Favorite Jingle" && it.isFavorite })
        assertTrue(remaining.any { it.title == "Recent Ad" })
    }

    @Test
    fun settingsPersistAcrossStoreInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore.get(context)
        val original = store.snapshot()

        store.update { it.copy(maxAdSeconds = 90, retentionDays = 7) }
        assertEquals(90, store.snapshot().maxAdSeconds)
        assertEquals(7, store.snapshot().retentionDays)
        assertNotEquals(original.maxAdSeconds, store.snapshot().maxAdSeconds)

        // Restore defaults so other tests in the same process are not affected.
        store.update { it.copy(maxAdSeconds = 120, retentionDays = 30) }
    }
}
