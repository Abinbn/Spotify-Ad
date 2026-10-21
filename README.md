# SpotAd

SpotAd records the Spotify ad breaks you want to keep. It watches what Spotify is playing, saves
the audio of an ad while it airs, and keeps the clip in an on-device library you can replay,
rename, star and export.

- No account, no cloud, no analytics. Recordings stay in app-private storage.
- Real WAV audio (not re-encoded, not synthesised).
- Lossless trim: cut the start or end of a clip by rewriting the WAV header — no re-encoding, and
  the original is only replaced once the trimmed copy is safely written.
- Material 3 UI with system light/dark themes and the platform font.
- Works on Android 7.0+ (internal audio capture needs Android 10+).

---

## How it works

1. `SpotifyNotificationListener` (a notification-listener service) reads Spotify's media session.
2. `AudioFingerprintEngine` decides whether the current item is an ad. It relies on Spotify's own
   ad labels (`spotify:ad:` URI, "Advertisement", "Ad break", "Sponsored", …). A short duration
   alone is never enough.
3. When an ad starts, `AdRecordingService` (foreground, `mediaProjection|dataSync`) opens a
   playback capture with the consent the user granted earlier.
4. `AudioRecorderManager` streams PCM into a WAV file on disk, measuring real levels for the
   waveform, and stops when music returns or at the configured maximum length.
5. Silent or sub-second captures are discarded. Everything else is stored in Room with facts:
   duration from the file, sample rate, source, and the detector's confidence.

## First run

1. Install and open the app.
2. **Start listening.** Android asks for the microphone permission (required for playback capture)
   and the notification permission (for the ongoing recorder notification).
3. **Audio capture access.** Accept the system screen-capture dialog. Android only allows app
   audio capture through that consent; nothing is recorded until an actual ad is detected.
4. **Notification access.** Grant it from the setup card so SpotAd can see what Spotify is playing.
5. Play Spotify. When an ad break begins, the notification switches to "Recording ad…", and the
   clip appears in the library when the music resumes.

`Record now` captures the next two minutes manually — useful when Spotify does not label an ad.

Open any recording to rename it, add notes, **trim** it (drag a start/end selection; the WAV is cut
losslessly), share it, export it to `Music/SpotAd`, or delete it.

## Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew test                   # JVM + Robolectric tests
./gradlew bundleRelease          # Play bundle (needs KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD)
```

The release build uses R8 + resource shrinking. The Play upload key is read from the environment;
`*.jks` and `*.keystore` are ignored by git.

## Project layout

```
app/src/main/java/com/spotad/recorder/
├── MainActivity.kt          single screen + permission flow
├── SpotAdApplication.kt
├── audio/                   capture, detection, playback, export
├── data/                    Room entity/DAO/database, settings
├── service/                 foreground recorder + Spotify listener
└── ui/                      theme, components, ViewModel
```

See [REVIEW.md](REVIEW.md) for the audit that produced this build, the remaining device-test
checklist, and the feature roadmap.

## Privacy

Audio is written to `files/ads` inside the app sandbox. It is never uploaded. Deleting a recording
removes the file and its row; “Delete all” removes the folder contents. Android's cloud backup is
disabled; only the settings file can be transferred to a new device.

## Limitations (by platform design)

- Capturing another app's audio requires the system screen-capture consent and shows Android's
  recording indicator.
- Some apps and some OEM builds block playback capture; SpotAd reports a silent capture instead of
  saving it.
- Aggressive battery managers may stop background services; allow SpotAd to run in the background.
- Android 15 and newer cap how long a `dataSync` foreground service may run (about six hours a
  day). SpotAd pauses cleanly and asks you to reopen it instead of failing silently.
- SpotAd records third-party audio for personal use. Respect copyright and the rights of the
  advertisers whose work you keep.
