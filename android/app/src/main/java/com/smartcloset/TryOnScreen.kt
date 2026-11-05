package com.smartcloset

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcloset.data.Clothing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TryOnScreen(vm: ClosetViewModel, initialTopId: Long?, onOpenClothing: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clothes by vm.allClothes.collectAsStateWithLifecycle()
    val tops = clothes.filter { it.category == "상의" }
    val prefs = remember { context.getSharedPreferences("tryon_local", 0) }
    val session = remember { TryOnSessionStore(context) }
    val api = remember { TryOnApi(BuildConfig.TRYON_API_BASE_URL, session) }
    var personPath by remember { mutableStateOf(prefs.getString("person_path", null)) }
    var pickedTopId by remember { mutableStateOf<Long?>(initialTopId?.takeIf { id -> tops.any { it.id == id } }) }
    var config by remember { mutableStateOf<TryOnConfig?>(null) }
    var job by remember { mutableStateOf<TryOnJob?>(null) }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var personBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val resultDir = remember { File(context.filesDir, "tryon/results").apply { mkdirs() } }
    LaunchedEffect(initialTopId, tops) {
        if (pickedTopId == null) initialTopId?.takeIf { id -> tops.any { it.id == id } }?.let { pickedTopId = it }
    }
    val pickPerson = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            val normalized = runCatching { withContext(Dispatchers.IO) { normalizePerson(context, uri) } }
            if (normalized.isSuccess) {
                val priorJobDeleted = job?.let { current -> runCatching { withContext(Dispatchers.IO) { api.delete(current.id) } }.isSuccess }
                if (priorJobDeleted == true) {
                    prefs.edit().remove("last_idempotency_key").apply()
                    job = null; resultBitmap = null
                } else if (priorJobDeleted == false) {
                    error = "기존 서버 작업 삭제에 실패했습니다. 새 요청 전 작업 삭제를 다시 시도하세요."
                }
                val file = normalized.getOrThrow()
                personPath?.let { File(it).delete() }
                personPath = file.absolutePath; prefs.edit().putString("person_path", file.absolutePath).apply()
                personBitmap = BitmapFactory.decodeFile(file.absolutePath)
            } else error = normalized.exceptionOrNull()?.message ?: "전신 사진을 읽지 못했습니다."
            busy = false
        }
    }
    val saveResultDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) {
            val id = job?.id
            if (id != null) scope.launch {
                runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { output -> File(resultDir, "$id.image").inputStream().use { it.copyTo(output) } } ?: error("결과 파일을 저장하지 못했습니다.") } }
                    .onSuccess { error = "결과 이미지를 선택한 위치에 저장했습니다." }
                    .onFailure { error = it.message ?: "결과 파일을 저장하지 못했습니다." }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!api.configured) { config = TryOnConfig(false, "서버 미설정", 7L * 1024 * 1024, 24, "앱 빌드에 서버 주소가 없습니다.") ; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { api.config() } }
            .onSuccess { config = it; runCatching { withContext(Dispatchers.IO) {
                api.ensureSession()
                val key = prefs.getString("last_idempotency_key", null)
                if (key != null) runCatching { api.byKey(key) }.getOrNull() else null
            } }.onSuccess { restored -> if (restored != null) job = restored else runCatching { withContext(Dispatchers.IO) { api.jobs().firstOrNull() } }.onSuccess { job = it } } }
            .onFailure { error = it.message ?: "서버 설정을 조회하지 못했습니다." }
    }
    LaunchedEffect(job?.id, job?.status) {
        val current = job ?: return@LaunchedEffect
        if (current.status == "succeeded") {
            val file = File(resultDir, "${current.id}.image")
            if (!file.exists()) runCatching { withContext(Dispatchers.IO) { api.downloadResult(current.id, file) } }
                .onFailure { error = "결과 보관 기간이 끝났거나 다운로드에 실패했습니다." }
            resultBitmap = BitmapFactory.decodeFile(file.absolutePath)
            return@LaunchedEffect
        }
        if (current.status !in setOf("queued", "processing")) return@LaunchedEffect
        while (true) {
            delay(2500)
            val updated = runCatching { withContext(Dispatchers.IO) { api.job(current.id) } }.getOrNull() ?: continue
            job = updated
            if (updated.status == "succeeded") break
            if (updated.status !in setOf("queued", "processing")) break
        }
    }

    val selectedTop = tops.firstOrNull { it.id == pickedTopId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AI 전신 착용 이미지", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("전신 사진 1장과 옷장에 등록된 상의 1장을 사용합니다. 상의만 바꾸는 스타일 참고 이미지이며 실제 핏이나 착용 결과를 보장하지 않습니다.")
        OutlinedButton(enabled = !busy, onClick = { pickPerson.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (personPath == null) "전신 사진 선택" else "전신 사진 변경") }
        personBitmap?.let { Image(it.asImageBitmap(), "선택한 전신 사진", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
        if (personPath != null) TextButton(onClick = {
            job?.let { current -> scope.launch { runCatching { withContext(Dispatchers.IO) { api.delete(current.id) } }.onSuccess { job = null; resultBitmap = null; prefs.edit().remove("last_idempotency_key").apply() }.onFailure { error = it.message } } }
            File(personPath!!).delete(); personPath = null; personBitmap = null; prefs.edit().remove("person_path").apply()
        }) { Text("전신 사진 삭제") }
        if (tops.isEmpty()) {
            Text("등록된 상의가 없습니다. AI 착용 이미지를 만들려면 먼저 상의를 등록하세요.")
        } else {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(selectedTop?.name ?: "상의 선택", {}, Modifier.fillMaxWidth().menuAnchor(), readOnly = true, label = { Text("등록된 상의") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) { tops.forEach { top -> DropdownMenuItem(text = { Text(top.name) }, onClick = { pickedTopId = top.id; expanded = false }) } }
            }
            selectedTop?.let { top ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClothingPreview(top, Modifier.size(112.dp)); Spacer(Modifier.width(12.dp))
                    Column { Text(top.name, fontWeight = FontWeight.SemiBold); TextButton(onClick = { onOpenClothing(top.id) }) { Text("의류 상세정보") } }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("외부 전송과 처리 안내", fontWeight = FontWeight.Bold)
                val mock = config?.provider == "연동 테스트 모드"
                Text(if (mock) "선택한 두 사진은 테스트를 위해 이 앱의 백엔드로 전송됩니다. 현재 개발용 Mock은 외부 이미지 생성 서비스로 보내지 않고 실제 AI 결과가 아닌 테스트 안내 카드만 반환합니다. 서버는 입력·결과 파일을 최대 ${config?.retentionHours ?: 24}시간 보관 후 정리합니다."
                    else "선택한 전신 사진과 상의 사진을 결과 생성 목적으로 앱이 연결된 서버를 거쳐 ${config?.provider ?: "설정된 제공사"}에 전송합니다. 서버는 입력·결과를 최대 ${config?.retentionHours ?: 24}시간 보관 후 정리합니다. Google은 입력을 모델 학습에 사용하지 않는다고 안내하지만, 안전성 감지에 걸린 요청은 악용 모니터링을 위해 최대 90일 보관될 수 있습니다. 앱에서 해당 제공사 보관 자료를 직접 삭제할 수 없습니다.")
                if (!mock && config?.provider == "Google Cloud Virtual Try-On") Text("2026-11-05 확인된 공식 가격은 결과 이미지 1장당 미화 $0.06이며 실제 청구액은 프로젝트·지역·세금·가격 변경에 따라 달라질 수 있습니다. 선택 모델은 폐기 상태이며 2027-03-15 지원 종료 예정입니다.", style = MaterialTheme.typography.bodySmall)
                Text("본인 사진 또는 사용 허락을 받은 사진만 사용하세요. 생성 결과는 스타일 참고용이며 얼굴·신체·배경·상의 표현이 달라질 수 있고 실제 사이즈나 핏을 보장하지 않습니다.")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("위 내용을 확인했고 외부 전송에 동의합니다.") }
            }
        }
        val waiting = job?.status in setOf("queued", "processing", "unknown")
        val ready = config?.enabled == true && personPath != null && selectedTop != null && consent && !busy && !waiting
        Button(enabled = ready, onClick = {
            val person = File(personPath!!); val top = selectedTop!!
            val clothing = File(File(context.filesDir, "clothing"), top.photoPath)
            if (!person.isFile || !clothing.isFile) { error = "선택한 사진을 읽을 수 없습니다. 사진을 다시 선택하거나 의류 정보를 확인하세요."; return@Button }
            if (person.length() > (config?.maxBytes ?: 0) || clothing.length() > (config?.maxBytes ?: 0)) { error = "사진이 제공사 용량 제한을 넘었습니다. 더 작은 사진을 선택하거나 의류 사진을 다시 등록하세요."; return@Button }
            busy = true; error = null; consent = false
            val oldKey = prefs.getString("last_idempotency_key", null)
            val idempotencyKey = if (job == null && oldKey != null) oldKey else UUID.randomUUID().toString()
            prefs.edit().putString("last_idempotency_key", idempotencyKey).apply()
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    api.ensureSession()
                    try { api.byKey(idempotencyKey) }
                    catch (failure: TryOnApiException) {
                        if (failure.statusCode == 404) api.submit(idempotencyKey, person, clothing) else throw failure
                    }
                } }
                    .onSuccess { job = it }
                    .onFailure { error = it.message ?: "요청 결과를 확인하지 못했습니다. 상태 확인 후 새 작업을 요청하세요." }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "요청 처리 중…" else "동의하고 이미지 생성 요청") }
        if (config?.enabled == false) Text(config?.message ?: "서버 또는 AI 제공사 설정이 없어 생성 요청을 사용할 수 없습니다.", color = MaterialTheme.colorScheme.error)
        job?.let { current ->
            Text("작업 상태: ${when (current.status) { "queued" -> "대기 중"; "processing" -> "생성 중"; "succeeded" -> "완료"; "unknown" -> "처리 여부 확인 불가"; "failed" -> "실패"; "expired" -> "보관 기간 만료"; else -> current.status }}")
            if (current.status == "unknown") Text("제공사에서 요청을 처리했는지 확인할 수 없습니다. 자동으로 다시 제출하지 않았습니다. 작업을 삭제한 뒤 새 요청을 선택할 수 있습니다.")
            current.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (current.isMock) Text("연동 테스트용 결과 — 실제 AI 생성 아님", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            resultBitmap?.let { Image(it.asImageBitmap(), "AI 착용 결과", Modifier.fillMaxWidth().heightIn(max = 500.dp), contentScale = ContentScale.Fit) }
            if (current.status == "succeeded" && resultBitmap != null) {
                OutlinedButton(onClick = { saveResultDocument.launch("smart-closet-try-on-${current.id}.jpg") }) { Text("이미지 파일로 저장") }
            }
            Text("결과는 앱 전용 저장소에 보관됩니다. 파일로 내보내기는 사용자가 선택할 때만 진행합니다. 화면을 닫아도 서버 작업은 자동 취소되지 않습니다. 서버 보관 이미지는 작업 삭제 버튼으로 요청할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { api.delete(current.id) } }.onSuccess { job = null; resultBitmap = null; File(resultDir, "${current.id}.image").delete(); prefs.edit().remove("last_idempotency_key").apply() }.onFailure { error = it.message } } }) { Text("서버 작업·보관 이미지 삭제") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun ClothingPreview(clothing: Clothing, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(clothing.photoPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clothing.photoPath) { bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(File(context.filesDir, "clothing"), clothing.photoPath).absolutePath) } }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Icon(Icons.Default.Checkroom, null, modifier)
}

private suspend fun normalizePerson(context: android.content.Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (true) { val read = input.read(buffer); if (read < 0) break; total += read; require(total <= 40 * 1024 * 1024) { "사진 원본이 너무 큽니다. 40 MiB 이하 사진을 선택하세요." }; output.write(buffer, 0, read) }
        output.toByteArray()
    } ?: error("사진 파일을 읽을 수 없습니다.")
    val orientation = runCatching { ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하는 사진 파일(JPEG/PNG)이 아닙니다." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("사진 파일을 읽을 수 없습니다.")
    val matrix = android.graphics.Matrix().apply { when (orientation) {
        2 -> postScale(-1f, 1f); 3 -> postRotate(180f); 4 -> postScale(1f, -1f)
        5 -> { postRotate(90f); postScale(-1f, 1f) }; 6 -> postRotate(90f)
        7 -> { postRotate(270f); postScale(-1f, 1f) }; 8 -> postRotate(270f)
    } }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val scale = minOf(1f, 2048f / maxOf(rotated.width, rotated.height))
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true) else rotated
    val output = ByteArrayOutputStream(); sized.compress(Bitmap.CompressFormat.JPEG, 88, output)
    bitmap.recycle(); if (rotated !== bitmap) rotated.recycle(); if (sized !== rotated) sized.recycle()
    require(output.size() <= 7 * 1024 * 1024) { "사진이 제공사 7 MiB 제한을 넘습니다." }
    val target = File(context.filesDir, "tryon/person-${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs(); writeBytes(output.toByteArray()) }
    target
}

