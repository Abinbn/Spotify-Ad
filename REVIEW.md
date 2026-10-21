# SpotAd — production review

_Review date: 2026-10-21 · Branch: `arena/8a31f2e7-spotify-ad`_

This is a full pass over the app: what was broken, what was rebuilt, what still needs a real
device, and which features are worth adding next.

---

## 1. Executive summary

The original build looked complete in the UI but **could not record an ad on a modern phone**
and **would have saved synthetic tones as if they were real recordings**. The parts that decide
"is this an ad", capture audio, and store the file were each doing something different from what
the UI claimed.

The app has been rebuilt around one honest pipeline:

```
Spotify media session / notification
        │  (SpotifyNotificationListener, de-duplicated)
        ▼
Ad decision  (AudioFingerprintEngine: label-based, weighted)
        ▼
Foreground service  (mediaProjection + dataSync FGS, START_STICKY)
        ▼
Playback capture → real PCM WAV (AudioRecorderManager, no fallback audio)
        ▼
Room (facts only) → library UI → share / export
```

Everything is local. No analytics, no cloud, no invented metadata.

---

## 2. Critical defects found and fixed

### 2.1 Recording never worked reliably (highest severity)

| # | Issue | Where | Fix |
|---|-------|-------|-----|
| 1 | The service declared only `dataSync` in `startForeground(...)`, so Android 14+ rejected the capture; the service manifest also lacked `FOREGROUND_SERVICE_MEDIA_PROJECTION`. | `AdRecordingService.startServiceInForeground()` | Service is promoted to `mediaProjection|dataSync` **before** `getMediaProjection()`, matching the Android 14 requirement. |
| 2 | No `MediaProjection.Callback` was registered. Android 14 throws `IllegalStateException` when a capture starts without one. | `MainActivity` / service | Callback registered in the service on a main-thread `Handler`; `onStop` tears the session down and tells the user to re-grant. |
| 3 | The projection was created in the Activity and kept in a static field with no lifetime management; it could be silently dead while the UI still said "capture active". | `MainActivity`, service | Projection owned by the service, released on stop/destroy/`onStop`. `captureGranted` in the UI state is derived from the real object. |
| 4 | **When capture failed, the recorder synthesised a 220/440/880 Hz tone** into the file. Users would have saved fake audio and the app would have looked "working". | `AudioRecorderManager` | Removed entirely. Startup failure now returns an explicit message (`"Android blocked audio capture…"`), shown in the monitoring card and notification. |
| 5 | Silence was saved as a recording. | recorder | Peak amplitude is measured; clips under `0.0025` peak are discarded with an explanation (Spotify/OEMs can block playback capture). |
| 6 | The output file was named `.mp3` but contained a WAV header, and `MediaStore` advertised `audio/mpeg`; exports opened in no player. | recorder, `ExportManager` | Real `.wav` files, `audio/wav` MIME everywhere, correct durations. |
| 7 | The WAV header was written once with a zero data size and never patched; players saw a 0-second file. | recorder | 44-byte placeholder is patched with the final data length when capture ends. |
| 8 | Recorded duration came from a seconds counter, not the audio. A stopped capture could disagree with the file. | service | Duration is derived from bytes written (`bytes / (rate × 2)`). |
| 9 | `AudioRecord` reading used a `ByteArrayOutputStream` that grew for the whole recording in RAM, and the file was only written at the end (loss on kill). | recorder | Streams directly to disk, with a hard byte/time cap. |
| 10 | Nothing stopped a recording if the ad-end event never arrived. | service | Two safety nets: the ticker enforces `maxAdSeconds` (default 120 s) and the projection callback ends capture if access is revoked. |
| 11 | Capture could be started twice (double ad events) and a finalising capture could race a new one. | service | `recorder.isRecording()` + `finalizing` guards, plus a de-dupe window in the listener. |

### 2.2 Detection

- The exported `SpotifyBroadcastReceiver` listened for `com.spotify.music.metadatachanged`, a
  broadcast Spotify has not sent in years. It was also `exported="true"`: **any app could forge
  an "ad started" broadcast** and trigger recordings. Removed (file and manifest entry).
- `"playing"` was inferred from Chinese-whisper heuristics: iterating notification action
  labels, and a label containing `"play"` was treated as paused — but `"Play"` and `"Pause"`
  both matched `"play"` first, so the state flipped inconsistently. Now the `MediaSession`
  `PlaybackState` is authoritative; action labels are only a fallback when no session token
  exists.
- The old acoustic "fingerprint" scored ZCR/PAPR and returned a **0.6 speech score by default**
  when there was no audio, then added `+0.30` for any track shorter than 45 seconds. The result
  was "ads" detected for short songs. The new classifier uses explicit Spotify ad labels
  (`spotify:ad:`, "Advertisement", "Ad break", "Sponsored", advertiser labels), requires a
  strong signal, and a bare short duration is only a weak hint that can never trigger alone.
- The detector "confidence" is now derived from the signals that matched, not a fabricated
  acoustic score, and it is stored with the recording.

### 2.3 Data quality (fabricated content)

- **Transcripts were invented.** When speech recognition had not run, `SpeechKeywordExtractor`
  picked a sentence from a hardcoded list of ten marketing phrases and wrote it into
  `transcriptSnippet`. The app displayed that as a quote from the ad. Removed; the field is empty
  unless real text is supplied, and the UI omits the section.
- Keywords/themes were partially fictional too. They now come only from the available title and
  advertiser text, and fall back to a neutral "Short spot" tag.
- `audioConfidence` was hardcoded to `0.96f` in the service. It now stores the detector's actual
  confidence.

### 2.4 Data layer

- `fallbackToDestructiveMigration()` meant **every schema change wiped the user's library**.
  Replaced with a real `MIGRATION_1_2`, keeping the existing database file name so installed
  users are migrated rather than reset.
- Added indices on `timestamp` and `isFavorite`.
- Repository delete paths removed the row but not always the audio file (or vice versa).
  Every path now removes both, plus `pruneOrphanFiles()` at startup.
- Removed the "purge sample ads" hack: the sample/demo data that needed purging was generated by
  the app itself (`AdSimulator`), which has been deleted.
- Duplicate/ambiguous DAO methods (`getAllAds` + `getAdsSortedByDateDesc` + …) collapsed into
  observable + a single filter/sort in the ViewModel.
- Settings moved from a static flag to a single `SettingsStore` (`StateFlow<AppSettings>`), so the
  UI and service can never disagree.

### 2.5 Service and lifecycle

- `MainActivity` started the foreground service in `onCreate` unconditionally, even when the user
  had never enabled monitoring — a permanent notification for a feature that was off.
  Monitoring is now explicit and persisted; the service is started only by the user.
- `stopAdRecording(cancel = true)` was called in `onDestroy` after state had already moved away
  from `RECORDING`, so the recorder was often left open.
- The "Travel mode" feature switched the whole app to an orange theme and disabled the service.
  It had no remaining purpose once monitoring can simply be paused; removed, along with the
  second colour scheme.
- Notification had no actions. It now offers Stop recording / Pause / Resume.
- Fixed the state machine: `OFF`, `LISTENING`, `RECORDING`, `NEEDS_ACCESS`, `ERROR`. The UI never
  claims "recording" unless the recorder is actually running.

### 2.6 Export and sharing

- Exports went to `Music/SpotifyAds` with `.mp3` names for WAV data, wrote a sidecar JSON into
  app-private storage (invisible to the user), and left `IS_PENDING` set in some paths.
- Rewritten: `Music/SpotAd`, correct MIME, pending flag cleared, rollback on failure, MediaStore
  on Android 10+, legacy path with the runtime permission on Android 9 and below.
- Share uses `ClipData` so receiving apps actually get read permission.

### 2.7 Player

- No audio focus: SpotAd played over Spotify.
- No headphone-unplug handling: audio continued on the speaker.
- `MediaPlayer` errors were swallowed; pressing play on a deleted file did nothing.
- Added audio focus, becoming-noisy handling, error surfacing through the UI, and correct
  speed/seek behaviour.

### 2.8 Build, manifest and privacy

- The app shipped Firebase AI, App Check, Retrofit, OkHttp and Moshi — none were used, but they
  added size, network permissions and privacy-review questions. Removed, along with the
  `google-services` and `secrets` plugins and the unused `.env.example`.
- `applicationId` changed from `com.aistudio.spotad.rxnpld` to `com.spotad.recorder`; version is
  `1.0.0` (`versionCode 6`). **This requires a new Play listing** (the old package cannot be
  migrated).
- Release builds now enable R8 + resource shrinking with Room keep rules.
- `allowBackup` is off; only the settings file is transferred by Android's device-transfer rules.
- The manifest no longer declares `FOREGROUND_SERVICE_MICROPHONE`; the app never records the mic.
- FileProvider now only exposes the recordings directory.

---

## 3. UI: removing the "AI studio" look

Removed:

- Emoji in the interface and in notification titles (`🔴 Recording…`, `🎵 …`, `🗣 …`).
- Custom colours lifted from another brand (Spotify green) and glow/pulse animations
  (`rememberInfiniteTransition` and friends).
- Marketing copy such as "Studio", "Captured Ads Library", "vibrant Orange theme",
  "100 % precision", "0 % CPU".
- Decorative gradients, oversized corner radii, and space-wasting "hero" cards.
- Fabricated labels on recordings (see 2.3).

Replaced with:

- One neutral palette (ink/graphite) and **one accent**; a warm red reserved for the record state.
  Full light and dark themes, plus a System option.
- The platform font with a tightened 4sp scale, sentence case, no wide tracking.
- 12 dp cards, 8 dp controls, static hairlines. Waveforms are drawn from the actual amplitude
  data (empty recordings show nothing rather than a fake pattern).
- A status-first information hierarchy: monitoring card → what Spotify is doing → library.
- Permission state is shown as a checklist that reflects the real Android settings, with one
  action per row — instead of a fake multi-step wizard.
- Accessibility: touch targets ≥ 48 dp, content descriptions on icon-only buttons, no
  colour-only status (dot + text), scrollable rows for long tags.
- Snackbars instead of toasts, and no blocking `Toast` loops on recomposition.

The app name is now `SpotAd` (was "SpotAd Recorder"); the launcher icon was redrawn (ring + dot +
level ticks) in all mipmap densities and as an adaptive/monochrome icon.

---

## 4. Platform realities you must accept before shipping

These are not bugs; they are constraints that shape the product and the store listing.

1. **Android does not allow silent system-audio capture.** Since Android 10, capturing another
   app's audio requires the MediaProjection consent dialog, and the system shows a persistent
   "casting/recording" indicator. There is no way around it. SpotAd therefore asks once and keeps
   the projection alive while monitoring; if the user revokes it, capture stops.
2. **Spotify does not publish an "ad" API.** Detection depends on Spotify putting an ad label in
   the media session or notification. When it does not, no automatic recording happens — so a
   manual **Record now** button was added and is the honest fallback.
3. **Some apps opt out of playback capture.** If Spotify (or an OEM build) sets
   `ALLOW_CAPTURE_BY_NONE`, the capture file will be silent. SpotAd detects that and explains it
   rather than saving an empty clip.
4. **Aggressive battery managers** (Xiaomi, Oppo, Vivo, Samsung) kill foreground services. The
   service is `START_STICKY`, but on those devices the user must allow autostart/background
   activity in the OEM settings. Add a help link in the store listing; don't promise 24/7
   reliability.
5. **Foreground service type.** Monitoring while idle uses `dataSync`. Play review may ask why;
   the answer is "user-initiated background monitoring of Spotify metadata". Recording time uses
   `mediaProjection` as required.
6. **Copyright / Play policy.** The app records third-party audio for personal use. Keep the
   in-app disclaimer, do not add upload/sharing features, and describe it accurately in the Data
   Safety form ("Audio files: collected? No — stored on device only").

---

## 5. What still needs a real device (cannot be verified here)

This sandbox has no Android SDK, so the project could not be compiled or run. Before release:

- [ ] `./gradlew assembleDebug` and `bundleRelease` with the upload keystore.
- [ ] Fresh install on Android 11, 13 and 14+ (capture and FGS rules differ).
- [ ] Start monitoring → grant capture → play a Spotify ad → confirm the WAV plays, the library
      row matches the notification, and the saved notification appears.
- [ ] Revoke capture from the system cast chip while recording → confirm the clip is finalised and
      the UI asks for access again.
- [ ] Lock the screen during an ad; confirm capture continues (test on Samsung/Xiaomi).
- [ ] Rotate, background-kill, and reopen the app; confirm state restoration.
- [ ] Enable "Don't keep activities"; confirm the projection prompt returns cleanly.
- [ ] Export on Android 9 (permission path) and Android 14 (MediaStore path).
- [ ] Run the unit tests (`ExampleRobolectricTest`) and fix any environment-specific failures.
- [ ] TalkBack pass, font scale 200 %, and RTL smoke test.
- [ ] Confirm `./gradlew lint` is clean, and add a Play Data Safety declaration.

Migration check: install the old build, record something, install the new build, confirm the
library survives (DB version 1 → 2 migration). Old rows have no waveform/`sampleRate`; they will
show "No waveform data".

---

## 6. Suggested features, in order of value

### Already added in this change (useful, cheap, low risk)

- **Manual "Record now"** — guarantees the app is useful even when detection fails.
- **Starred recordings** exempt from automatic cleanup.
- **Retention setting** (forever / 7 / 30 / 90 days) and storage usage readout.
- **Maximum clip length** setting (1/2/5 min).
- **Light / dark / system theme**.
- **Edit metadata + notes**, copy details to clipboard.
- **Export to Music/SpotAd** and system share with `ClipData`.
- **Trim to recording** (implicit: capture stops at ad end).

### Tier 1 — next release (high value, moderate effort)

1. **Trim / crop without re-encoding** — WAV makes lossless trimming trivial: pick start/end,
   rewrite the header. Let users cut the tail of a clip that caught the next song.
2. **Convert to MP3/AAC** — after capture, encode with `MediaCodec` (AAC in an `.m4a`) so files
   are ~10× smaller and shareable; optional "keep original" setting. This matters before users
   collect hundreds of clips.
3. **Merge consecutive ad pods** — Spotify often plays 2–3 ads back to back; offer "combine into
   one break" with chapter timestamps.
4. **Duplicate detection** — a perceptual hash (chromaprint-style) or a simple audio fingerprint
   would let the library collapse the same ad heard twenty times, with a "heard 20×" counter.
5. **Search across transcripts** — optional on-device speech recognition (`SpeechRecognizer` with
   `EXTRA_PREFER_OFFLINE` on API 31+) over the saved file, user-initiated only, with a clear
   privacy note. If added, never fabricate a transcript when it fails.
6. **Home screen widget / quick settings tile** to start or stop a capture.

### Tier 2 — differentiation

7. **Advertiser analytics (local only)** — top advertisers, ads per hour, longest/shortest,
   weekday patterns. Pure SQL over the existing table, no upload.
8. **RSS-style export / local web share** — export a folder with JSON + WAVs so a user can drop
   it into their own archive.
9. **Automatic gain normalisation** — ads are mastered loud; offer a per-clip normalise on export.
10. **Notes and quotes** — timestamped notes on the waveform ("this punchline at 0:12").
11. **Backup/restore** of the library to a user-chosen folder (SAF), including the database.
12. **Wear/TV companion** for re-listening — likely overkill; skip unless requested.

### Explicitly not recommended

- Cloud accounts or social feeds: recording third-party ads and publishing them invites takedowns.
- Background speech-to-text on all audio: battery drain and privacy risk with little benefit.
- "100 % detection" claims: the metadata is outside our control.

---

## 7. File map after the rebuild

```
app/src/main/java/com/example/
├── MainActivity.kt                 single screen, permission flow, MediaProjection launcher
├── SpotAdApplication.kt
├── audio/
│   ├── AudioRecorderManager.kt     playback capture → streaming WAV, honest failures
│   ├── AudioPlayerManager.kt       focus-aware player
│   ├── AudioFingerprintEngine.kt   weighted ad-label classifier
│   ├── SpeechKeywordExtractor.kt   dictionary tags, no invented transcripts
│   └── ExportManager.kt            MediaStore export, share, formatting
├── data/
│   ├── RecordedAdEntity.kt         facts + computed helpers
│   ├── RecordedAdDao.kt            focused queries
│   ├── AppDatabase.kt              v2 with a real migration
│   ├── RecordedAdRepository.kt     row + file consistency
│   └── AppSettings.kt              SettingsStore + ThemeMode
├── service/
│   ├── AdRecordingService.kt       foreground service + state machine
│   ├── SpotifyNotificationListener.kt  media-session-first detection
│   └── SpotifyAdEvent.kt           NowPlaying / SpotifyAdEvent / ContentKind
└── ui/
    ├── theme/                      palette, type scale, shapes
    ├── components/                 library UI (no emoji, no gradients)
    └── viewmodel/MainViewModel.kt  single UI state, settings, cleanup
```
