package com.smartcloset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherPresentationTest {
    private val location = WeatherLocation("서울", 37.5, 127.0, "Asia/Seoul")
    private val snapshot = WeatherSnapshot(19.0, 55, "흐림", 123L)

    @Test fun freshCacheFailedUnsetAndManualStatesAreDistinct() {
        assertEquals(WeatherDisplayMode.FRESH, resolveWeatherPresentation(WeatherSettings(location), WeatherResult.Fresh(snapshot)).mode)
        assertEquals(WeatherDisplayMode.CACHED, resolveWeatherPresentation(WeatherSettings(location, cachedWeather = snapshot), null).mode)
        val failed = resolveWeatherPresentation(WeatherSettings(location, cachedWeather = snapshot), WeatherResult.Failed("offline"))
        assertEquals(WeatherDisplayMode.CACHED, failed.mode)
        assertEquals("offline", failed.message)
        assertEquals(WeatherDisplayMode.FAILED, resolveWeatherPresentation(WeatherSettings(location), WeatherResult.Failed("offline")).mode)
        assertEquals(WeatherDisplayMode.LOCATION_UNSET, resolveWeatherPresentation(WeatherSettings(), null).mode)
        val manual = resolveWeatherPresentation(WeatherSettings(manualMode = true, manualTemperature = 20.0, manualSeason = "봄"), WeatherResult.Fresh(snapshot))
        assertEquals(WeatherDisplayMode.MANUAL, manual.mode)
        assertNull(manual.snapshot)
    }
}
