package ir.tajeritools.pricemanager

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val TAJERI_GATEWAY_BASE = "https://tajeritools.ir/wp-json/tajeritools/v1"

data class LicenseStatus(
    val active: Boolean,
    val phone: String = "",
    val expiresAt: String = "",
    val usedToday: Int = 0,
    val maxDaily: Int = 0,
    val aiModel: String = "",
    val message: String = ""
)

private fun licensePrefs(context: Context) =
    context.getSharedPreferences("tajeri_license", Context.MODE_PRIVATE)

fun loadLicenseToken(context: Context): String =
    licensePrefs(context).getString("token", "").orEmpty()

fun loadLicensePhone(context: Context): String =
    licensePrefs(context).getString("phone", "").orEmpty()

fun clearLicense(context: Context) {
    licensePrefs(context).edit().remove("token").remove("phone").apply()
}

fun deviceId(context: Context): String {
    val prefs = licensePrefs(context)
    val existing = prefs.getString("device_id", "").orEmpty()
    if (existing.isNotBlank()) return existing
    val id = UUID.randomUUID().toString()
    prefs.edit().putString("device_id", id).apply()
    return id
}

private fun gatewayRequest(
    path: String,
    method: String = "POST",
    token: String = "",
    body: JSONObject? = null,
    readTimeoutMs: Int = 70_000
): JSONObject {
    val connection = (URL("$TAJERI_GATEWAY_BASE/$path").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = readTimeoutMs
        setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    if (body != null) {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }
    val http = connection.responseCode
    val raw = (if (http in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    val json = runCatching { JSONObject(raw) }
        .getOrElse { JSONObject().put("message", raw.take(500)) }

    if (http !in 200..299) {
        val message = json.optString("message")
            .ifBlank { json.optString("error") }
            .ifBlank { "Server HTTP $http" }
        error(message)
    }
    return json
}

fun activateLicense(context: Context, phone: String, code: String): LicenseStatus {
    val json = gatewayRequest(
        "activate",
        body = JSONObject()
            .put("phone", phone.trim())
            .put("code", code.trim())
            .put("device_id", deviceId(context))
            .put("app_version", "2.2.0")
    )
    val token = json.optString("token")
    require(token.isNotBlank()) { "توکن فعال‌سازی دریافت نشد." }

    val savedPhone = json.optString("phone", phone.trim())
    licensePrefs(context).edit()
        .putString("token", token)
        .putString("phone", savedPhone)
        .apply()

    return LicenseStatus(
        active = true,
        phone = savedPhone,
        expiresAt = json.optString("expires_at"),
        maxDaily = json.optInt("max_daily", 0),
        aiModel = json.optString("ai_model"),
        message = "مجوز فعال شد."
    )
}

fun checkLicense(context: Context): LicenseStatus {
    val token = loadLicenseToken(context)
    if (token.isBlank()) return LicenseStatus(false, message = "مجوزی روی این گوشی فعال نشده است.")

    return try {
        val json = gatewayRequest(
            "status",
            method = "GET",
            token = token,
            body = null,
            readTimeoutMs = 20_000
        )
        LicenseStatus(
            active = json.optBoolean("ok", false),
            phone = json.optString("phone"),
            expiresAt = json.optString("expires_at"),
            usedToday = json.optInt("used_today", 0),
            maxDaily = json.optInt("max_daily", 0),
            aiModel = json.optString("ai_model"),
            message = "مجوز فعال است."
        )
    } catch (e: Exception) {
        LicenseStatus(
            active = false,
            phone = loadLicensePhone(context),
            message = e.message.orEmpty()
        )
    }
}

fun analyzeWithGateway(
    token: String,
    fileName: String,
    bytes: ByteArray,
    mimeType: String,
    pageStart: Int,
    brandHint: String
): String {
    val payload = JSONObject()
        .put("file", Base64.encodeToString(bytes, Base64.NO_WRAP))
        .put("mime_type", mimeType)
        .put("page", pageStart)
        .put("brand_hint", brandHint)
        .put("source_name", fileName)

    val json = gatewayRequest(
        "analyze",
        token = token,
        body = payload,
        readTimeoutMs = 70_000
    )
    return (json.optJSONObject("result")
        ?: error("پاسخ AI فاقد نتیجه ساختاری است.")).toString()
}

fun analyzePdfAnySizeWithGateway(
    token: String,
    fileName: String,
    file: File,
    brandHint: String
): String {
    val merged = JSONObject()
        .put("brand", "")
        .put("products", JSONArray())
    val mergedProducts = merged.getJSONArray("products")
    val tempDir = File(file.parentFile ?: File("."), "gateway_chunks").apply { mkdirs() }

    PDDocument.load(file).use { source ->
        var start = 0
        val pagesPerChunk = 4

        while (start < source.numberOfPages) {
            val end = minOf(start + pagesPerChunk, source.numberOfPages)
            val chunkFile = File(tempDir, "gateway_${start}_${end}.pdf")
            try {
                PDDocument().use { chunk ->
                    for (i in start until end) chunk.importPage(source.getPage(i))
                    chunk.save(chunkFile)
                }

                val chunkJson = analyzeWithGateway(
                    token = token,
                    fileName = "$fileName | original pages ${start + 1}-$end",
                    bytes = chunkFile.readBytes(),
                    mimeType = "application/pdf",
                    pageStart = start + 1,
                    brandHint = brandHint
                )
                val o = JSONObject(chunkJson)
                if (merged.optString("brand").isBlank()) {
                    merged.put("brand", o.optString("brand"))
                }

                val arr = o.optJSONArray("products") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val localPage = p.optInt("page", 0)
                    if (localPage in 1..pagesPerChunk) {
                        p.put("page", localPage + start)
                    }
                    mergedProducts.put(p)
                }
            } finally {
                chunkFile.delete()
            }
            start = end
        }
    }

    tempDir.delete()

    val seen = mutableSetOf<String>()
    val deduped = JSONArray()
    for (i in 0 until mergedProducts.length()) {
        val p = mergedProducts.optJSONObject(i) ?: continue
        val key = listOf(
            normalize(p.optString("code")),
            normalize(p.optString("name")),
            p.optDouble("price", -1.0).toLong().toString()
        ).joinToString("|")
        if (seen.add(key)) deduped.put(p)
    }
    merged.put("products", deduped)
    return merged.toString()
}

fun analyzeImageWithGateway(
    token: String,
    fileName: String,
    file: File,
    mimeType: String,
    brandHint: String
): String = analyzeWithGateway(
    token = token,
    fileName = fileName,
    bytes = file.readBytes(),
    mimeType = when {
        mimeType.contains("png", true) -> "image/png"
        mimeType.contains("webp", true) -> "image/webp"
        else -> "image/jpeg"
    },
    pageStart = 1,
    brandHint = brandHint
)

@Composable
fun LicenseScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf(loadLicensePhone(context)) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<LicenseStatus?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            status = withContext(Dispatchers.IO) { checkLicense(context) }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (loadLicenseToken(context).isNotBlank()) refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text("مجوز و AI ابری TajeriTools", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "این بخش بدون کامپیوتر کار می‌کند. کلید AI فقط روی سرور TajeriTools می‌ماند. مجوز به شماره موبایل و دستگاه متصل می‌شود و مدیر می‌تواند دسترسی را هر زمان قطع کند.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch == '+' } },
            label = { Text("شماره موبایل") },
            placeholder = { Text("0912...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() } },
            label = { Text("کد فعال‌سازی") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        Button(
            enabled = !busy && phone.length >= 10 && code.length >= 4,
            onClick = {
                scope.launch {
                    busy = true
                    status = withContext(Dispatchers.IO) {
                        runCatching { activateLicense(context, phone, code) }
                            .getOrElse {
                                LicenseStatus(false, phone = phone, message = it.message.orEmpty())
                            }
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "در حال بررسی…" else "فعال‌سازی")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !busy && loadLicenseToken(context).isNotBlank(),
            onClick = { refresh() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("بررسی وضعیت مجوز") }

        status?.let { s ->
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (s.active) "وضعیت: فعال" else "وضعیت: غیرفعال",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (s.phone.isNotBlank()) Text("موبایل: ${s.phone}")
                    if (s.aiModel.isNotBlank()) Text("AI: ${s.aiModel}")
                    if (s.maxDaily > 0) Text("مصرف امروز: ${s.usedToday} از ${s.maxDaily}")
                    if (s.expiresAt.isNotBlank() && s.expiresAt != "null") {
                        Text("انقضا: ${s.expiresAt}")
                    }
                    if (s.message.isNotBlank()) Text(s.message)
                }
            }
        }

        if (loadLicenseToken(context).isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = {
                    clearLicense(context)
                    status = LicenseStatus(false, message = "مجوز از این گوشی خارج شد.")
                }
            ) { Text("خروج از مجوز این گوشی") }
        }

        Spacer(Modifier.height(16.dp))
        Text("ترتیب هوش مصنوعی برنامه", style = MaterialTheme.typography.titleMedium)
        Text("1) AI ابری TajeriTools: Mistral Document AI، با Gemini 3.8 Flash به‌عنوان fallback سرور")
        Text("2) PaddleOCR‑VL قبلی، اگر سرور محلی‌اش تنظیم باشد")
        Text("3) Gemini قبلی با API Key شخصی، اگر تنظیم باشد")
        Text("4) parser محلی برنامه")
    }
}
