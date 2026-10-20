package com.laststop.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.io.IOException

private val Context.preferences by preferencesDataStore("laststop_settings")
data class Settings(val sourceId: String = "PNVL", val destinationId: String = "VSH",
    val soundUri: String = "", val soundName: String = "Phone default alarm", val planId: String = "",
    val darkTheme: Boolean? = null)

object SettingsMigration {
    private val oldDestinations = listOf("PNVL", "KNDS", "MANR", "KHAG", "BEPR", "SWDV", "NEU", "JNJ", "SNCR", "VSH")
    fun destination(oldIndex: Int?) = oldDestinations.getOrNull(oldIndex ?: 9)?.takeUnless { it == "PNVL" } ?: "VSH"
}

class JourneyPreferences(private val context: Context) {
    private val sourceKey = stringPreferencesKey("source_id")
    private val destinationKey = stringPreferencesKey("destination_id")
    private val oldDestinationKey = intPreferencesKey("destination")
    private val radiusKey = intPreferencesKey("radius")
    private val uriKey = stringPreferencesKey("sound_uri")
    private val nameKey = stringPreferencesKey("sound_name")
    private val planKey = stringPreferencesKey("plan_id")
    private val themeKey = booleanPreferencesKey("dark_theme")
    val settings = context.preferences.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map {
        Settings(sourceId = it[sourceKey]?.takeIf(MumbaiNetwork.byId::containsKey) ?: "PNVL",
            destinationId = it[destinationKey]?.takeIf(MumbaiNetwork.byId::containsKey)
                ?: SettingsMigration.destination(it[oldDestinationKey]),
            soundUri = it[uriKey].orEmpty(), soundName = it[nameKey] ?: "Phone default alarm",
            planId = it[planKey].orEmpty(), darkTheme = it[themeKey])
    }
    suspend fun saveJourney(source: String, destination: String, planId: String) {
        context.preferences.edit {
            it[sourceKey] = source; it[destinationKey] = destination
            it.remove(radiusKey) // Retire old distance preferences when upgrading to station-based alarms.
            it[planKey] = planId
        }
    }
    suspend fun saveSound(uri: String, name: String) {
        context.preferences.edit { it[uriKey] = uri; it[nameKey] = name }
    }
    suspend fun saveTheme(dark: Boolean) {
        context.preferences.edit { it[themeKey] = dark }
    }
}
