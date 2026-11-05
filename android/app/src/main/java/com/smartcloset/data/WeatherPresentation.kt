package com.smartcloset.data

enum class WeatherDisplayMode { FRESH, CACHED, FAILED, LOCATION_UNSET, MANUAL, LOADING }

data class WeatherPresentation(
    val mode: WeatherDisplayMode,
    val snapshot: WeatherSnapshot? = null,
    val message: String? = null
)

/** Resolves UI state without inventing weather values. */
fun resolveWeatherPresentation(settings: WeatherSettings, result: WeatherResult?): WeatherPresentation = when {
    settings.manualMode -> WeatherPresentation(WeatherDisplayMode.MANUAL)
    settings.location == null -> WeatherPresentation(WeatherDisplayMode.LOCATION_UNSET)
    result is WeatherResult.Fresh -> WeatherPresentation(WeatherDisplayMode.FRESH, result.snapshot)
    result is WeatherResult.Cached -> WeatherPresentation(WeatherDisplayMode.CACHED, result.snapshot, result.errorMessage)
    result is WeatherResult.Failed && settings.cachedWeather != null -> WeatherPresentation(WeatherDisplayMode.CACHED, settings.cachedWeather, result.message)
    result is WeatherResult.Failed -> WeatherPresentation(WeatherDisplayMode.FAILED, message = result.message)
    settings.cachedWeather != null -> WeatherPresentation(WeatherDisplayMode.CACHED, settings.cachedWeather)
    else -> WeatherPresentation(WeatherDisplayMode.LOADING)
}
