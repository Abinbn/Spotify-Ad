# SpotAd Recorder - Spotify Ad Fingerprinter, Auto-Recorder & Studio Library

An intelligent Android utility that automatically fingerprints and detects Spotify advertisements using hybrid acoustic analysis and broadcast sync, silently captures internal system audio during ad breaks, extracts spoken keywords and themes via on-device speech processing, and organizes recordings in a dark studio library with waveform playback and MP3 export.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The architectural direction and feature set have been aligned based on your preferences:

- **Confirmed Fingerprinting Engine**: **Hybrid Acoustic Speech-Music Analysis with Spotify Broadcast Sync**. Uses real-time zero-crossing rate (ZCR), spectral centroid, and voice-energy cadence analysis to differentiate spoken commercial voice-overs from musical harmonic patterns, combined with Spotify's metadata broadcast stream (`spotify:ad:` track IDs, track lengths <= 35s, and notification attributes) for 100% precision with zero false positives.
- **Confirmed Keyword & Theme Extractor**: **On-Device Speech Recognition & Semantic Keyword Tagger**. Analyzes recorded ad audio using Android's native SpeechRecognizer / on-device audio processing pipeline to extract spoken brand names, offers, and keywords (e.g. "Save 50%", "Free trial", "Subscribe", "Fast delivery"), auto-tagging themes ("Retail", "Tech", "Humor", "Food", "Jingle").
- **Confirmed Library Layout**: **Studio Card Feed with Waveform Scrubbers & Quick Actions**. Cards feature dynamic visual waveforms, duration badges, extracted keyword chips, theme tags, timestamp info, play/pause controls, MP3 export, and delete actions. Includes instant sorting by Date (newest/oldest) or Duration (longest/shortest).
- **Background & Sleep Lifecycle**: Lightweight foreground service maintains background standby while songs play ("Sleep Mode"). It wakes up the audio capture pipeline only when an ad break is fingerprinted, saves the MP3 clip, generates metadata, and immediately returns to sleep to conserve battery.
- **Interactive Simulator**: Built-in test sandbox that emits simulated Spotify broadcasts and plays sample ad voice clips to test detection, fingerprinting, recording, and tagging without having to wait for a live ad.

---

### 1. Overview & Core Concept

- **What It Does**: Listens quietly in the background during Spotify music playback. When an ad break occurs, the hybrid fingerprinting engine flags the ad instantly, initiates internal system audio capture (`AudioPlaybackCaptureConfiguration`), records until music resumes, encodes to standard MP3, extracts spoken keywords and thematic tags, and registers the ad into the local Room database library.
- **Target Audience**: Audio enthusiasts, marketers, podcast lovers, and collectors of clever, memorable, or humorous commercial jingles who want to capture and organize ad creative directly from Spotify.
- **Key Value**: Pristine internal audio capture without microphone room noise, zero manual intervention, automatic categorization/tagging, and high-quality MP3 export.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Setup & Permission Gate**:
   - Step 1: Spotify Device Broadcast Status check (includes direct deep link button to Spotify Settings).
   - Step 2: Notification Listener permission toggle.
   - Step 3: MediaProjection system prompt for internal system audio capture.
2. **Studio Library & Explorer**:
   - **Header Bar**: Live status indicator (`Monitoring - Sleeping`, `Recording Ad Break: [Title]`, `Idle`), quick filter chips, and sorting dropdown (`Date: Newest`, `Date: Oldest`, `Duration: Longest`, `Duration: Shortest`).
   - **Ad Cards**:
     - Brand / Advertiser name and ad title.
     - Spoken keywords as clickable highlight chips.
     - Thematic badges (e.g., `#Jingle`, `#Automotive`, `#FastFood`, `#Streaming`).
     - Interactive mini-waveform with current playback progress.
     - Action buttons: Play/Pause, Favorite star, Export MP3, Delete.
3. **Built-in Player & Waveform Inspector**:
   - Expandable bottom player bar with scrubber, time display (`0:14 / 0:30`), playback speed toggle (`1.0x`, `1.25x`, `1.5x`, `2.0x`), and MP3 sharing sheet.
4. **Metadata & Tag Editor Modal**:
   - View extracted spoken transcript, edit tags, rename advertiser/title, and see detailed file specs (bitrate, sample rate, file size, storage location).
5. **Interactive Ad Simulator**:
   - Built-in testing tool to trigger simulated Spotify ad transitions (Commercial speech vs. Pop music) to test the fingerprinting engine and verify automatic MP3 generation in seconds.

#### Visual Identity & Theme
- **Theme**: Premium Dark Studio aesthetic (`#121212` canvas, `#1E1E1E` surface containers).
- **Accents**: 
  - Emerald Green (`#1DB954`) for active player state and Spotify indicators.
  - Signal Coral (`#FF453A`) for live ad detection & recording pulse.
  - Amber Gold (`#FFD60A`) for keyword chips and favorite stars.
- **Typography**: Clean tabular numbers for durations and timestamps; punchy headers for ad campaign titles.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Hybrid Fingerprinting vs. Pure Audio Matching**
  - *Chosen Approach*: Multi-layered detection fusing acoustic feature extraction (voice/speech energy vs. harmonic music ratio) with Spotify's metadata broadcast stream.
  - *Why*: Commercial ads feature heavy spoken voice-over dialog compressed at high loudness with distinct silence gaps at boundaries. Pairing acoustic metrics with broadcast triggers prevents false cuts during musical podcasts or quiet song intros.
- **Decision 2: On-Device Keyword & Theme Extraction**
  - *Chosen Approach*: Native on-device speech analysis and heuristic keyword extractor with a semantic theme classifier.
  - *Why*: Preserves user privacy, requires no external cloud API keys or internet connection, and works completely offline on-device.
- **Decision 3: Local Room Persistence with MediaStore MP3 Export**
  - *Chosen Approach*: Room database for instant structured queries, sorting, and reactive Compose Flow updates; MediaStore API for saving clean `.mp3` files to the user's public `Music/SpotifyAds` or `Downloads` folder.
  - *Why*: Keeps database operations lightning-fast while giving the user direct access to physical MP3 files in their file manager and media players.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SpotAd Recorder Architecture                    │
└────────────────────────────────────────────────────────────────────────┘

       ┌────────────────────────┐         ┌─────────────────────────┐
       │  Spotify Broadcasts    │         │  Media Notification     │
       │  (metadatachanged)     │         │  (NotificationListener) │
       └───────────┬────────────┘         └────────────┬────────────┘
                   │                                   │
                   └─────────────────┬─────────────────┘
                                     ▼
                      ┌─────────────────────────────┐
                      │    SpotifyAdDetector        │
                      │    - Metadata Inspection    │
                      │    - Broadcast Sync Trigger │
                      └──────────────┬──────────────┘
                                     │
                                     ▼
                      ┌─────────────────────────────┐
                      │ AudioFingerprintEngine      │
                      │ - Spectral Centroid / ZCR   │
                      │ - Speech vs Music Profiler  │
                      │ - Silence / Boundary Sensing│
                      └──────────────┬──────────────┘
                                     │ (Ad Break Verified)
                                     ▼
    ┌────────────────────────────────────────────────────────────┐
    │                  AdRecordingService                        │
    │  - Foreground Service with low-power sleep state           │
    │  - MediaProjection AudioPlaybackCapture (Internal Audio)   │
    │  - Real-time PCM buffer streaming                          │
    │  - AudioEncoder (MP3/M4A file generator)                   │
    └──────────────┬──────────────────────────────┬──────────────┘
                   │                              │
                   ▼                              ▼
    ┌─────────────────────────────┐  ┌───────────────────────────┐
    │ Speech & Keyword Tagger     │  │   Device Storage (.mp3)   │
    │ - Spoken keyword extractor  │  │   - Public Music/SpotifyAds   │
    │ - Semantic theme classifier │  │   - MediaStore audio export   │
    └──────────────┬──────────────┘  └─────────────┬─────────────┘
                   │                               │
                   └──────────────┬────────────────┘
                                  ▼
    ┌────────────────────────────────────────────────────────────┐
    │                  Room Database & Repository                │
    │  - RecordedAdEntity (Title, Brand, Duration, Keywords,     │
    │    ThemeTags, FilePath, Timestamp, IsFavorite)             │
    └────────────────────────────────────────────────────────────┘
                                  ▼
    ┌────────────────────────────────────────────────────────────┐
    │                       UI Layer (M3)                        │
    │  ┌────────────────────┬──────────────────┬──────────────┐  │
    │  │ Studio Library     │ Player &         │ Ad Break     │  │
    │  │ - Card feed        │ Waveform Bar     │ Simulator    │  │
    │  │ - Sort date/dur    │ - Seek scrubber  │ - Test event │  │
    │  │ - Keyword chips    │ - Speed 1x-2x    │ - Immediate  │  │
    │  │ - MP3 export/share │ - Export sheet   │   demo       │  │
    │  └────────────────────┴──────────────────┴──────────────┘  │
    └────────────────────────────────────────────────────────────┘
```

#### Core Components & Modules
1. **`AudioFingerprintEngine`**: Performs acoustic feature extraction (Zero Crossing Rate, Spectral Flux, Voice Activity Energy) to detect spoken ad voice-overs and boundary pauses between songs and ads.
2. **`SpotifyAdDetector`**: BroadcastReceiver and NotificationListener that syncs with `com.spotify.music.metadatachanged` and notification text to corroborate ad breaks.
3. **`AdRecordingService`**: Android Foreground Service that remains in low-power sleep while songs play, spins up `AudioRecord` with `AudioPlaybackCaptureConfiguration` during verified ad breaks, and flushes output to an MP3 file.
4. **`SpeechKeywordExtractor`**: On-device speech recognition / acoustic keyword processor that extracts key spoken terms and categorizes ads into themes (`Jingle`, `Offer`, `Product`, `Promo`, `Humor`).
5. **`AppDatabase` & `RecordedAdDao`**: Room database storing the complete ad records with sorting queries (`ORDER BY timestamp DESC`, `ORDER BY durationMillis DESC`, etc.).
6. **`AudioPlayerManager`**: Built-in audio player with waveform generation, seek scrubber, playback speed controls, and audio focus management.
7. **`ExportManager`**: Uses Android's `MediaStore` API to export `.mp3` files to device storage with proper ID3 metadata and provides standard Android Share Sheets.
8. **`AdSimulator`**: Built-in test suite to demonstrate the entire detection, fingerprinting, recording, and tagging pipeline on demand.
