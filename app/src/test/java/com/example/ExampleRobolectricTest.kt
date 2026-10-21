package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioFingerprintEngine
import com.example.audio.SpeechKeywordExtractor
import com.example.data.AppDatabase
import com.example.data.RecordedAdEntity
import com.example.data.RecordedAdRepository
import com.example.service.AdRecordingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SpotAd Recorder", appName)
    }

    @Test
    fun testAudioFingerprintEngineAdDetection() {
        val adResult = AudioFingerprintEngine.evaluateAdBreak(
            trackId = "spotify:ad:12345",
            trackTitle = "Advertisement",
            artist = "Spotify",
            lengthMillis = 30_000L
        )
        assertTrue(adResult.isAdBreak)
        assertTrue(adResult.confidence >= 0.7f)

        val musicResult = AudioFingerprintEngine.evaluateAdBreak(
            trackId = "spotify:track:98765",
            trackTitle = "Bohemian Rhapsody",
            artist = "Queen",
            lengthMillis = 354_000L
        )
        assertFalse(musicResult.isAdBreak)
    }

    @Test
    fun testSpeechKeywordExtractor() {
        val extracted = SpeechKeywordExtractor.extract(
            title = "Special Spotify Promotion",
            advertiser = "Spotify",
            durationMillis = 30_000L,
            speechRecognizedText = "Get three months of Spotify Premium for free. Enjoy unlimited music."
        )
        assertTrue(extracted.keywords.any { it.contains("free", ignoreCase = true) || it.contains("premium", ignoreCase = true) })
        assertTrue(extracted.themes.contains("Subscription") || extracted.themes.contains("Special Offer"))
        assertNotNull(extracted.transcriptSnippet)
    }

    @Test
    fun testRoomDatabaseAdPersistence() = runBlocking {
        val dao = db.recordedAdDao()
        val entity = RecordedAdEntity(
            title = "Test Ad",
            advertiser = "Test Advertiser",
            filePath = "/data/user/0/com.example/files/recorded_ads/test.mp3",
            durationMillis = 15_000L,
            timestamp = System.currentTimeMillis(),
            fileSizeBytes = 25000L,
            spokenKeywords = "save, promo",
            themeTags = "Special Offer",
            isFavorite = false
        )

        val id = dao.insert(entity)
        assertTrue(id > 0)

        val retrieved = dao.getAdById(id)
        assertNotNull(retrieved)
        assertEquals("Test Ad", retrieved?.title)
        assertEquals("Test Advertiser", retrieved?.advertiser)

        dao.updateFavorite(id, true)
        val updated = dao.getAdById(id)
        assertTrue(updated?.isFavorite == true)

        val allAds = dao.getAllAds().first()
        assertEquals(1, allAds.size)

        dao.deleteById(id)
        val emptyList = dao.getAllAds().first()
        assertTrue(emptyList.isEmpty())
    }

    @Test
    fun testAutomated30DayCleanupExemptsFavorites() = runBlocking {
        val dao = db.recordedAdDao()
        val now = System.currentTimeMillis()
        val thirtyFiveDaysAgo = now - (35L * 24 * 60 * 60 * 1000)
        val fiveDaysAgo = now - (5L * 24 * 60 * 60 * 1000)

        // 1. Old Ad (35 days old, non-favorite) -> SHOULD be deleted
        dao.insert(
            RecordedAdEntity(
                id = 101L,
                title = "Old Promo",
                advertiser = "Brand A",
                filePath = "/tmp/old_ad.mp3",
                durationMillis = 15000L,
                timestamp = thirtyFiveDaysAgo,
                isFavorite = false
            )
        )

        // 2. Old Ad (35 days old, FAVORITE) -> MUST be preserved
        dao.insert(
            RecordedAdEntity(
                id = 102L,
                title = "Favorite Jingle",
                advertiser = "Brand B",
                filePath = "/tmp/fav_ad.mp3",
                durationMillis = 20000L,
                timestamp = thirtyFiveDaysAgo,
                isFavorite = true
            )
        )

        // 3. Recent Ad (5 days old, non-favorite) -> MUST be preserved
        dao.insert(
            RecordedAdEntity(
                id = 103L,
                title = "Recent Ad",
                advertiser = "Brand C",
                filePath = "/tmp/recent_ad.mp3",
                durationMillis = 30000L,
                timestamp = fiveDaysAgo,
                isFavorite = false
            )
        )

        assertEquals(3, dao.getAllAds().first().size)
        assertEquals(1, repository.countOldRecordings(30))

        // Run automated cleanup
        val (deletedCount, _) = repository.cleanupOldRecordings(days = 30)
        assertEquals(1, deletedCount)

        val remaining = dao.getAllAds().first()
        assertEquals(2, remaining.size)

        // Confirm Favorite (102) and Recent (103) survived, Old non-fav (101) deleted
        assertTrue(remaining.any { it.id == 102L && it.isFavorite })
        assertTrue(remaining.any { it.id == 103L })
        assertFalse(remaining.any { it.id == 101L })
    }

    @Test
    fun testTravelModePreferenceToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AdRecordingService.setTravelModePreference(context, true)
        assertTrue(AdRecordingService.isTravelMode(context))

        AdRecordingService.setTravelModePreference(context, false)
        assertFalse(AdRecordingService.isTravelMode(context))
    }
}
