package com.spotad.recorder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotad.recorder.audio.AudioFingerprintEngine
import com.spotad.recorder.audio.AudioRecorderManager
import com.spotad.recorder.audio.SpeechKeywordExtractor
import com.spotad.recorder.audio.WavTrimmer
import com.spotad.recorder.data.AppDatabase
import com.spotad.recorder.data.RecordedAdEntity
import com.spotad.recorder.data.RecordedAdRepository
import com.spotad.recorder.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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


    private fun writeTestWav(directory: File, name: String, seconds: Int, sampleRate: Int = 8_000): File {
        val file = File(directory, name)
        val frames = sampleRate * seconds
        val pcm = ByteArray(frames * 2) { (it % 251).toByte() }
        file.outputStream().use { out ->
            out.write(AudioRecorderManager.wavHeader(pcm.size.toLong(), sampleRate))
            out.write(pcm)
        }
        return file
    }

    @Test
    fun wavInfoReportsRealDuration() {
        val dir = File.createTempFile("spotad_wav", "").apply { delete(); mkdirs() }
        try {
            val file = writeTestWav(dir, "two-seconds.wav", seconds = 2)
            val info = WavTrimmer.readInfo(file)
            assertNotNull(info)
            assertEquals(2_000L, info!!.durationMillis)
            assertEquals(8_000, info.sampleRate)
            assertEquals(16, info.bitsPerSample)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nonWavFileIsNotTrimmed() {
        val dir = File.createTempFile("spotad_wav_bad", "").apply { delete(); mkdirs() }
        try {
            val file = File(dir, "not-a-wav.wav")
            file.writeText("this is not audio")
            assertNull(WavTrimmer.readInfo(file))
            assertNull(WavTrimmer.trim(file, File(dir, "out.wav"), 0, 100))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun trimProducesShorterValidWav() {
        val dir = File.createTempFile("spotad_trim", "").apply { delete(); mkdirs() }
        try {
            val source = writeTestWav(dir, "source.wav", seconds = 2)
            val destination = File(dir, "trimmed.wav")

            val info = WavTrimmer.trim(source, destination, startMillis = 500, endMillis = 1_500)
            assertNotNull(info)
            assertEquals(1_000L, info!!.durationMillis)
            // 1 s * 8 000 frames * 2 bytes + 44-byte header.
            assertEquals(44L + 16_000L, destination.length())

            val reread = WavTrimmer.readInfo(destination)
            assertNotNull(reread)
            assertEquals(8_000, reread!!.sampleRate)
            assertEquals(1_000L, reread.durationMillis)
            // The original must be untouched by trim().
            assertEquals(44L + 32_000L, source.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun trimInPlaceReplacesFileAndKeepsItValid() {
        val dir = File.createTempFile("spotad_trim_inplace", "").apply { delete(); mkdirs() }
        try {
            val file = writeTestWav(dir, "clip.wav", seconds = 3)
            val info = WavTrimmer.trimInPlace(file, startMillis = 1_000, endMillis = 2_000)
            assertNotNull(info)
            assertEquals(1_000L, info!!.durationMillis)
            assertEquals(44L + 16_000L, file.length())
            // No temporary or backup files are left behind.
            assertEquals(1, dir.listFiles()?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun trimRangeOutsideFileIsClampedOrRejected() {
        val dir = File.createTempFile("spotad_trim_clamp", "").apply { delete(); mkdirs() }
        try {
            val source = writeTestWav(dir, "short.wav", seconds = 1)
            // Asking for a window that starts after the end must fail rather than emit junk.
            assertNull(WavTrimmer.trim(source, File(dir, "bad.wav"), 5_000, 6_000))
            // Asking for a window past the end is clamped to the real length.
            val info = WavTrimmer.trim(source, File(dir, "ok.wav"), 0, 9_000)
            assertNotNull(info)
            assertEquals(1_000L, info!!.durationMillis)
        } finally {
            dir.deleteRecursively()
        }
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
            filePath = "/data/user/0/com.spotad.recorder/files/ads/test.wav",
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
