package com.smartcloset

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class TryOnConfig(val enabled: Boolean, val provider: String, val maxBytes: Long, val retentionHours: Int, val message: String?)
data class TryOnJob(val id: String, val status: String, val isMock: Boolean, val message: String?, val resultUrl: String?)
class TryOnApiException(val statusCode: Int, message: String) : IllegalStateException(message)

/** Per-install server session, encrypted with an Android Keystore AES key. */
class TryOnSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("tryon_session", Context.MODE_PRIVATE)
    private val alias = "smart-closet-tryon-session"
    fun read(): String? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val cipherText = Base64.decode(prefs.getString("token", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()
    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray())
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(alias, null)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}

class TryOnApi(private val baseUrl: String, private val session: TryOnSessionStore) {
    val configured get() = baseUrl.startsWith("https://") || BuildConfig.DEBUG && baseUrl.startsWith("http://")
    fun config(): TryOnConfig = request("GET", "/v1/try-on/config").let {
        TryOnConfig(it.optBoolean("enabled"), it.optString("provider"), it.optLong("max_image_bytes", 7L * 1024 * 1024), it.optInt("retention_hours", 24), it.optString("message").takeUnless(String::isBlank)
            ?.takeUnless { value -> value == "null" })
    }
    fun ensureSession() {
        if (session.read() != null) return
        val response = request("POST", "/v1/sessions")
        session.write(response.getString("access_token"))
    }
    fun jobs(): List<TryOnJob> = request("GET", "/v1/try-on/jobs", auth = true).getJSONArray("jobs").let { array ->
        (0 until array.length()).map { parseJob(array.getJSONObject(it)) }
    }
    fun byKey(key: String): TryOnJob = parseJob(request("GET", "/v1/try-on/jobs/by-key/$key", auth = true))
    fun submit(key: String, person: File, garment: File): TryOnJob {
        val boundary = "Closet-${UUID.randomUUID()}"
        val connection = open("POST", "/v1/try-on/jobs", auth = true).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Idempotency-Key", key); doOutput = true; setChunkedStreamingMode(16 * 1024)
        }
        connection.outputStream.use { out ->
            fun part(name: String, file: File) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }; out.write("\r\n".toByteArray())
            }
            part("person_image", person); part("clothing_image", garment); out.write("--$boundary--\r\n".toByteArray())
        }
        return parseJob(readJson(connection))
    }
    fun job(id: String): TryOnJob = parseJob(request("GET", "/v1/try-on/jobs/$id", auth = true))
    fun downloadResult(id: String, destination: File) {
        val c = open("GET", "/v1/try-on/jobs/$id/result", auth = true)
        if (c.responseCode !in 200..299) throw IllegalStateException("결과 이미지를 가져오지 못했습니다. (${c.responseCode})")
        c.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    }
    fun delete(id: String) { request("DELETE", "/v1/try-on/jobs/$id", auth = true) }
    private fun parseJob(json: JSONObject) = TryOnJob(json.getString("request_id"), json.getString("status"), json.optBoolean("is_mock"), json.optString("message").takeUnless { it.isBlank() || it == "null" }, json.optString("result_url").takeUnless { it.isBlank() || it == "null" })
    private fun request(method: String, path: String, auth: Boolean = false): JSONObject {
        val c = open(method, path, auth)
        if (method != "GET" && method != "DELETE") c.doOutput = true
        return readJson(c)
    }
    private fun open(method: String, path: String, auth: Boolean): HttpURLConnection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10_000; readTimeout = 210_000; useCaches = false; instanceFollowRedirects = false
        if (auth) session.read()?.let { setRequestProperty("Authorization", "Bearer $it") }
    }
    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(); c.disconnect()
        if (code !in 200..299 && code != 204) {
            if (code == 401) session.clear()
            val detail = runCatching { JSONObject(body).optJSONObject("detail")?.optString("message") }.getOrNull()
            throw TryOnApiException(code, detail?.takeUnless { it.isNullOrBlank() } ?: "서버 요청에 실패했습니다. (HTTP $code)")
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
