package com.smartcloset.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.closetGoalsDataStore by preferencesDataStore(name = "closet_goals")

class ClosetGoalRepository(private val context: Context) {
    val weatherSettings: Flow<WeatherSettings> = context.closetGoalsDataStore.data.map { p ->
        val name = p[stringPreferencesKey("weather_location_name")]
        WeatherSettings(
            location = if (name == null) null else WeatherLocation(name, p[doublePreferencesKey("weather_lat")] ?: 0.0, p[doublePreferencesKey("weather_lon")] ?: 0.0, p[stringPreferencesKey("weather_timezone")] ?: "auto"),
            manualMode = p[intPreferencesKey("weather_manual_mode")] == 1,
            manualTemperature = p[stringPreferencesKey("weather_manual_temp")]?.toDoubleOrNull(),
            manualSeason = p[stringPreferencesKey("weather_manual_season")],
            cachedWeather = if (p[stringPreferencesKey("weather_cache_condition")] == null) null else WeatherSnapshot(p[stringPreferencesKey("weather_cache_temp")]?.toDoubleOrNull() ?: 0.0, p[intPreferencesKey("weather_cache_humidity")] ?: 0, p[stringPreferencesKey("weather_cache_condition")]!!, p[longPreferencesKey("weather_cache_time")] ?: 0L)
        )
    }
    val reminderSettings: Flow<OutfitReminderSettings> = context.closetGoalsDataStore.data.map { p -> OutfitReminderSettings(p[intPreferencesKey("outfit_reminder_enabled")] == 1, p[intPreferencesKey("outfit_reminder_hour")] ?: 8, p[intPreferencesKey("outfit_reminder_minute")] ?: 0) }

    suspend fun setReminderSettings(settings: OutfitReminderSettings) {
        require(settings.hour in 0..23 && settings.minute in 0..59)
        context.closetGoalsDataStore.edit { p ->
            p[intPreferencesKey("outfit_reminder_enabled")] = if (settings.enabled) 1 else 0
            p[intPreferencesKey("outfit_reminder_hour")] = settings.hour
            p[intPreferencesKey("outfit_reminder_minute")] = settings.minute
        }
    }

    suspend fun saveWeatherLocation(location: WeatherLocation?) {
        context.closetGoalsDataStore.edit { p ->
            val name = stringPreferencesKey("weather_location_name")
            val previousLat = p[doublePreferencesKey("weather_lat")]
            val previousLon = p[doublePreferencesKey("weather_lon")]
            if (location == null || previousLat != location.latitude || previousLon != location.longitude) {
                p.remove(stringPreferencesKey("weather_cache_temp")); p.remove(intPreferencesKey("weather_cache_humidity"));
                p.remove(stringPreferencesKey("weather_cache_condition")); p.remove(longPreferencesKey("weather_cache_time"))
            }
            if (location == null) {
                p.remove(name); p.remove(doublePreferencesKey("weather_lat")); p.remove(doublePreferencesKey("weather_lon")); p.remove(stringPreferencesKey("weather_timezone"))
            }
            else { p[name] = location.name; p[doublePreferencesKey("weather_lat")] = location.latitude; p[doublePreferencesKey("weather_lon")] = location.longitude; p[stringPreferencesKey("weather_timezone")] = location.timezone }
        }
    }

    suspend fun setManualWeather(enabled: Boolean, temperature: Double?, season: String?) {
        require(temperature == null || temperature in -80.0..60.0)
        context.closetGoalsDataStore.edit { p ->
            p[intPreferencesKey("weather_manual_mode")] = if (enabled) 1 else 0
            val tempKey = stringPreferencesKey("weather_manual_temp")
            val seasonKey = stringPreferencesKey("weather_manual_season")
            if (temperature == null) p.remove(tempKey) else p[tempKey] = temperature.toString()
            if (season == null) p.remove(seasonKey) else p[seasonKey] = season
        }
    }

    suspend fun saveWeatherSnapshot(snapshot: WeatherSnapshot) {
        context.closetGoalsDataStore.edit { p ->
            p[stringPreferencesKey("weather_cache_temp")] = snapshot.temperatureC.toString()
            p[intPreferencesKey("weather_cache_humidity")] = snapshot.humidityPercent
            p[stringPreferencesKey("weather_cache_condition")] = snapshot.condition
            p[longPreferencesKey("weather_cache_time")] = snapshot.updatedAt
        }
    }
    val goals: Flow<Map<ClosetTargetKey, Int>> = context.closetGoalsDataStore.data.map { preferences ->
        buildMap {
            ClosetStatistics.seasons.forEach { season -> ClosetStatistics.categories.forEach { category ->
                val key = ClosetTargetKey(season, category)
                preferences[intPreferencesKey(storageKey(key))]?.let { put(key, it) }
            } }
        }
    }

    val wearAnalysisSettings: Flow<WearAnalysisSettings> = context.closetGoalsDataStore.data.map { preferences ->
        WearAnalysisSettings(
            recentDays = preferences[intPreferencesKey("wear_recent_days")] ?: 30,
            noWearDays = preferences[intPreferencesKey("wear_inactive_days")] ?: 60,
            noWearAfterRegistrationDays = preferences[intPreferencesKey("wear_registration_grace_days")] ?: 30
        )
    }

    suspend fun setGoal(key: ClosetTargetKey, count: Int?) {
        require(count == null || count >= 0)
        context.closetGoalsDataStore.edit { preferences ->
            val preferenceKey = intPreferencesKey(storageKey(key))
            if (count == null) preferences.remove(preferenceKey) else preferences[preferenceKey] = count
        }
    }

    suspend fun replaceGoals(goals: Map<ClosetTargetKey, Int?>) {
        require(goals.values.all { it == null || it >= 0 })
        context.closetGoalsDataStore.edit { preferences ->
            goals.forEach { (key, count) ->
                val preferenceKey = intPreferencesKey(storageKey(key))
                if (count == null) preferences.remove(preferenceKey) else preferences[preferenceKey] = count
            }
        }
    }

    suspend fun setWearAnalysisSettings(settings: WearAnalysisSettings) {
        require(settings.recentDays > 0 && settings.noWearDays > 0 && settings.noWearAfterRegistrationDays > 0)
        context.closetGoalsDataStore.edit { preferences ->
            preferences[intPreferencesKey("wear_recent_days")] = settings.recentDays
            preferences[intPreferencesKey("wear_inactive_days")] = settings.noWearDays
            preferences[intPreferencesKey("wear_registration_grace_days")] = settings.noWearAfterRegistrationDays
        }
    }

    private fun storageKey(key: ClosetTargetKey) = "goal_${key.season}_${key.category}"
}
