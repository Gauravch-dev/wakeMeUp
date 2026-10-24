package com.laststop.app

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/** Small synchronous writes only at start, alert transitions and stop, not on every fix. */
class JourneyRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun load(): SavedJourney? = SavedJourney.fromFields(preferences.all.entries
        .filter { it.key.startsWith(PREFIX) && it.value is String }
        .associate { it.key.removePrefix(PREFIX) to it.value as String })

    @SuppressLint("ApplySharedPref", "UseKtx") // Need commit's Boolean result; the KTX helper returns Unit.
    fun save(journey: SavedJourney): Boolean {
        val editor = preferences.edit().putBoolean("unfinished", true)
        journey.fields().forEach { (key, value) -> editor.putString(PREFIX + key, value) }
        return editor.commit()
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    fun clear(): Boolean {
        val editor = preferences.edit().putBoolean("unfinished", false)
        preferences.all.keys.filter { it.startsWith(PREFIX) }.forEach(editor::remove)
        return editor.commit()
    }

    companion object {
        private const val PREFIX = "active_journey_"
        fun bootCount(context: Context): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
}
