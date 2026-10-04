package ir.tajeritools.pricemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class CatalogEntry(
    val doc: DocItem,
    val product: ProductLine,
    val page: Int = 0,
    val promotion: String = "",
    val confidence: Double = 0.0,
    val bbox: IntArray? = null
)

fun catalogMatches(entry: CatalogEntry, query: String): Boolean {
    val needle = normalize(query)
    if (needle.isBlank()) return true
    val p = entry.product
    val haystack = normalize(
        listOf(
            p.brand,
            p.name,
            p.code.orEmpty(),
            p.priceType,
            entry.promotion,
            p.source
        ).joinToString(" ")
    )
    return haystack.contains(needle)
}

fun buildCatalogEntries(docs: List<DocItem>): List<CatalogEntry> {
    val out = mutableListOf<CatalogEntry>()
    for (doc in docs) {
        val products = extractProducts(doc)
        for (p in products) {
            var page = 0
            var promo = ""
            var confidence = 0.0
            var bbox: IntArray? = null

            if (doc.aiJson.isNotBlank()) {
                runCatching {
                    val root = JSONObject(doc.aiJson)
                    val arr = root.optJSONArray("products") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val code = o.optString("code")
                        val name = o.optString("name")
                        val codeMatch = p.code != null && normalize(code) == normalize(p.code.orEmpty())
                        val nameMatch = normalize(name) == normalize(p.name)
                        if (codeMatch || nameMatch) {
                            page = o.optInt("page", 0)
                            promo = o.optString("promotion").trim()
                            confidence = o.optDouble("confidence", 0.0)
                            val b = o.optJSONArray("bbox")
                            if (b != null && b.length() == 4) {
                                val parsed = IntArray(4) { idx -> b.optInt(idx, -1) }
                                if (parsed.all { it in 0..1000 } &&
                                    parsed[2] > parsed[0] &&
                                    parsed[3] > parsed[1]
                                ) bbox = parsed
                            }
                            break
                        }
                    }
                }
            }

            out += CatalogEntry(doc, p, page, promo, confidence, bbox)
        }
    }

    return out.distinctBy {
        normalize(it.product.brand) + "|" +
            normalize(it.product.code.orEmpty()) + "|" +
            normalize(it.product.name)
    }
}

fun cropNormalized(bitmap: Bitmap, bbox: IntArray?): Bitmap {
    if (bbox == null || bbox.size != 4) return bitmap
    val pad = 24
    val top = ((bbox[0] / 1000f) * bitmap.height).roundToInt().minus(pad).coerceAtLeast(0)
    val left = ((bbox[1] / 1000f) * bitmap.width).roundToInt().minus(pad).coerceAtLeast(0)
    val bottom = ((bbox[2] / 1000f) * bitmap.height).roundToInt().plus(pad).coerceAtMost(bitmap.height)
    val right = ((bbox[3] / 1000f) * bitmap.width).roundToInt().plus(pad).coerceAtMost(bitmap.width)
    if (right <= left || bottom <= top) return bitmap
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

fun renderCatalogPreview(doc: DocItem, page: Int, bbox: IntArray?): Bitmap? {
    val file = File(doc.path)
    if (!file.exists()) return null

    if (doc.mime.startsWith("image/")) {
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    if (doc.mime != "application/pdf" && !file.name.endsWith(".pdf", true)) return null
    if (page <= 0) return null

    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    return try {
        if (renderer.pageCount <= 0) return null
        val index = (page - 1).coerceIn(0, renderer.pageCount - 1)
        renderer.openPage(index).use { pdfPage ->
            val width = 1400
            val scale = width.toFloat() / pdfPage.width.toFloat()
            val height = (pdfPage.height * scale).roundToInt().coerceAtLeast(1)
            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pdfPage.render(full, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val cropped = cropNormalized(full, bbox)
            if (cropped !== full) full.recycle()
            cropped
        }
    } finally {
        renderer.close()
        pfd.close()
    }
}

@Composable
private fun CatalogPreview(entry: CatalogEntry) {
    var bitmap by remember(entry.doc.path, entry.page, entry.bbox?.contentHashCode()) {
        mutableStateOf<Bitmap?>(null)
    }
    var loading by remember { mutableStateOf(false) }

    val canPreview = entry.doc.mime.startsWith("image/") || entry.page > 0
    if (!canPreview) {
        Text(
            "برای نمایش تصویر همین محصول، فایل را با AI فعال دوباره وارد کن.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    LaunchedEffect(entry.doc.path, entry.page, entry.bbox?.contentHashCode()) {
        loading = true
        bitmap = withContext(Dispatchers.IO) {
            runCatching { renderCatalogPreview(entry.doc, entry.page, entry.bbox) }.getOrNull()
        }
        loading = false
    }

    if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        bitmap?.let { bm ->
            Image(
                bitmap = bm.asImageBitmap(),
                contentDescription = "تصویر محصول از فایل PDF",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 130.dp, max = 360.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun CatalogScreen(docs: List<DocItem>, formulas: Map<String, String>) {
    var q by remember { mutableStateOf("") }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val entries = remember(docs) { buildCatalogEntries(docs) }
    val filtered = remember(entries, q) {
        entries.filter { catalogMatches(it, q) }.take(150)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("کاتالوگ تصویری محصولات", style = MaterialTheme.typography.titleLarge)
        Text(
            "جستجو بر اساس نام، مدل یا برند؛ نتیجه به قیمت و صفحه اصلی PDF متصل است.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("مثال: دریل، اینورتر، 5304، C2106") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Text("${filtered.size} نتیجه", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = {
                "${it.doc.id}|${it.product.code.orEmpty()}|${it.product.name}"
            }) { entry ->
                val p = entry.product
                val key = "${entry.doc.id}|${p.code.orEmpty()}|${p.name}"
                val expanded = expandedKey == key

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("برند: ${p.brand}")
                        if (!p.code.isNullOrBlank()) Text("مدل/کد: ${p.code}")
                        if (entry.page > 0) Text("صفحه PDF: ${entry.page}")
                        Text("قیمت منبع: ${formatPrice(p.sourcePrice)} ${unitLabel(p.priceUnit)}")
                        if (p.priceUnit == "rial") {
                            Text("مبنای محاسبه: ${formatPrice(p.rawPrice)} تومان")
                        }
                        if (entry.promotion.isNotBlank()) {
                            Text("شرایط/اشانتیون: ${entry.promotion}")
                        }

                        formulaForProduct(formulas, p)?.let { f ->
                            runCatching { applyFormulaSteps(p.rawPrice, f) }.getOrNull()?.let { steps ->
                                Text("قیمت نهایی: ${formatPrice(steps.last().second)} تومان")
                                Text(
                                    formatFormulaTrace(p.rawPrice, steps),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (entry.confidence > 0) {
                            Text(
                                "اطمینان AI: ${(entry.confidence * 100).toInt()}٪",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        TextButton(
                            onClick = { expandedKey = if (expanded) null else key }
                        ) {
                            Text(if (expanded) "بستن تصویر" else "نمایش تصویر از PDF")
                        }

                        if (expanded) CatalogPreview(entry)
                    }
                }
            }
        }
    }
}
