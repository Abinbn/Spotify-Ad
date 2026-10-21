package com.spotad.recorder

import android.app.Application
import com.spotad.recorder.data.SettingsStore

/**
 * Keeps start-up work explicit and cheap. Recordings, settings and the database all live in
 * app-private storage; nothing is uploaded anywhere.
 */
class SpotAdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Touch the settings store so the first UI frame reads the real values.
        SettingsStore.get(this)
    }
}
