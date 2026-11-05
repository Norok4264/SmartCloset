package com.smartcloset

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartcloset.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.time.LocalDate
import kotlin.random.Random

data class ClosetFilters(val query: String = "", val category: String = "", val season: String = "", val color: String = "")

class ClosetViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ClothingRepository(app)
    private val goalRepository = ClosetGoalRepository(app)
    private val wearRepository = WearHistoryRepository(app)
    private val outfitRepository = OutfitRepository(app)
    private val weatherPreferences = goalRepository
    private val weatherRepository: WeatherRepository = OpenMeteoWeatherRepository()
    private val clock = Clock.systemDefaultZone()
    init { viewModelScope.launch { runCatching { repository.cleanupOrphanPhotos() } } }
    val filters = MutableStateFlow(ClosetFilters())
    val query = filters.map { it.query }
    val items = filters.flatMapLatest { repository.observe(it.query, it.category, it.season, it.color) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repository.observe(favoritesOnly = true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val count = repository.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val statistics = combine(repository.observeAll(), goalRepository.goals) { clothes, goals ->
        ClosetStatistics.calculate(clothes, goals)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClosetStatistics.calculate(emptyList()))
    val wearAnalytics = combine(repository.observeAll(), wearRepository.observeAll(), goalRepository.wearAnalysisSettings) { clothes, records, settings ->
        WearAnalytics.calculate(clothes, records, settings, LocalDate.now(clock), clock.zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearAnalytics.calculate(emptyList(), emptyList(), WearAnalysisSettings(), LocalDate.now(clock), clock.zone))
    val wearAnalysisSettings = goalRepository.wearAnalysisSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearAnalysisSettings())
    val allClothes = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val wearRecords = wearRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val savedOutfits = outfitRepository.outfits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favoriteOutfits = outfitRepository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val weatherSettings = weatherPreferences.weatherSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeatherSettings())
    val reminderSettings = weatherPreferences.reminderSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OutfitReminderSettings())
    val weatherResult = MutableStateFlow<WeatherResult?>(null)
    fun setQuery(value: String) { filters.value = filters.value.copy(query = value) }
    fun setCategory(value: String) { filters.value = filters.value.copy(category = value) }
    fun setSeason(value: String) { filters.value = filters.value.copy(season = value) }
    fun setColor(value: String) { filters.value = filters.value.copy(color = value) }
    fun observe(id: Long) = repository.observeById(id)
    fun save(source: File?, existing: Clothing?, clothing: Clothing, onDone: (Result<Long>) -> Unit) = viewModelScope.launch {
        runCatching { repository.save(source, existing, clothing) }.also(onDone)
    }
    fun delete(item: Clothing, onDone: () -> Unit) = viewModelScope.launch { runCatching { repository.delete(item) }; onDone() }
    fun toggleFavorite(item: Clothing) = viewModelScope.launch { repository.save(null, item, item.copy(favorite = !item.favorite)) }
    fun setGoal(key: ClosetTargetKey, count: Int?) = viewModelScope.launch { goalRepository.setGoal(key, count) }
    fun setGoals(goals: Map<ClosetTargetKey, Int?>) = viewModelScope.launch { goalRepository.replaceGoals(goals) }
    fun setWearAnalysisSettings(settings: WearAnalysisSettings) = viewModelScope.launch { goalRepository.setWearAnalysisSettings(settings) }
    fun observeWearHistory(clothingId: Long) = wearRepository.observeForClothing(clothingId)
    fun observeWearSummary(clothingId: Long) = wearRepository.observeSummary(clothingId)
    fun addWear(clothingId: Long, date: LocalDate, confirmBeforePurchase: Boolean = false, onDone: (Result<WearSaveResult>) -> Unit) = viewModelScope.launch {
        runCatching { wearRepository.add(clothingId, date, confirmBeforePurchase) }.also(onDone)
    }
    fun updateWearDate(recordId: Long, date: LocalDate, confirmBeforePurchase: Boolean = false, onDone: (Result<WearSaveResult>) -> Unit) = viewModelScope.launch {
        runCatching { wearRepository.updateDate(recordId, date, confirmBeforePurchase) }.also(onDone)
    }
    fun deleteWear(recordId: Long, onDone: (Result<Unit>) -> Unit) = viewModelScope.launch {
        runCatching { wearRepository.delete(recordId) }.also(onDone)
    }
    fun recordClothesForDay(clothingIds: List<Long>, date: LocalDate, confirmBeforePurchase: Boolean = false, onDone: (Result<WearBatchResult>) -> Unit) = viewModelScope.launch {
        runCatching { wearRepository.addMany(clothingIds, date, confirmBeforePurchase) }.also(onDone)
    }
    fun searchWeatherLocations(query: String, onDone: (Result<List<WeatherLocation>>) -> Unit) = viewModelScope.launch { runCatching { weatherRepository.searchLocations(query) }.also(onDone) }
    fun setWeatherLocation(location: WeatherLocation?) = viewModelScope.launch {
        weatherPreferences.saveWeatherLocation(location)
        if (location != null) weatherPreferences.setManualWeather(false, null, null)
    }
    fun setManualWeather(enabled: Boolean, temperature: Double?, season: String?) = viewModelScope.launch { weatherPreferences.setManualWeather(enabled, temperature, season) }
    fun refreshWeather() = viewModelScope.launch {
        val settings = weatherSettings.value
        val location = settings.location
        if (location == null) { weatherResult.value = WeatherResult.Failed("지역을 설정하면 실제 날씨를 조회할 수 있습니다."); return@launch }
        weatherResult.value = try {
            val fresh = weatherRepository.current(location)
            weatherPreferences.saveWeatherSnapshot(fresh)
            WeatherResult.Fresh(fresh)
        } catch (error: Exception) {
            settings.cachedWeather?.let { WeatherResult.Cached(it, error.message ?: "날씨를 불러오지 못했습니다.") } ?: WeatherResult.Failed(error.message ?: "날씨를 불러오지 못했습니다.")
        }
    }
    fun saveOutfit(name: String, topId: Long, bottomId: Long, outerId: Long?, favorite: Boolean, onDone: (Result<SaveOutfitResult>) -> Unit) = viewModelScope.launch {
        runCatching { outfitRepository.save(name, topId, bottomId, outerId, favorite) }.also(onDone)
    }
    fun toggleOutfitFavorite(outfit: Outfit) = viewModelScope.launch { outfitRepository.toggleFavorite(outfit) }
    fun deleteOutfit(outfit: Outfit) = viewModelScope.launch { outfitRepository.delete(outfit.id) }
    fun setReminderSettings(settings: OutfitReminderSettings) = viewModelScope.launch { weatherPreferences.setReminderSettings(settings) }
    fun savedOutfit(id: Long) = outfitRepository.observe(id)
}
