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

data class PaddleAnalysis(
    val text: String,
    val aiJson: String,
    val productCount: Int
)

fun normalizePaddleBaseUrl(value: String): String {
    val v = value.trim().trimEnd('/')
    if (v.isBlank()) return ""
    require(v.startsWith("http://") || v.startsWith("https://")) {
        "آدرس Paddle باید با http:// یا https:// شروع شود"
    }
    return v
}

fun savePaddleServerUrl(context: Context, value: String) {
    context.getSharedPreferences("tajeri", Context.MODE_PRIVATE)
        .edit().putString("paddle_server_url", value.trim().trimEnd('/')).apply()
}

fun loadPaddleServerUrl(context: Context): String =
    context.getSharedPreferences("tajeri", Context.MODE_PRIVATE)
        .getString("paddle_server_url", "").orEmpty()

fun testPaddleServer(baseUrl: String): String {
    val base = normalizePaddleBaseUrl(baseUrl)
    require(base.isNotBlank()) { "آدرس سرور وارد نشده است" }
    val connection = (URL("$base/health").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 7_000
        readTimeout = 10_000
    }
    val code = connection.responseCode
    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    require(code in 200..299) { "Paddle HTTP $code: ${body.take(200)}" }
    return if (body.isBlank()) "اتصال برقرار است" else body.take(160)
}

fun paddleLayoutRequest(baseUrl: String, bytes: ByteArray, fileType: Int): JSONObject {
    val base = normalizePaddleBaseUrl(baseUrl)
    val payload = JSONObject().apply {
        put("file", Base64.encodeToString(bytes, Base64.NO_WRAP))
        put("fileType", fileType)
        put("useDocOrientationClassify", true)
        put("useDocUnwarping", false)
        put("useLayoutDetection", true)
        put("formatBlockContent", true)
        put("restructurePages", false)
        put("returnMarkdownImages", false)
        put("visualize", false)
        put("temperature", 0.0)
    }

    val connection = (URL("$base/layout-parsing").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 30_000
        readTimeout = 180_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }
    connection.outputStream.use {
        it.write(payload.toString().toByteArray(Charsets.UTF_8))
    }
    val code = connection.responseCode
    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    require(code in 200..299) { "Paddle HTTP $code: ${body.take(400)}" }

    val root = JSONObject(body)
    require(root.optInt("errorCode", 0) == 0) {
        root.optString("errorMsg").ifBlank { "Paddle processing error" }
    }
    return root
}

fun extractPaddlePageTexts(response: JSONObject): List<String> {
    val result = response.optJSONObject("result") ?: return emptyList()
    val arr = result.optJSONArray("layoutParsingResults") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val markdown = item.optJSONObject("markdown")?.optString("text").orEmpty()
            val fallback = item.optJSONObject("prunedResult")?.toString().orEmpty()
            add(markdown.ifBlank { fallback })
        }
    }
}

fun detectPromotionNearProduct(text: String, product: ProductLine): String {
    val lines = text.lines().map { normalize(it) }
    val code = normalize(product.code.orEmpty())
    val nameWords = normalize(product.name).split(" ").filter { it.length >= 3 }.take(3)
    val idx = lines.indexOfFirst { line ->
        (code.isNotBlank() && line.contains(code)) ||
            (nameWords.isNotEmpty() && nameWords.count { line.contains(it) } >= 2)
    }
    if (idx < 0) return ""
    val nearby = lines.subList((idx - 3).coerceAtLeast(0), (idx + 5).coerceAtMost(lines.size))
        .joinToString(" ")
    val gift = Regex("""(?<!\d)(\d{1,3})\s*(?:\+|به\s*اضافه|خرید)\s*(\d{1,2})(?!\d)""")
        .find(nearby)
    return gift?.let { "${it.groupValues[1]}+${it.groupValues[2]}" }.orEmpty()
}

fun productsToAiJson(brand: String, pageRows: List<Pair<Int, ProductLine>>, pageTexts: Map<Int, String>): String {
    val arr = JSONArray()
    pageRows.forEach { (page, p) ->
        if (p.priceUnit == "unknown") return@forEach
        arr.put(JSONObject().apply {
            put("name", p.name)
            put("code", p.code.orEmpty())
            put("price", p.sourcePrice)
            put("price_unit", p.priceUnit)
            put("price_type", p.priceType)
            put("page", page)
            put("confidence", 0.90)
            put("promotion", detectPromotionNearProduct(pageTexts[page].orEmpty(), p))
            put("evidence", "PaddleOCR-VL page $page")
        })
    }
    return JSONObject()
        .put("brand", brand)
        .put("products", arr)
        .put("engine", "PaddleOCR-VL")
        .toString()
}

fun analyzePdfWithPaddleServer(
    baseUrl: String,
    file: File,
    brandHint: String,
    sourceName: String,
    pagesPerChunk: Int = 4
): PaddleAnalysis {
    val allText = StringBuilder()
    val pageRows = mutableListOf<Pair<Int, ProductLine>>()
    val pageTexts = linkedMapOf<Int, String>()

    PDDocument.load(file).use { source ->
        var start = 0
        while (start < source.numberOfPages) {
            val end = minOf(start + pagesPerChunk, source.numberOfPages)
            val temp = File(file.parentFile, "paddle_${start}_${end}_${System.nanoTime()}.pdf")
            try {
                PDDocument().use { chunk ->
                    for (i in start until end) chunk.importPage(source.getPage(i))
                    chunk.save(temp)
                }

                val response = paddleLayoutRequest(baseUrl, temp.readBytes(), 0)
                val pageMarkdown = extractPaddlePageTexts(response)

                pageMarkdown.forEachIndexed { localIndex, pageText ->
                    val globalPage = start + localIndex + 1
                    val marked = "\n--- PADDLE PAGE $globalPage ---\n$pageText\n"
                    allText.append(marked)
                    pageTexts[globalPage] = pageText

                    val textRows = extractProductsFromTextBlocks(
                        brandHint,
                        pageText,
                        "$sourceName • Paddle page $globalPage"
                    )
                    val codeFirst = extractProductsFromCodeFirstBlocks(
                        brandHint,
                        pageText,
                        "$sourceName • Paddle page $globalPage"
                    )
                    val rows = (textRows + codeFirst).distinctBy {
                        "${it.code}|${normalize(it.name)}|${it.rawPrice}"
                    }

                    rows.forEach { row -> pageRows += globalPage to row }
                }
            } finally {
                temp.delete()
            }
            start = end
        }
    }

    val detectedBrand = brandHint.ifBlank { detectBrand("$sourceName\n$allText") }.ifBlank { "نامشخص" }
    val distinct = pageRows.distinctBy {
        "${normalize(it.second.code.orEmpty())}|${normalize(it.second.name)}|${it.second.sourcePrice.toLong()}"
    }
    val aiJson = productsToAiJson(detectedBrand, distinct, pageTexts)
    return PaddleAnalysis(allText.toString(), aiJson, distinct.size)
}

fun analyzeImageWithPaddleServer(
    baseUrl: String,
    file: File,
    brandHint: String,
    sourceName: String
): PaddleAnalysis {
    val response = paddleLayoutRequest(baseUrl, file.readBytes(), 1)
    val text = extractPaddlePageTexts(response).joinToString("\n")
    val brand = brandHint.ifBlank { detectBrand("$sourceName\n$text") }.ifBlank { "نامشخص" }
    val rows = (
        extractProductsFromTextBlocks(brand, text, "$sourceName • Paddle") +
            extractProductsFromCodeFirstBlocks(brand, text, "$sourceName • Paddle")
        ).distinctBy { "${it.code}|${normalize(it.name)}|${it.rawPrice}" }
        .map { 1 to it }

    return PaddleAnalysis(
        text = text,
        aiJson = productsToAiJson(brand, rows, mapOf(1 to text)),
        productCount = rows.size
    )
}

@Composable
fun PaddleScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(loadPaddleServerUrl(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text("PaddleOCR‑VL", style = MaterialTheme.typography.titleLarge)
        Text(
            "موتور رایگان خواندن PDF و جدول. وقتی آدرس سرور ذخیره باشد، هنگام واردکردن PDF/عکس اول PaddleOCR‑VL اجرا می‌شود.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("آدرس سرور PaddleOCR‑VL") },
            placeholder = { Text("مثلاً http://192.168.1.20:8080") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                runCatching {
                    val normalized = normalizePaddleBaseUrl(url)
                    savePaddleServerUrl(context, normalized)
                    status = if (normalized.isBlank()) "Paddle غیرفعال شد." else "آدرس Paddle ذخیره شد."
                }.onFailure { status = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ذخیره") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    status = withContext(Dispatchers.IO) {
                        runCatching { testPaddleServer(url) }
                            .fold(
                                onSuccess = { "اتصال موفق: $it" },
                                onFailure = { "اتصال ناموفق: ${it.message}" }
                            )
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "در حال تست…" else "تست اتصال") }

        if (status.isNotBlank()) {
            Text(status, Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("ترتیب پردازش:", style = MaterialTheme.typography.titleMedium)
        Text("1) PaddleOCR‑VL برای PDF/عکس")
        Text("2) استخراج محلی مدل/نام/قیمت از خروجی ساختاری Paddle")
        Text("3) اگر نتیجه کافی نبود و Gemini فعال بود، Gemini به‌عنوان fallback")
        Text("4) در نبود هر دو، parser محلی برنامه")
        Spacer(Modifier.height(8.dp))
        Text(
            "برای فایل‌های بزرگ، برنامه PDF را ۴ صفحه‌۴ صفحه پردازش می‌کند.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
