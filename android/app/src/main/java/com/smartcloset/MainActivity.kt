package com.smartcloset

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.smartcloset.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SmartClosetTheme { ClosetApp() } } }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "홈", Icons.Default.Home), Closet("closet", "내 옷장", Icons.Default.Checkroom),
    Analysis("analysis", "옷장 분석", Icons.Default.BarChart), Favorites("favorites", "즐겨찾기", Icons.Default.Favorite), Settings("settings", "설정", Icons.Default.Settings)
}

@Composable
private fun ClosetApp(vm: ClosetViewModel = viewModel()) {
    val nav = rememberNavController()
    val route by nav.currentBackStackEntryAsState()
    val current = route?.destination?.route
    Scaffold(bottomBar = {
        NavigationBar { Tab.entries.forEach { tab -> NavigationBarItem(current == tab.route, onClick = {
            nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
        }, icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) }) } }
    }) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, onCloset = { nav.navigate("closet") }, onOutfits = { nav.navigate("outfits") }, onRegister = { nav.navigate("edit/0") }, onTryOn = { id -> nav.navigate("try-on${id?.let { "?topId=$it" }.orEmpty()}") }) }
            composable("closet") { ClosetScreen(vm, false, onAdd = { nav.navigate("edit/0") }, onItem = { nav.navigate("detail/$it") }) }
            composable("favorites") { FavoritesScreen(vm, onClothing = { nav.navigate("detail/$it") }, onOutfit = { nav.navigate("outfit-detail/$it") }) }
            composable("analysis") { AnalysisScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                DetailScreen(vm, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/${entry.arguments!!.getLong("id")}") })
            }
            composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                EditScreen(vm, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
            }
            composable("wear-history") { Placeholder("착용 기록", "착용 기록은 각 의류의 상세 화면에서 관리합니다.") }
            composable("outfits") { OutfitScreen(vm, onClothing = { nav.navigate("detail/$it") }, onRegister = { nav.navigate("edit/0") }) }
            composable("outfit-detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry -> SavedOutfitDetailScreen(vm, entry.arguments!!.getLong("id"), onClothing = { nav.navigate("detail/$it") }) }
            composable("try-on?topId={topId}", arguments = listOf(navArgument("topId") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry -> TryOnScreen(vm, entry.arguments?.getString("topId")?.toLongOrNull(), onOpenClothing = { nav.navigate("detail/$it") }) }
        }
    }
}

@Composable private fun HomeScreen(vm: ClosetViewModel, onCloset: () -> Unit, onOutfits: () -> Unit, onRegister: () -> Unit, onTryOn: (Long?) -> Unit) {
    val clothes by vm.allClothes.collectAsStateWithLifecycle()
    val homeWearRecords by vm.wearRecords.collectAsStateWithLifecycle()
    val weather by vm.weatherSettings.collectAsStateWithLifecycle()
    val result by vm.weatherResult.collectAsStateWithLifecycle()
    val saved by vm.savedOutfits.collectAsStateWithLifecycle()
    LaunchedEffect(weather.location, weather.manualMode) { if (weather.location != null && !weather.manualMode && result == null) vm.refreshWeather() }
    var showWeather by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var saveHomeOutfit by remember { mutableStateOf(false) }
    var homeOutfitName by remember { mutableStateOf("") }
    var homeOutfitFavorite by remember { mutableStateOf(false) }
    var pendingHomeWear by remember { mutableStateOf<List<Long>?>(null) }
    var homeWearMessage by remember { mutableStateOf<String?>(null) }
    if (showWeather) WeatherLocationDialog(vm, onDismiss = { showWeather = false })
    if (showManual) ManualWeatherDialog(weather, onDismiss = { showManual = false }, onSave = { temp, season -> vm.setManualWeather(true, temp, season); showManual = false })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("스마트 옷장", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        WeatherCard(weather, result, onRefresh = vm::refreshWeather, onRegion = { showWeather = true }, onManual = { showManual = true })
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("오늘의 코디", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val suggestion = remember(clothes, weather, result, homeWearRecords) {
                    val temperature = (result as? WeatherResult.Fresh)?.snapshot?.temperatureC ?: weather.cachedWeather?.temperatureC
                    val season = if (weather.manualMode) weather.manualSeason ?: weather.manualTemperature?.let { OutfitRecommendationEngine().seasonForTemperature(it) }
                    else temperature?.let { OutfitRecommendationEngine().seasonForTemperature(it) }
                    val recentDays = RecommendationRules().recentWearWindowDays.toLong()
                    val recentIds = homeWearRecords.groupBy { it.clothingId }.filterValues { records -> records.maxOf { it.wornDate } >= LocalDate.now().minusDays(recentDays).toString() }.keys
                    OutfitRecommendationEngine().generate(clothes, OutfitOptions(season, recentIds), kotlin.random.Random.Default)
                }
                if (saveHomeOutfit && suggestion is OutfitGeneration.Ready) AlertDialog(onDismissRequest = { saveHomeOutfit = false }, title = { Text("코디 저장") }, text = { Column { OutlinedTextField(homeOutfitName, { homeOutfitName = it }, label = { Text("코디 이름") }); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(homeOutfitFavorite, { homeOutfitFavorite = it }); Text("즐겨찾기에 추가") } } }, confirmButton = { TextButton(onClick = { vm.saveOutfit(homeOutfitName.ifBlank { "나의 코디" }, suggestion.outfit.top.id, suggestion.outfit.bottom.id, suggestion.outfit.outer?.id, homeOutfitFavorite) { result -> homeWearMessage = result.fold({ if (it is SaveOutfitResult.Duplicate) "같은 조합이 이미 저장되어 있습니다." else "코디를 저장했습니다." }, { it.message ?: "코디 저장에 실패했습니다." }) }; saveHomeOutfit = false }) { Text("저장") } }, dismissButton = { TextButton(onClick = { saveHomeOutfit = false }) { Text("취소") } })
                pendingHomeWear?.let { ids -> AlertDialog(onDismissRequest = { pendingHomeWear = null }, title = { Text("구매일 확인") }, text = { Text("코디 구성 의류의 구매일보다 이른 기록입니다. 오늘 착용했다고 확인하고 저장할까요?") }, confirmButton = { TextButton(onClick = { pendingHomeWear = null; vm.recordClothesForDay(ids, LocalDate.now(), true) { result -> homeWearMessage = result.fold({ "새 기록 ${it.newlyRecorded}벌 · 이미 기록 ${it.alreadyRecorded}벌" }, { it.message ?: "착용 기록 저장에 실패했습니다." }) } }) { Text("확인 후 기록") } }, dismissButton = { TextButton(onClick = { pendingHomeWear = null }) { Text("취소") } }) }
                when (suggestion) {
                    OutfitGeneration.MissingTop -> Text("상의가 등록되어 있지 않아 조합을 만들 수 없습니다. 내 옷장에서 상의를 등록해 주세요.")
                    OutfitGeneration.MissingBottom -> Text("하의가 등록되어 있지 않아 조합을 만들 수 없습니다. 내 옷장에서 하의를 등록해 주세요.")
                    OutfitGeneration.NoCombination -> Text("등록된 의류로 만들 수 있는 조합이 없습니다.")
                    is OutfitGeneration.Ready -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClothingImage(suggestion.outfit.top.photoPath, Modifier.weight(1f).height(150.dp))
                            ClothingImage(suggestion.outfit.bottom.photoPath, Modifier.weight(1f).height(150.dp))
                        }
                        Text("${suggestion.outfit.top.name} + ${suggestion.outfit.bottom.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${weather.manualSeason?.let { "선택한 $it 계절과 " } ?: "등록된 "}의류 정보를 기준으로 조합했습니다.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saveHomeOutfit = true }) { Text("코디 저장") }
                            OutlinedButton(onClick = { vm.recordClothesForDay(listOf(suggestion.outfit.top.id, suggestion.outfit.bottom.id), LocalDate.now()) { result -> result.fold({ batch -> if (batch.needsPurchaseConfirmation) pendingHomeWear = listOf(suggestion.outfit.top.id, suggestion.outfit.bottom.id) else homeWearMessage = "새 기록 ${batch.newlyRecorded}벌 · 이미 기록 ${batch.alreadyRecorded}벌" }, { homeWearMessage = it.message ?: "착용 기록 저장에 실패했습니다." }) } }) { Text("오늘 입었어요") }
                        }
                        homeWearMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                if (suggestion is OutfitGeneration.MissingTop || suggestion is OutfitGeneration.MissingBottom) OutlinedButton(onClick = onRegister) { Text("의류 등록") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOutfits) { Text("다른 코디 보기") }
                    OutlinedButton(onClick = { onTryOn((suggestion as? OutfitGeneration.Ready)?.outfit?.top?.id) }) { Text("AI 상의 착용 이미지") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("나의 옷을 한곳에 모아 관리해 보세요", style = MaterialTheme.typography.titleMedium)
                Text("사진과 정보를 등록하면 언제든 옷장에서 확인할 수 있어요.")
                Button(onClick = onCloset) { Text("내 옷장 열기") }
                Text("등록 의류 ${clothes.size}벌 · 저장 코디 ${saved.size}개")
            }
        }
    }
}

@Composable private fun WeatherCard(settings: WeatherSettings, result: WeatherResult?, onRefresh: () -> Unit, onRegion: () -> Unit, onManual: () -> Unit) {
    val presentation = resolveWeatherPresentation(settings, result)
    val snapshot = presentation.snapshot
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("현재 날씨", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(when (presentation.mode) {
                WeatherDisplayMode.MANUAL -> "수동 입력 모드${settings.location?.let { " · ${it.name}" }.orEmpty()}"
                WeatherDisplayMode.LOCATION_UNSET -> "지역 미설정"
                WeatherDisplayMode.FRESH -> "최신 날씨 · ${settings.location?.name.orEmpty()}"
                WeatherDisplayMode.CACHED -> if (presentation.message != null) "갱신 실패 · 이전 조회 정보 표시 · ${settings.location?.name.orEmpty()}" else "이전 조회 정보 · ${settings.location?.name.orEmpty()}"
                WeatherDisplayMode.FAILED -> "날씨 조회 실패 · ${settings.location?.name.orEmpty()}"
                WeatherDisplayMode.LOADING -> "날씨를 조회해 주세요 · ${settings.location?.name.orEmpty()}"
            }, style = MaterialTheme.typography.titleMedium)
            if (settings.manualMode) {
                Text(settings.manualTemperature?.let { "수동 기온 ${it}°C" } ?: "수동 기온 미입력 · ${settings.manualSeason ?: "계절 미선택"}")
            } else if (snapshot != null && settings.location != null) {
                Text("${snapshot.temperatureC}°C · 습도 ${snapshot.humidityPercent}% · ${snapshot.condition}")
                Text("갱신 ${java.time.Instant.ofEpochMilli(snapshot.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}", style = MaterialTheme.typography.bodySmall)
                presentation.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else presentation.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("습도는 참고 정보입니다. 날씨 제공 실패 시 임의의 날씨 수치를 표시하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("날씨 제공: Open-Meteo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRegion) { Text("지역 선택") }
                OutlinedButton(onClick = onManual) { Text("수동 모드") }
                if (!settings.manualMode && settings.location != null) TextButton(onClick = onRefresh) { Text("갱신") }
            }
            if (result is WeatherResult.Cached) Text("최근 성공한 날씨를 표시 중입니다. 갱신 ${java.time.Instant.ofEpochMilli(result.snapshot.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalStringForCloset()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun java.time.LocalDateTime.toLocalStringForCloset() = "$year-$monthValue-$dayOfMonth $hour:$minute"

@Composable private fun WeatherLocationDialog(vm: ClosetViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<WeatherLocation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("날씨 지역 선택") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("도시 또는 지역") }, singleLine = true)
            Button(enabled = query.isNotBlank() && !searching, onClick = { searching = true; vm.searchWeatherLocations(query) { result -> searching = false; result.onSuccess { matches = it; error = if (it.isEmpty()) "검색 결과가 없습니다." else null }.onFailure { error = it.message ?: "지역 검색에 실패했습니다." } } }) { Text(if (searching) "검색 중…" else "검색") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            matches.forEach { location -> TextButton(onClick = { vm.setWeatherLocation(location); onDismiss() }) { Text(location.name) } }
            Text("위치 권한은 사용하지 않습니다. 지역을 직접 선택합니다.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

@Composable private fun ManualWeatherDialog(settings: WeatherSettings, onDismiss: () -> Unit, onSave: (Double?, String?) -> Unit) {
    var temperature by remember { mutableStateOf(settings.manualTemperature?.toString().orEmpty()) }
    var season by remember { mutableStateOf(settings.manualSeason.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("수동 추천 기준") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(temperature, { temperature = it }, label = { Text("기온 °C (선택)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Text("또는 계절을 선택하세요")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("봄", "여름", "가을", "겨울").forEach { FilterChip(season == it, { season = if (season == it) "" else it }, label = { Text(it) }) } }
            Text("수동 기준이며 실제 관측 날씨가 아닙니다.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val parsed = temperature.toDoubleOrNull()
        error = when { temperature.isNotBlank() && parsed == null -> "기온을 숫자로 입력해 주세요."; parsed != null && parsed !in -80.0..60.0 -> "기온 범위는 -80°C부터 60°C까지입니다."; parsed == null && season.isBlank() -> "기온이나 계절을 하나 이상 선택해 주세요."; else -> null }
        if (error == null) onSave(parsed, season.ifBlank { null })
    }) { Text("사용") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable private fun OutfitScreen(vm: ClosetViewModel, onClothing: (Long) -> Unit, onRegister: () -> Unit) {
    val clothes by vm.allClothes.collectAsStateWithLifecycle()
    val wearRecords by vm.wearRecords.collectAsStateWithLifecycle()
    val weather by vm.weatherSettings.collectAsStateWithLifecycle()
    val weatherResult by vm.weatherResult.collectAsStateWithLifecycle()
    var includeOuter by remember { mutableStateOf(false) }
    var randomMode by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(setOf<String>()) }
    var current by remember { mutableStateOf<OutfitCombination?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var outfitName by remember { mutableStateOf("") }
    var favoriteOnSave by remember { mutableStateOf(false) }
    var confirmPurchase by remember { mutableStateOf(false) }
    var recordMessage by remember { mutableStateOf<String?>(null) }
    val latestWearDate = remember(wearRecords) { wearRecords.groupBy { it.clothingId }.mapValues { (_, records) -> records.maxOf { it.wornDate } } }
    val preferredSeason = remember(weather, weatherResult) {
        if (weather.manualMode) weather.manualSeason ?: weather.manualTemperature?.let { OutfitRecommendationEngine().seasonForTemperature(it) }
        else (weatherResult as? WeatherResult.Fresh)?.snapshot?.temperatureC?.let { OutfitRecommendationEngine().seasonForTemperature(it) }
            ?: (weatherResult as? WeatherResult.Cached)?.snapshot?.temperatureC?.let { OutfitRecommendationEngine().seasonForTemperature(it) }
            ?: weather.cachedWeather?.temperatureC?.let { OutfitRecommendationEngine().seasonForTemperature(it) }
    }
    val combinationsCount = clothes.count { it.category == "상의" } * clothes.count { it.category == "하의" } *
        (if (includeOuter && clothes.any { it.category == "아우터" }) clothes.count { it.category == "아우터" } else 1)
    fun generate(random: Boolean) {
        randomMode = random
        if (combinationsCount > 0 && seen.size >= combinationsCount) { seen = emptySet(); message = "가능한 모든 조합을 확인했습니다. 새 순서로 다시 보여드릴게요." }
        val recent = latestWearDate.filterValues { it >= LocalDate.now().minusDays(RecommendationRules().recentWearWindowDays.toLong()).toString() }.keys
        when (val generated = OutfitRecommendationEngine().generate(clothes, OutfitOptions(preferredSeason, recent, includeOuter), kotlin.random.Random.Default, seen, random)) {
            OutfitGeneration.MissingTop -> { current = null; message = "상의가 부족합니다. 내 옷장에서 등록해 주세요." }
            OutfitGeneration.MissingBottom -> { current = null; message = "하의가 부족합니다. 내 옷장에서 등록해 주세요." }
            OutfitGeneration.NoCombination -> { current = null; message = "등록된 의류 조합이 없습니다." }
            is OutfitGeneration.Ready -> { current = generated.outfit; seen = seen + generated.outfit.key; if (generated.alternatives == 1) message = "가능한 조합이 하나뿐입니다." else if (seen.size == combinationsCount) message = "가능한 조합을 모두 확인했습니다." }
        }
    }
    LaunchedEffect(clothes, preferredSeason, includeOuter) { if (current == null || listOfNotNull(current?.top?.id, current?.bottom?.id, current?.outer?.id).any { id -> clothes.none { it.id == id } }) generate(false) }
    if (saveDialog) AlertDialog(onDismissRequest = { saveDialog = false }, title = { Text("코디 저장") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(outfitName, { outfitName = it }, label = { Text("코디 이름") }); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(favoriteOnSave, { favoriteOnSave = it }); Text("즐겨찾기에도 추가") } }
    }, confirmButton = { TextButton(onClick = { current?.let { selected -> vm.saveOutfit(outfitName.ifBlank { "나의 코디" }, selected.top.id, selected.bottom.id, selected.outer?.id, favoriteOnSave) { result -> message = result.fold({ when (it) { is SaveOutfitResult.Saved -> "코디를 저장했습니다."; is SaveOutfitResult.Duplicate -> "같은 조합이 이미 저장되어 있습니다." } }, { it.message ?: "저장에 실패했습니다." }) } }; saveDialog = false }) { Text("저장") } }, dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("취소") } })
    if (confirmPurchase) AlertDialog(onDismissRequest = { confirmPurchase = false }, title = { Text("구매일 확인") }, text = { Text("구성 의류 중 구매일보다 이른 날짜가 포함되어 있습니다. 오늘 착용 기록을 계속 추가할까요?") }, confirmButton = { TextButton(onClick = { confirmPurchase = false; recordCurrent(vm, current, true) { recordMessage = it } }) { Text("계속") } }, dismissButton = { TextButton(onClick = { confirmPurchase = false }) { Text("취소") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("코디 추천", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("추천 기준: ${preferredSeason ?: "등록된 계절 정보"} · 프로젝트 규칙이며 개인에게 맞는 정답은 아닙니다.")
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(includeOuter, { includeOuter = it; seen = emptySet(); current = null }); Text("아우터 포함") }
        if (current != null) {
            val outfit = current!!
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(onClick = { onClothing(outfit.top.id) }, modifier = Modifier.weight(1f)) { Column { ClothingImage(outfit.top.photoPath, Modifier.fillMaxWidth().height(180.dp)); Text("상의 · ${outfit.top.name}", Modifier.padding(8.dp)) } }
                Card(onClick = { onClothing(outfit.bottom.id) }, modifier = Modifier.weight(1f)) { Column { ClothingImage(outfit.bottom.photoPath, Modifier.fillMaxWidth().height(180.dp)); Text("하의 · ${outfit.bottom.name}", Modifier.padding(8.dp)) } }
            }
            outfit.outer?.let { outer -> Card(onClick = { onClothing(outer.id) }) { Row(verticalAlignment = Alignment.CenterVertically) { ClothingImage(outer.photoPath, Modifier.size(100.dp)); Text("아우터 · ${outer.name}", Modifier.padding(8.dp)) } } }
            Text("${if (randomMode) "등록 의류에서 무작위로 조합했습니다." else "선택한 계절과 등록된 의류 정보를 기준으로 조합했습니다."} 최근 7일 착용 기록과 즐겨찾기는 보조 기준입니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { generate(false) }) { Text("다른 조합 보기") }; OutlinedButton(onClick = { generate(true) }) { Text("랜덤 코디") }; OutlinedButton(onClick = { saveDialog = true }) { Text("저장") } }
            OutlinedButton(onClick = { recordCurrent(vm, current, false) { status -> if (status == "구매일 확인 필요") confirmPurchase = true else recordMessage = status } }, modifier = Modifier.fillMaxWidth()) { Text("오늘 이 코디를 입었어요") }
            recordMessage?.let { Text(it) }
        } else {
            Text(message ?: "상의와 하의를 등록하면 실제 의류로 코디를 만들 수 있습니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { generate(false) }) { Text("추천 코디 생성") }
                if (message?.contains("부족") == true) OutlinedButton(onClick = onRegister) { Text("의류 등록") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (weather.manualMode) Text("현재 수동 모드로 추천합니다.")
        else if (weatherResult is WeatherResult.Cached) Text("날씨 연결을 사용할 수 없어 갱신 시각이 표시된 캐시 기준입니다.")
        else if (preferredSeason == null) Text("날씨나 계절을 설정하지 않았습니다. 의류 등록 정보만으로 조합합니다.")
    }
}

private fun recordCurrent(vm: ClosetViewModel, outfit: OutfitCombination?, confirm: Boolean, onMessage: (String) -> Unit) {
    if (outfit == null) return
    val ids = listOfNotNull(outfit.top.id, outfit.bottom.id, outfit.outer?.id)
    vm.recordClothesForDay(ids, LocalDate.now(), confirm) { result ->
        result.fold({ batch -> if (batch.needsPurchaseConfirmation) onMessage("구매일 확인 필요") else onMessage("새 기록 ${batch.newlyRecorded}벌 · 이미 기록 ${batch.alreadyRecorded}벌") }, { onMessage(it.message ?: "착용 기록 저장에 실패했습니다.") })
    }
}

@Composable private fun SavedOutfitDetailScreen(vm: ClosetViewModel, id: Long, onClothing: (Long) -> Unit) {
    val outfit by vm.savedOutfit(id).collectAsStateWithLifecycle(initialValue = null)
    val clothes by vm.allClothes.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmPurchase by remember { mutableStateOf(false) }
    val saved = outfit
    val top = saved?.let { outfit -> clothes.firstOrNull { it.id == outfit.topClothingId } }
    val bottom = saved?.let { outfit -> clothes.firstOrNull { it.id == outfit.bottomClothingId } }
    val outer = saved?.outerClothingId?.let { outerId -> clothes.firstOrNull { it.id == outerId } }
    val combination = if (top != null && bottom != null) OutfitCombination(top, bottom, outer) else null
    if (confirmPurchase) AlertDialog(onDismissRequest = { confirmPurchase = false }, title = { Text("구매일 확인") }, text = { Text("구성 의류 중 구매일보다 이른 날짜가 포함되어 있습니다. 오늘 기록을 추가할까요?") }, confirmButton = { TextButton(onClick = { confirmPurchase = false; recordCurrent(vm, combination, true) { message = it } }) { Text("계속") } }, dismissButton = { TextButton(onClick = { confirmPurchase = false }) { Text("취소") } })
    if (saved == null || combination == null) { Placeholder("저장 코디", "구성 의류가 삭제되어 사용할 수 없는 코디입니다."); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(saved.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        listOfNotNull(combination.top, combination.bottom, combination.outer).forEach { garment ->
            Card(onClick = { onClothing(garment.id) }) { Row(verticalAlignment = Alignment.CenterVertically) { ClothingImage(garment.photoPath, Modifier.size(130.dp)); Column(Modifier.padding(12.dp)) { Text("${garment.category} · ${garment.name}", style = MaterialTheme.typography.titleMedium); Text("상세정보 보기") } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.toggleOutfitFavorite(saved) }) { Text(if (saved.favorite) "즐겨찾기 해제" else "즐겨찾기 추가") }
            OutlinedButton(onClick = { recordCurrent(vm, combination, false) { status -> if (status == "구매일 확인 필요") confirmPurchase = true else message = status } }) { Text("오늘 이 코디를 입었어요") }
        }
        message?.let { Text(it) }
    }
}

@Composable private fun SettingsScreen(vm: ClosetViewModel) {
    val settings by vm.reminderSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hourText by remember(settings.hour) { mutableStateOf(settings.hour.toString().padStart(2, '0')) }
    var minuteText by remember(settings.minute) { mutableStateOf(settings.minute.toString().padStart(2, '0')) }
    var message by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val updated = OutfitReminderSettings(true, hourText.toIntOrNull() ?: 8, minuteText.toIntOrNull() ?: 0)
            vm.setReminderSettings(updated); DailyOutfitReminderScheduler.apply(context, updated); message = "데일리 코디 알림을 설정했습니다. 시스템 상태에 따라 실행 시각이 늦어질 수 있습니다."
        } else message = "알림 권한이 없어 알림을 켜지 않았습니다. 권한 없이도 앱의 주요 기능은 사용할 수 있습니다."
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("데일리 코디 알림", style = MaterialTheme.typography.titleLarge)
            Text("원하는 시간대를 선택합니다. Android 백그라운드 실행 제약으로 정확한 정시 실행은 보장되지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hourText, { hourText = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("시 (0–23)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(minuteText, { minuteText = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("분 (0–59)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val hour = hourText.toIntOrNull(); val minute = minuteText.toIntOrNull()
                    if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) message = "시간은 0–23시, 분은 0–59분으로 입력해 주세요."
                    else if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else { val updated = OutfitReminderSettings(true, hour, minute); vm.setReminderSettings(updated); DailyOutfitReminderScheduler.apply(context, updated); message = "데일리 코디 알림을 설정했습니다." }
                }) { Text("알림 켜기") }
                OutlinedButton(onClick = { val disabled = settings.copy(enabled = false); vm.setReminderSettings(disabled); DailyOutfitReminderScheduler.apply(context, disabled); message = "데일리 코디 알림을 껐습니다." }) { Text("끄기") }
            }
            Text(if (settings.enabled) "알림 사용 중 · ${settings.hour.toString().padStart(2, '0')}:${settings.minute.toString().padStart(2, '0')}" else "알림 꺼짐")
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        } }
        Text("날씨 지역과 수동 추천 기준은 홈 화면의 날씨 카드에서 설정합니다.")
    }
}

@Composable private fun FavoritesScreen(vm: ClosetViewModel, onClothing: (Long) -> Unit, onOutfit: (Long) -> Unit) {
    val clothes by vm.favorites.collectAsStateWithLifecycle()
    val outfits by vm.savedOutfits.collectAsStateWithLifecycle()
    val favoriteOutfits by vm.favoriteOutfits.collectAsStateWithLifecycle()
    val all by vm.allClothes.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("즐겨찾기", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("의류", "저장 코디", "코디 즐겨찾기").forEachIndexed { index, label -> FilterChip(selectedTab == index, { selectedTab = index }, label = { Text(label) }) } }
        val selectedOutfits = if (selectedTab == 2) favoriteOutfits else outfits
        if (selectedTab > 0) {
            if (selectedOutfits.isEmpty()) Text(if (selectedTab == 2) "즐겨찾기한 저장 코디가 없습니다." else "저장한 코디가 없습니다.")
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { selectedOutfits.forEach { outfit ->
                val top = all.firstOrNull { it.id == outfit.topClothingId }; val bottom = all.firstOrNull { it.id == outfit.bottomClothingId }; val outer = all.firstOrNull { it.id == outfit.outerClothingId }
                if (top != null && bottom != null) Card(onClick = { onOutfit(outfit.id) }) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(outfit.name, style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ClothingImage(top.photoPath, Modifier.weight(1f).height(140.dp)); ClothingImage(bottom.photoPath, Modifier.weight(1f).height(140.dp)); outer?.let { ClothingImage(it.photoPath, Modifier.weight(1f).height(140.dp)) } }; Text("${top.name} + ${bottom.name}${outer?.let { " + ${it.name}" }.orEmpty()}") } }
            } }
        } else if (clothes.isEmpty()) Text("즐겨찾기한 의류가 없습니다.")
        else LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(clothes, key = { it.id }) { item -> Card(onClick = { onClothing(item.id) }) { Column { ClothingImage(item.photoPath, Modifier.fillMaxWidth().height(170.dp)); Text(item.name, Modifier.padding(10.dp)) } } } }
    }
}

@Composable private fun AnalysisScreen(vm: ClosetViewModel) {
    val stats by vm.statistics.collectAsStateWithLifecycle()
    val wearAnalysis by vm.wearAnalytics.collectAsStateWithLifecycle()
    val wearSettings by vm.wearAnalysisSettings.collectAsStateWithLifecycle()
    var showGoalEditor by remember { mutableStateOf(false) }
    var showWearSettings by remember { mutableStateOf(false) }
    if (showGoalEditor) GoalSettingsDialog(stats, onDismiss = { showGoalEditor = false }, onSave = { goals -> vm.setGoals(goals); showGoalEditor = false })
    if (showWearSettings) WearAnalysisSettingsDialog(wearSettings, onDismiss = { showWearSettings = false }, onSave = { settings -> vm.setWearAnalysisSettings(settings); showWearSettings = false })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("옷장 분석", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = { showGoalEditor = true }) { Icon(Icons.Default.Tune, null); Text(" 목표 설정") }
        }
        Text("앱에 등록된 의류 ${stats.total}벌을 기준으로 집계합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("전체 의류", "${stats.total}벌", Modifier.weight(1f))
            SummaryCard("즐겨찾기", "${stats.favoriteCount}벌", Modifier.weight(1f))
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("종류별 보유 현황", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                ClosetStatistics.categories.forEach { category ->
                    val count = stats.byCategory[category] ?: 0
                    val percent = stats.categoryPercent[category] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(category, Modifier.width(52.dp))
                        Box(Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))) {
                            if (percent > 0) Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)))
                        }
                        Text("$count벌 · $percent%", Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("종류별 비율의 분모는 전체 등록 의류입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("계절별 보유 현황", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val maxSeasonCount = (stats.bySeason.values.maxOrNull() ?: 0).coerceAtLeast(1)
                ClosetStatistics.seasons.forEach { season ->
                    val count = stats.bySeason[season] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(season, Modifier.width(42.dp))
                        Box(Modifier.weight(1f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))) {
                            if (count > 0) Box(Modifier.fillMaxWidth(count.toFloat() / maxSeasonCount).fillMaxHeight().background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(20.dp)))
                        }
                        Text("$count벌", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("한 벌을 여러 계절에 등록할 수 있어 계절별 수량 합계는 전체 수량보다 클 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("계절·종류별 목표", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { showGoalEditor = true }) { Text("편집") }
                }
                ClosetStatistics.seasons.forEach { season -> ClosetStatistics.categories.forEach { category ->
                    val key = ClosetTargetKey(season, category)
                    val current = stats.bySeasonAndCategory[key] ?: 0
                    val target = stats.goals[key]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("$season $category", Modifier.weight(1f))
                        Text("현재 $current벌 · 목표 ${target?.let { "${it}벌" } ?: "미설정"}", style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("목표와 비교", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                when {
                    stats.goals.isEmpty() -> Text("계절과 종류별 목표 수량을 설정하면 현재 등록 수량과 비교합니다.")
                    stats.shortages.isEmpty() -> Text("현재 설정한 목표보다 적은 항목은 없습니다.")
                    else -> stats.shortages.forEach { (key, shortage) -> Text("설정한 목표보다 ${key.season} ${key.category}가 ${shortage}벌 적습니다.", style = MaterialTheme.typography.bodyLarge) }
                }
                Text("목표 수량은 개인 설정이며 객관적인 적정 보유량이나 구매 권유가 아닙니다. 등록하지 않은 옷이 있는지 먼저 확인하세요.", style = MaterialTheme.typography.bodySmall)
            }
        }
        WearAnalysisPanels(wearAnalysis, wearSettings, onEditSettings = { showWearSettings = true })
    }
}

@Composable private fun WearAnalysisPanels(analysis: WearAnalytics, settings: WearAnalysisSettings, onEditSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("착용 기록 분석", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onEditSettings) { Text("기간 기준") }
                }
                Text("기준: 최근 ${settings.recentDays}일, 미착용 ${settings.noWearDays}일, 등록 후 미기록 ${settings.noWearAfterRegistrationDays}일 이상", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("총 착용 기록 ${analysis.totalRecords}건")
            }
        }
        AnalysisListCard("기록상 자주 착용한 의류", analysis.mostWorn.take(5).map { "${it.clothing.name} · ${it.wearCount}회${it.lastWornDate?.let { date -> " · 마지막 기록 $date" } ?: ""}" }, "착용 기록이 없습니다.")
        AnalysisListCard("최근 ${settings.recentDays}일 착용 기록", analysis.recentWears.take(5).map { "${it.wornDate} · ${it.clothing.name}" }, "해당 기간의 기록이 없습니다.")
        AnalysisListCard("최근 ${settings.noWearDays}일 기록이 없는 의류", analysis.noWearWithinPeriod.map { "${it.clothing.name} · 마지막 기록 ${it.lastWornDate}" }, "기록 기준으로 해당하는 의류가 없습니다.")
        AnalysisListCard("등록 후 ${settings.noWearAfterRegistrationDays}일 이상 기록이 없는 의류", analysis.registeredWithoutWear.map { "${it.name} · 등록 ${Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()}" }, "기준 기간 이상 기록이 없는 의류가 없습니다.")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("구매일과 착용 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (analysis.purchaseHistory.isEmpty()) Text("구매일이 입력된 의류가 없습니다.")
                analysis.purchaseHistory.forEach { item ->
                    Text("${item.clothing.name} · 구매 ${item.clothing.purchaseDate} · 경과 ${item.daysSincePurchase}일 · 기록상 ${item.wearCount}회${item.lastWornDate?.let { " · 마지막 $it" } ?: " · 착용 기록 없음"}")
                }
                Text("구매일을 모르는 의류의 보유 기간은 추정하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("기록 해석", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("앱에 입력된 착용 기록 기준입니다. 기록이 없다고 실제로 입지 않았다는 뜻은 아닙니다.")
                if (analysis.mostWorn.isNotEmpty()) Text("착용 기록이 많은 의류입니다. 실제 상태를 확인해 보세요.")
                Text("착용 횟수나 경과 기간만으로 마모·손상·교체 필요성을 판단하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun AnalysisListCard(title: String, rows: List<String>, emptyMessage: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (rows.isEmpty()) Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { Text("• $it") }
        }
    }
}

@Composable private fun WearAnalysisSettingsDialog(settings: WearAnalysisSettings, onDismiss: () -> Unit, onSave: (WearAnalysisSettings) -> Unit) {
    var recent by remember(settings) { mutableStateOf(settings.recentDays.toString()) }
    var inactive by remember(settings) { mutableStateOf(settings.noWearDays.toString()) }
    var grace by remember(settings) { mutableStateOf(settings.noWearAfterRegistrationDays.toString()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("착용 분석 기간 기준") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WearDaysField("최근 기록 표시 기간 (일)", recent, { recent = it })
                WearDaysField("미착용 분류 기준 (일)", inactive, { inactive = it })
                WearDaysField("등록 후 미기록 분류 기준 (일)", grace, { grace = it })
                if (invalid) Text("각 기준은 1일 이상의 정수로 입력하세요.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            val recentDays = recent.toIntOrNull()
            val inactiveDays = inactive.toIntOrNull()
            val registrationDays = grace.toIntOrNull()
            if (recentDays == null || recentDays !in 1..3650 || inactiveDays == null || inactiveDays !in 1..3650 || registrationDays == null || registrationDays !in 1..3650) invalid = true
            else onSave(WearAnalysisSettings(recentDays, inactiveDays, registrationDays))
        }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable private fun WearDaysField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.all { char -> char.isDigit() }) onChange(it) }, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
}

@Composable private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun GoalSettingsDialog(stats: ClosetStatistics, onDismiss: () -> Unit, onSave: (Map<ClosetTargetKey, Int?>) -> Unit) {
    val values = remember(stats.goals) {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            ClosetStatistics.seasons.forEach { season -> ClosetStatistics.categories.forEach { category ->
                val key = ClosetTargetKey(season, category)
                put(goalKey(key), stats.goals[key]?.toString().orEmpty())
            } }
        }
    }
    var validationError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목표 수량 설정") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("목표는 개인 기준입니다. 비워 두면 해당 항목의 비교를 사용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                ClosetStatistics.seasons.forEach { season -> ClosetStatistics.categories.forEach { category ->
                    val key = ClosetTargetKey(season, category)
                    OutlinedTextField(
                        value = values[goalKey(key)].orEmpty(),
                        onValueChange = { input -> if (input.all { it.isDigit() }) values[goalKey(key)] = input },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("$season $category 목표 (벌)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                } }
                if (validationError) Text("목표 수량을 0 이상의 정수로 입력하거나 비워 두세요.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                validationError = false
                val targets = buildMap {
                    ClosetStatistics.seasons.forEach { season -> ClosetStatistics.categories.forEach { category ->
                        val key = ClosetTargetKey(season, category)
                        val text = values[goalKey(key)].orEmpty()
                        val count = if (text.isBlank()) null else text.toIntOrNull()
                        if (text.isNotBlank() && count == null) validationError = true
                        put(key, count)
                    } }
                }
                if (!validationError) onSave(targets)
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

private fun goalKey(key: ClosetTargetKey) = "${key.season}_${key.category}"

@Composable private fun ClosetScreen(vm: ClosetViewModel, favoritesOnly: Boolean, onAdd: () -> Unit, onItem: (Long) -> Unit) {
    val clothes by (if (favoritesOnly) vm.favorites else vm.items).collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (favoritesOnly) "즐겨찾기" else "내 옷장", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (!favoritesOnly) Text("등록된 의류 $count벌", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!favoritesOnly) FilledTonalButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Text(" 추가") }
        }
        if (!favoritesOnly) {
            OutlinedTextField(value = filters.query, onValueChange = vm::setQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("이름 검색") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filters.category.isEmpty(), { vm.setCategory("") }, label = { Text("전체 종류") })
                listOf("상의", "하의", "아우터").forEach { value -> FilterChip(filters.category == value, { vm.setCategory(if (filters.category == value) "" else value) }, label = { Text(value) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("봄", "여름", "가을", "겨울").forEach { value -> FilterChip(filters.season == value, { vm.setSeason(if (filters.season == value) "" else value) }, label = { Text(value) }) }
                OutlinedTextField(value = filters.color, onValueChange = vm::setColor, modifier = Modifier.width(120.dp), singleLine = true, label = { Text("색상") })
            }
        } else Text("코디 즐겨찾기는 준비 중입니다.", Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (clothes.isEmpty()) {
            val emptyAll = if (favoritesOnly) count == 0 else count == 0
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (emptyAll) if (favoritesOnly) "아직 등록된 의류가 없습니다." else "옷장이 비어 있어요." else if (favoritesOnly) "즐겨찾기한 의류가 없습니다." else "검색 조건에 맞는 의류가 없습니다.", style = MaterialTheme.typography.titleMedium)
                    if (!favoritesOnly && emptyAll) Button(onClick = onAdd) { Text("첫 의류 등록") }
                    if (favoritesOnly && count == 0) Text("내 옷장에서 의류를 등록해 보세요.")
                }
            }
        } else LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(clothes, key = { it.id }) { item ->
                Card(onClick = { onItem(item.id) }, shape = RoundedCornerShape(18.dp)) {
                    Column {
                        ClothingImage(item.photoPath, Modifier.fillMaxWidth().height(170.dp))
                        Column(Modifier.padding(12.dp)) {
                            Text(item.name, maxLines = 1, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("${item.category} · ${item.seasons.replace(',', ' ')}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailScreen(vm: ClosetViewModel, id: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val item by vm.observe(id).collectAsStateWithLifecycle(initialValue = null)
    val wearRecords by vm.observeWearHistory(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val wearSummary by vm.observeWearSummary(id).collectAsStateWithLifecycle(initialValue = WearRecordSummary(0, null))
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmWearDelete by remember { mutableStateOf<Long?>(null) }
    var purchaseConfirmation by remember { mutableStateOf<PendingWearAction?>(null) }
    var wearMessage by remember { mutableStateOf<String?>(null) }
    if (confirmDelete && item != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("의류 삭제") }, text = { Text("이 의류와 연결된 착용 기록·저장 코디, 앱에 보관된 사진을 함께 삭제할까요? 원본 사진은 삭제되지 않습니다.") }, confirmButton = { TextButton(onClick = { vm.delete(item!!) { onBack() }; confirmDelete = false }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } })
    val submitWearAction: (PendingWearAction, Boolean) -> Unit = { action, confirmed ->
        val onDone: (Result<WearSaveResult>) -> Unit = { result -> result.fold(
            onSuccess = { saved -> when (saved.status) {
                WearSaveStatus.ADDED -> wearMessage = "착용 기록을 저장했습니다."
                WearSaveStatus.ALREADY_RECORDED -> wearMessage = "이 날짜의 착용 기록이 이미 있습니다."
                WearSaveStatus.NEEDS_PURCHASE_CONFIRMATION -> purchaseConfirmation = action
                WearSaveStatus.UPDATED -> wearMessage = "착용 날짜를 수정했습니다."
                WearSaveStatus.DATE_ALREADY_RECORDED -> wearMessage = "해당 날짜에 이미 기록이 있어 수정할 수 없습니다."
            } },
            onFailure = { wearMessage = it.message ?: "착용 기록을 저장하지 못했습니다." }
        ) }
        if (action.recordId == null) vm.addWear(id, action.date, confirmed, onDone)
        else vm.updateWearDate(action.recordId, action.date, confirmed, onDone)
    }
    purchaseConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { purchaseConfirmation = null },
            title = { Text("구매일 확인") },
            text = { Text("선택한 착용일이 등록된 구매일보다 빠릅니다. 입력한 날짜가 맞는지 확인하고 계속할까요?") },
            confirmButton = { TextButton(onClick = { purchaseConfirmation = null; submitWearAction(action, true) }) { Text("확인 후 저장") } },
            dismissButton = { TextButton(onClick = { purchaseConfirmation = null }) { Text("취소") } }
        )
    }
    confirmWearDelete?.let { recordId ->
        AlertDialog(
            onDismissRequest = { confirmWearDelete = null }, title = { Text("착용 기록 삭제") },
            text = { Text("이 날짜의 착용 기록을 삭제할까요? 착용 횟수와 마지막 착용일이 다시 계산됩니다.") },
            confirmButton = { TextButton(onClick = { vm.deleteWear(recordId) { result -> wearMessage = if (result.isSuccess) "착용 기록을 삭제했습니다." else "기록 삭제에 실패했습니다." }; confirmWearDelete = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmWearDelete = null }) { Text("취소") } }
        )
    }
    if (item == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val clothing = item!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("‹ 옷장") }; Spacer(Modifier.weight(1f)); IconButton(onClick = { vm.toggleFavorite(clothing) }) { Icon(if (clothing.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "즐겨찾기") }; TextButton(onClick = onEdit) { Text("수정") }; TextButton(onClick = { confirmDelete = true }) { Text("삭제") } }
        ClothingImage(clothing.photoPath, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 360.dp))
        Text(clothing.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        InfoRow("종류", clothing.category); InfoRow("세부 종류", clothing.subcategory); InfoRow("계절", clothing.seasons.replace(',', ' ')); InfoRow("색상", clothing.color); InfoRow("소재", clothing.material); InfoRow("구매일", clothing.purchaseDate ?: "모름"); InfoRow("메모", clothing.memo)
        InfoRow("등록일", Instant.ofEpochMilli(clothing.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("착용 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("기록상 ${wearSummary.wearCount}회", style = MaterialTheme.typography.titleMedium)
                Text(wearSummary.lastWornDate?.let { "마지막 기록일 $it" } ?: "마지막 착용일: 기록 없음")
                if (wearSummary.wearCount == 0) Text("이 앱에 기록이 없다는 뜻이며, 실제로 입지 않았다는 의미는 아닙니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { submitWearAction(PendingWearAction(null, LocalDate.now()), false) }) { Text("오늘 입었어요") }
                    OutlinedButton(onClick = { showWearDatePicker(context, LocalDate.now()) { date -> submitWearAction(PendingWearAction(null, date), false) } }) { Text("날짜 선택") }
                }
                wearMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                HorizontalDivider()
                Text("기록 목록", style = MaterialTheme.typography.titleMedium)
                if (wearRecords.isEmpty()) Text("아직 착용 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                wearRecords.forEach { record ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(record.wornDate, Modifier.weight(1f))
                        TextButton(onClick = { showWearDatePicker(context, LocalDate.parse(record.wornDate)) { date -> submitWearAction(PendingWearAction(record.id, date), false) } }) { Text("날짜 수정") }
                        IconButton(onClick = { confirmWearDelete = record.id }) { Icon(Icons.Default.Delete, "기록 삭제") }
                    }
                }
            }
        }
        CareGuide(clothing.material)
    }
}

private data class PendingWearAction(val recordId: Long?, val date: LocalDate)

private fun showWearDatePicker(context: Context, initialDate: LocalDate, onDateSelected: (LocalDate) -> Unit) {
    val dialog = DatePickerDialog(context, { _, year, month, day -> onDateSelected(LocalDate.of(year, month + 1, day)) }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth)
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }
    dialog.datePicker.maxDate = today.timeInMillis
    dialog.show()
}

@Composable private fun InfoRow(label: String, value: String?) { if (!value.isNullOrBlank()) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label, Modifier.width(86.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, modifier = Modifier.weight(1f)) } }

@Composable private fun CareGuide(material: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("소재별 관리 안내", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val guide = when (material?.trim()?.lowercase()) {
                "면", "cotton", "울", "모", "wool", "실크", "silk", "폴리에스터", "polyester" -> "$material 소재로 등록되어 있습니다. 혼용률과 제품 가공에 따라 관리 조건이 달라지므로 이 소재명만으로 세탁 조건을 정하지 말고 제품 라벨의 세탁·표백·건조·다림질·전문 관리 표시를 확인하세요."
                else -> null
            }
            Text(guide ?: if (material.isNullOrBlank()) "소재 정보가 없어 안내를 제공할 수 없습니다." else "등록된 소재에 대한 일반 안내 자료가 없습니다.")
            Text("소재와 혼용률, 가공 방식에 따라 관리 방법이 다를 수 있습니다. 실제 의류의 제조사 케어라벨을 우선 확인하세요.", style = MaterialTheme.typography.bodySmall)
            Text("근거: GINETEX, Care symbols, ISO 3758:2023 개요. https://www.ginetex.net/article/GB/care-symbols", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun EditScreen(vm: ClosetViewModel, id: Long, onBack: () -> Unit, onSaved: () -> Unit) {
    val existing by if (id > 0) vm.observe(id).collectAsStateWithLifecycle(initialValue = null) else remember { mutableStateOf<Clothing?>(null) }
    val context = LocalContext.current
    var picked by remember { mutableStateOf<File?>(null) }
    var pendingCamera by remember { mutableStateOf<File?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var category by remember(existing?.id) { mutableStateOf(existing?.category.orEmpty()) }
    var subcategory by remember(existing?.id) { mutableStateOf(existing?.subcategory.orEmpty()) }
    var selectedSeasons by remember(existing?.id) { mutableStateOf(existing?.seasons?.split(',')?.toSet() ?: emptySet()) }
    var color by remember(existing?.id) { mutableStateOf(existing?.color.orEmpty()) }
    var material by remember(existing?.id) { mutableStateOf(existing?.material.orEmpty()) }
    var purchaseDate by remember(existing?.id) { mutableStateOf(existing?.purchaseDate.orEmpty()) }
    var memo by remember(existing?.id) { mutableStateOf(existing?.memo.orEmpty()) }
    var favorite by remember(existing?.id) { mutableStateOf(existing?.favorite ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            var temp: File? = null
            runCatching {
                temp = File(context.cacheDir, "picked-${System.nanoTime()}")
                context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "사진을 읽을 수 없습니다." }.use { src -> temp!!.outputStream().use { output -> src.copyTo(output) } } }
                picked?.delete(); picked = temp; error = null
            }.onFailure { temp?.delete(); error = it.message ?: "사진을 읽을 수 없습니다." }
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingCamera
        if (ok && file != null && file.length() > 0) { picked?.delete(); picked = file; error = null }
        else { file?.delete(); error = null }
        pendingCamera = null
    }
    DisposableEffect(Unit) { onDispose { picked?.delete(); pendingCamera?.delete() } }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("취소") }; Text(if (id == 0L) "의류 등록" else "의류 수정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (picked != null || existing != null) ClothingImage(picked?.absolutePath ?: existing!!.photoPath, Modifier.fillMaxWidth().height(210.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("사진 선택") }
            OutlinedButton(onClick = {
                val file = File(context.cacheDir, "capture-${System.nanoTime()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                pendingCamera = file; takePicture.launch(uri)
            }) { Text("사진 촬영") }
        }
        Field(name, { name = it }, "의류 이름 *")
        Text("종류 *")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("상의", "하의", "아우터").forEach { FilterChip(category == it, { category = it }, label = { Text(it) }) } }
        Field(subcategory, { subcategory = it }, "세부 종류")
        Text("계절 (복수 선택) *")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("봄", "여름", "가을", "겨울").forEach { value -> FilterChip(value in selectedSeasons, { selectedSeasons = if (value in selectedSeasons) selectedSeasons - value else selectedSeasons + value }, label = { Text(value) }) } }
        Field(color, { color = it }, "색상"); Field(material, { material = it }, "소재")
        Field(purchaseDate, { purchaseDate = it }, "구매일 (YYYY-MM-DD, 비워 두면 모름)", KeyboardType.Text)
        Text("구매 날짜는 오늘 이후 날짜를 입력할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
        Field(memo, { memo = it }, "메모", singleLine = false)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(favorite, { favorite = it }); Text("즐겨찾기에 추가") }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(enabled = !saving, onClick = {
            val today = LocalDate.now().toString()
            val validDate = purchaseDate.isBlank() || runCatching { LocalDate.parse(purchaseDate).toString() == purchaseDate && purchaseDate <= today }.getOrDefault(false)
            error = when { picked == null && existing == null -> "사진을 선택하거나 촬영해 주세요."; name.isBlank() -> "이름을 입력해 주세요."; category.isBlank() -> "종류를 선택해 주세요."; selectedSeasons.isEmpty() -> "계절을 하나 이상 선택해 주세요."; !validDate -> "오늘까지의 날짜를 YYYY-MM-DD 형식으로 입력해 주세요."; else -> null }
            if (error == null) {
                saving = true
                val fields = Clothing(name = name.trim(), photoPath = "", category = category, subcategory = subcategory.takeIf(String::isNotBlank), seasons = listOf("봄", "여름", "가을", "겨울").filter { it in selectedSeasons }.joinToString(","), color = color.takeIf(String::isNotBlank), material = material.takeIf(String::isNotBlank), purchaseDate = purchaseDate.takeIf(String::isNotBlank), favorite = favorite, memo = memo.takeIf(String::isNotBlank))
                vm.save(picked, existing, fields) { result -> saving = false; result.onSuccess { picked = null; onSaved() }.onFailure { error = it.message ?: "저장에 실패했습니다." } }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "저장 중…" else "저장") }
    }
}

@Composable private fun Field(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = singleLine)
}

@Composable private fun ClothingImage(path: String, modifier: Modifier = Modifier) {
    val filesDir = LocalContext.current.filesDir
    val bitmap by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(if (File(path).isAbsolute) path else File(filesDir, "clothing/$path").absolutePath) }.getOrNull() } }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Default.Checkroom, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun Placeholder(title: String, message: String) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(message) } } }

@Composable private fun SmartClosetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF607D68), onPrimary = androidx.compose.ui.graphics.Color.White, secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE9EFE8), background = androidx.compose.ui.graphics.Color(0xFFFAFAF7), surface = androidx.compose.ui.graphics.Color(0xFFFAFAF7)), content = content)
}
