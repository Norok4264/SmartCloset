package com.smartcloset.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double, val timezone: String)
data class WeatherSnapshot(val temperatureC: Double, val humidityPercent: Int, val condition: String, val updatedAt: Long)
data class WeatherSettings(val location: WeatherLocation? = null, val manualMode: Boolean = false, val manualTemperature: Double? = null, val manualSeason: String? = null, val cachedWeather: WeatherSnapshot? = null)
data class OutfitReminderSettings(val enabled: Boolean = false, val hour: Int = 8, val minute: Int = 0)
sealed interface WeatherResult {
    data class Fresh(val snapshot: WeatherSnapshot) : WeatherResult
    data class Cached(val snapshot: WeatherSnapshot, val errorMessage: String? = null) : WeatherResult
    data class Failed(val message: String) : WeatherResult
}

interface WeatherRepository {
    suspend fun searchLocations(query: String): List<WeatherLocation>
    suspend fun current(location: WeatherLocation): WeatherSnapshot
}

/** Open-Meteo provider: public, unauthenticated GET API for non-commercial use. */
class OpenMeteoWeatherRepository : WeatherRepository {
    override suspend fun searchLocations(query: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val json = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=ko&format=json")
        val results = json.optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val city = item.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val admin = item.optString("admin1", "").takeIf { it.isNotBlank() }
            val country = item.optString("country", "")
            val label = listOf(city, admin, country).filterNotNull().distinct().joinToString(", ")
            WeatherLocation(label, item.getDouble("latitude"), item.getDouble("longitude"), item.optString("timezone", "auto"))
        }
    }

    override suspend fun current(location: WeatherLocation): WeatherSnapshot = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,relative_humidity_2m,weather_code&timezone=auto"
        val current = getJson(url).getJSONObject("current")
        WeatherSnapshot(current.getDouble("temperature_2m"), current.getInt("relative_humidity_2m"), condition(current.getInt("weather_code")), System.currentTimeMillis())
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode !in 200..299) error("날씨 서버 응답 오류 (${connection.responseCode})")
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun condition(code: Int): String = when (code) {
        0 -> "맑음"
        1, 2 -> "대체로 맑음"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        in 61..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        in 85..86 -> "눈 소나기"
        in 95..99 -> "뇌우"
        else -> "날씨 상태 정보 없음"
    }
}
