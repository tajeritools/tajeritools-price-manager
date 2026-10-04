package ir.tajeritools.pricemanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

data class DocItem(
    val id: String,
    val brand: String,
    val name: String,
    val path: String,
    val mime: String,
    val text: String,
    val aiJson: String = ""
)

data class ProductLine(
    val brand: String,
    val name: String,
    val code: String?,
    val rawPrice: Double,
    val source: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(loadDocs(context)) }
    var formulas by remember { mutableStateOf(loadFormulas(context)) }
    var apiKey by remember { mutableStateOf(loadAiKey(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingBrand by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val added = mutableListOf<DocItem>()
            for (uri in uris) {
                runCatching { importDocument(context, uri, pendingBrand.trim(), apiKey) }
                    .onSuccess { added += it }
                    .onFailure { message = "خطا در خواندن فایل: ${it.message}" }
            }
            docs = docs + added
            saveDocs(context, docs)
            busy = false
            if (added.isNotEmpty()) message = "${added.size} فایل اضافه شد."
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "TajeriTools Price Manager",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )
        TabRow(selectedTabIndex = tab) {
            listOf("جستجو", "فایل‌ها", "فرمول", "PDF", "AI").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        message?.let {
            AssistChip(
                onClick = { message = null },
                label = { Text(it) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        when (tab) {
            0 -> SearchScreen(docs, formulas)
            1 -> FilesScreen(
                docs = docs,
                brand = pendingBrand,
                onBrand = { pendingBrand = it },
                onAdd = {
                    picker.launch(arrayOf(
                        "application/pdf",
                        "image/*",
                        "text/plain",
                        "text/csv",
                        "application/octet-stream"
                    ))
                },
                onDelete = { item ->
                    runCatching { File(item.path).delete() }
                    docs = docs.filterNot { it.id == item.id }
                    saveDocs(context, docs)
                }
            )
            2 -> FormulaScreen(formulas) { brand, formula ->
                formulas = formulas.toMutableMap().apply { put(brand, formula) }
                saveFormulas(context, formulas)
                message = "فرمول $brand ذخیره شد."
            }
            3 -> PdfScreen(docs, formulas)
            4 -> AiScreen(
                apiKey = apiKey,
                onSave = {
                    apiKey = it.trim()
                    saveAiKey(context, apiKey)
                    message = if (apiKey.isBlank()) "کلید AI پاک شد." else "کلید Gemini ذخیره شد."
                }
            )
        }
    }
}

@Composable
fun SearchScreen(docs: List<DocItem>, formulas: Map<String, String>) {
    var q by remember { mutableStateOf("") }
    val products = remember(docs) { docs.flatMap(::extractProducts) }
    val results = if (q.isBlank()) emptyList() else {
        val needle = normalize(q)
        products.filter {
            normalize("${it.brand} ${it.code.orEmpty()} ${it.name} ${it.source}").contains(needle)
        }.take(100)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("نام، کد، برند یا مشخصه") },
            placeholder = { Text("مثلاً DCE12 یا دریل شارژی آنکور") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("برند: ${p.brand}")
                        if (!p.code.isNullOrBlank()) Text("مدل/کد: ${p.code}")
                        Text("قیمت فایل: ${formatPrice(p.rawPrice)} تومان")
                        formulaForBrand(formulas, p.brand)?.let { f ->
                            runCatching { applyFormulaSteps(p.rawPrice, f) }.getOrNull()?.let { steps ->
                                Text("قیمت نهایی: ${formatPrice(steps.last().second)} تومان")
                                Text(formatFormulaTrace(p.rawPrice, steps), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(p.source.take(260), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun FilesScreen(
    docs: List<DocItem>,
    brand: String,
    onBrand: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (DocItem) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = brand,
            onValueChange = onBrand,
            label = { Text("برند اختیاری") },
            placeholder = { Text("خالی بگذارید تا برنامه خودش تشخیص دهد") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("افزودن PDF / عکس / CSV / متن")
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(docs, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall)
                            Text("${d.brand} • ${d.mime}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (d.text.isBlank()) "متن قابل استخراج پیدا نشد" else "${d.text.length} نویسه استخراج شد",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { onDelete(d) }) { Text("حذف") }
                    }
                }
            }
        }
    }
}

@Composable
fun FormulaScreen(
    formulas: Map<String, String>,
    onSave: (String, String) -> Unit
) {
    var brand by remember { mutableStateOf("") }
    var supplierDiscount by remember { mutableStateOf("18") }
    var giftBuy by remember { mutableStateOf("0") }
    var giftFree by remember { mutableStateOf("0") }
    var extraCosts by remember { mutableStateOf("0") }
    var targetMargin by remember { mutableStateOf("10") }
    var roundTo by remember { mutableStateOf("10000") }
    var test by remember { mutableStateOf("20000000") }
    var formula by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }

    fun buildProfessionalFormula(): String {
        val d = supplierDiscount.toDoubleOrNull() ?: 0.0
        val x = giftBuy.toIntOrNull() ?: 0
        val y = giftFree.toIntOrNull() ?: 0
        val costs = extraCosts.toDoubleOrNull() ?: 0.0
        val margin = targetMargin.toDoubleOrNull() ?: 0.0
        val rounding = roundTo.toLongOrNull() ?: 1L
        val parts = mutableListOf<String>()
        if (d > 0) parts += "discount(${trimNumber(d)})"
        if (x > 0 && y > 0) parts += "gift($x,$y)"
        if (costs > 0) parts += "cost(${trimNumber(costs)})"
        if (margin > 0) parts += "margin(${trimNumber(margin)})"
        if (rounding > 1) parts += "round($rounding)"
        return parts.joinToString("=")
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text("قیمت‌گذاری حرفه‌ای", style = MaterialTheme.typography.titleLarge)
        Text(
            "قیمت لیست → تخفیف تأمین‌کننده → اشانتیون → هزینه جانبی → حاشیه سود هدف → گردکردن",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            brand, { brand = it },
            label = { Text("برند") },
            placeholder = { Text("مثلاً Arva") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                supplierDiscount,
                { supplierDiscount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("تخفیف خرید %") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                extraCosts,
                { extraCosts = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("هزینه جانبی %") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                giftBuy,
                { giftBuy = it.filter(Char::isDigit) },
                label = { Text("خرید X") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                giftFree,
                { giftFree = it.filter(Char::isDigit) },
                label = { Text("اشانتیون Y") },
                modifier = Modifier.weight(1f)
            )
        }
        Text("مثال 10+1 یعنی خرید 10 عدد و دریافت 1 عدد رایگان.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                targetMargin,
                { targetMargin = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("حاشیه سود هدف %") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                roundTo,
                { roundTo = it.filter(Char::isDigit) },
                label = { Text("گردکردن به") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                formula = buildProfessionalFormula()
                preview = "فرمول ساخته شد: $formula"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ساخت فرمول حرفه‌ای") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                supplierDiscount = "18"
                giftBuy = "0"
                giftFree = "0"
                extraCosts = "0"
                targetMargin = "0"
                roundTo = "1"
                formula = "discount(18)=markup(10)"
                preview = "فرمول قبلی: 18٪ تخفیف سپس 10٪ سود روی هزینه"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("استفاده از فرمول قبلی (-18% سپس +10%)") }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            test,
            { test = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '/' || ch == ',' || ch == '٬' } },
            label = { Text("قیمت آزمایشی") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (formula.isBlank()) formula = buildProfessionalFormula()
                preview = runCatching {
                    val base = parseNumber(test) ?: error("قیمت نامعتبر")
                    val steps = applyFormulaSteps(base, formula)
                    "قیمت لیست: ${formatPrice(base)} تومان\n" +
                        formatFormulaTrace(base, steps) +
                        "\nقیمت نهایی: ${formatPrice(steps.last().second)} تومان"
                }.getOrElse { "خطا در فرمول: ${it.message}" }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("تست محاسبه") }

        if (preview.isNotBlank()) {
            Text(preview, modifier = Modifier.padding(vertical = 10.dp))
        }

        Button(
            onClick = {
                if (formula.isBlank()) formula = buildProfessionalFormula()
                if (brand.isNotBlank() && formula.isNotBlank()) onSave(brand.trim(), formula)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ذخیره برای این برند") }

        Spacer(Modifier.height(14.dp))
        Divider()
        Text("فرمول‌های ذخیره‌شده", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        formulas.forEach { (b, f) ->
            Text("$b : $f", modifier = Modifier.padding(vertical = 5.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun AiScreen(apiKey: String, onSave: (String) -> Unit) {
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("هوش مصنوعی Gemini", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("اگر کلید Gemini API را وارد کنید، هنگام ورود فایل، AI برند و ردیف‌های محصول را از متن PDF تشخیص می‌دهد. قیمت حدس زده نمی‌شود و باید در خود فایل وجود داشته باشد.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Gemini API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSave(key) }, modifier = Modifier.fillMaxWidth()) {
            Text("ذخیره تنظیمات AI")
        }
        Spacer(Modifier.height(8.dp))
        Text(if (key.isBlank()) "AI غیرفعال است؛ تشخیص محلی برند و استخراج معمولی انجام می‌شود." else "AI فعال است. مدل: gemini-3.8-flash")
    }
}

@Composable
fun PdfScreen(docs: List<DocItem>, formulas: Map<String, String>) {
    val context = LocalContext.current
    val products = remember(docs) { docs.flatMap(::extractProducts) }
    val brands = products.map { it.brand }.distinct()
    var brand by remember { mutableStateOf(brands.firstOrNull().orEmpty()) }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (brands.isEmpty()) {
            Text("ابتدا فایل قیمت‌دار اضافه کنید.")
            return@Column
        }
        Text("برند خروجی", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            brands.take(6).forEach { b ->
                FilterChip(selected = b == brand, onClick = { brand = b }, label = { Text(b) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val rows = products.filter { it.brand == brand }
        Text("${rows.size} ردیف قیمت پیدا شد.")
        Text("فرمول: ${formulaForBrand(formulas, brand) ?: "تنظیم نشده — ابتدا در تب فرمول ذخیره کنید"}")
        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = formulaForBrand(formulas, brand) != null,
            onClick = {
                val finalRows = rows.filter { isMeaningfulProductName(it.name) && !isLikelyYearCode(it.code.orEmpty()) }.map {
                    val final = formulaForBrand(formulas, brand)?.let { f ->
                        runCatching { applyFormula(it.rawPrice, f) }.getOrDefault(it.rawPrice)
                    } ?: it.rawPrice
                    it to final
                }
                runCatching {
                    val file = makePricePdf(context, brand, finalRows)
                    sharePdf(context, file)
                }.onSuccess {
                    status = "PDF ساخته شد و پنجره اشتراک باز شد."
                }.onFailure {
                    status = "خطا: ${it.message}"
                }
            }
        ) { Text("ساخت و اشتراک PDF") }
        if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(rows.take(50)) { p ->
                val f = formulaForBrand(formulas, brand)
                val steps = f?.let { runCatching { applyFormulaSteps(p.rawPrice, it) }.getOrNull() }
                val final = steps?.lastOrNull()?.second ?: p.rawPrice
                Column(Modifier.padding(vertical = 7.dp)) {
                    Text("دستگاه: ${p.name}")
                    if (!p.code.isNullOrBlank()) Text("مدل/کد: ${p.code}")
                    Text("قیمت فایل: ${formatPrice(p.rawPrice)} تومان")
                    if (steps != null) {
                        Text("محاسبه: ${formatFormulaTrace(p.rawPrice, steps)}")
                        Text("قیمت نهایی: ${formatPrice(final)} تومان")
                    }
                }
            }
        }
    }
}

suspend fun importDocument(context: Context, uri: Uri, brandOverride: String, apiKey: String): DocItem = withContext(Dispatchers.IO) {
    val name = queryName(context, uri) ?: "file_${System.currentTimeMillis()}"
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val dir = File(context.filesDir, "uploads").apply { mkdirs() }
    val outFile = File(dir, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._آ-ی-]"), "_")}")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }

    val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
    val text = when {
        isPdf -> runCatching { extractPdfTextSmart(outFile) }.getOrDefault("")
        mime.startsWith("image/") -> runCatching { extractImageText(outFile) }.getOrDefault("")
        name.endsWith(".csv", true) || mime.contains("csv") || mime.startsWith("text/") -> runCatching { outFile.readText() }.getOrDefault("")
        else -> runCatching { outFile.readText() }.getOrDefault("")
    }

    val localBrand = brandOverride.ifBlank { detectBrand("$name\n$text") }
    val aiJson = if (apiKey.isNotBlank()) {
        when {
            isPdf -> runCatching { analyzePdfAnySizeWithGemini(apiKey, name, outFile) }.getOrDefault("")
            text.isNotBlank() -> runCatching { analyzeWithGemini(apiKey, name, text) }.getOrDefault("")
            else -> ""
        }
    } else ""
    val aiBrand = parseAiBrand(aiJson)
    val finalBrand = brandOverride.ifBlank { aiBrand.ifBlank { localBrand } }.ifBlank { "نامشخص" }

    DocItem(
        id = "${System.currentTimeMillis()}-${name.hashCode()}",
        brand = finalBrand,
        name = name,
        path = outFile.absolutePath,
        mime = mime,
        text = text,
        aiJson = aiJson
    )
}

fun queryName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return uri.lastPathSegment
}

fun extractPdfText(file: File): String {
    PDDocument.load(file).use { doc ->
        return PDFTextStripper().getText(doc).orEmpty()
    }
}

fun extractPdfTextSmart(file: File): String {
    val embedded = runCatching { extractPdfText(file) }.getOrDefault("")
    val useful = embedded.count { it.isLetterOrDigit() }
    if (useful >= 250) return embedded
    val ocr = runCatching { extractScannedPdfText(file) }.getOrDefault("")
    return listOf(embedded, ocr).filter { it.isNotBlank() }.joinToString("\n")
}

fun extractScannedPdfText(file: File): String {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        buildString {
            val pages = minOf(renderer.pageCount, 80)
            for (index in 0 until pages) {
                renderer.openPage(index).use { page ->
                    val width = 1800
                    val scale = width.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                    if (result.text.isNotBlank()) {
                        append("\n--- PAGE ${index + 1} OCR ---\n")
                        append(result.text)
                    }
                    bitmap.recycle()
                }
            }
        }
    } finally {
        recognizer.close()
        renderer.close()
        pfd.close()
    }
}

fun extractImageText(file: File): String {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        result.text.orEmpty()
    } finally {
        recognizer.close()
    }
}

fun repairProductName(value: String): String {
    val normalized = normalize(value)
        .replace(Regex("""\s+"""), " ")
        .trim()
    val tokens = normalized.split(" ").filter { it.isNotBlank() }
    val singleRatio = if (tokens.isEmpty()) 0.0 else tokens.count { it.length == 1 && it[0].isLetter() }.toDouble() / tokens.size
    if (singleRatio < 0.55) return value.replace(Regex("""\s+"""), " ").trim()

    val compact = tokens.joinToString("")
    val known = listOf(
        "دستگاهجوشکاری" to "دستگاه جوشکاری",
        "دریلچکشی" to "دریل چکشی",
        "دریلشارژیچکشی" to "دریل شارژی چکشی",
        "دریلشارژی" to "دریل شارژی",
        "پیچگوشتیبرقی" to "پیچ گوشتی برقی",
        "فرزآهنگری" to "فرز آهنگری",
        "فرزسنگبری" to "فرز سنگبری",
        "مینفرز" to "مینی فرز",
        "مینیفرز" to "مینی فرز",
        "فرزحکاکی" to "فرز حکاکی",
        "اورفرز" to "اور فرز",
        "بتنکنشارژی" to "بتن کن شارژی",
        "بتنکن" to "بتن کن",
        "چکشتخریب" to "چکش تخریب",
        "ارهگردبرشارژی" to "اره گردبر شارژی",
        "ارهگردبر" to "اره گردبر",
        "ارهعمودبر" to "اره عمودبر",
        "ارهزنجیریشارژی" to "اره زنجیری شارژی",
        "ارهزنجیریبنزینی" to "اره زنجیری بنزینی",
        "فارسیبر" to "فارسی بر",
        "پروفیلبر" to "پروفیل بر",
        "سنبادهلرزان" to "سنباده لرزان",
        "سشوارصنعتی" to "سشوار صنعتی",
        "پیستولهبرقی" to "پیستوله برقی",
        "بلوورشارژی" to "بلوور شارژی",
        "بلوور" to "بلوور",
        "دمندهبرقی" to "دمنده برقی",
        "همزنبرقی" to "همزن برقی",
        "شیارزن" to "شیارزن",
        "کارواششارژی" to "کارواش شارژی",
        "کارواشپرتابل" to "کارواش پرتابل",
        "کارواش" to "کارواش",
        "موتوربرقبنزینی" to "موتور برق بنزینی",
        "کمپرسورباد" to "کمپرسور باد",
        "کمپرسورفندکی" to "کمپرسور فندکی",
        "میخکوببادی" to "میخ کوب بادی",
        "منگنهکوببادی" to "منگنه کوب بادی",
        "آچاربکسشارژی" to "آچار بکس شارژی",
        "آچارجغجغهای" to "آچار جغجغه‌ای",
        "قیچیباغبانیشارژی" to "قیچی باغبانی شارژی",
        "جاروشارژی" to "جارو شارژی",
        "ترازلیزری" to "تراز لیزری",
        "مترلیزری" to "متر لیزری"
    )
    val hit = known.firstOrNull { compact.contains(it.first) }
    return hit?.second ?: compact
}

fun isLikelyYearCode(value: String): Boolean {
    val n = normalize(value).filter(Char::isDigit)
    if (n.length != 4) return false
    val y = n.toIntOrNull() ?: return false
    return y in 1300..1500 || y in 2000..2100
}

fun isMeaningfulProductName(value: String): Boolean {
    val s = normalize(value)
        .replace(Regex("""(?:13|14|20)\d{2}[/.-]\d{1,2}[/.-]\d{1,2}"""), " ")
        .replace(Regex("""https?://\S+|www\.\S+|\S+\.com\S*"""), " ")
        .replace(Regex("""\b\d{5,}\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val letters = s.count { it.isLetter() }
    if (letters < 2) return false
    val banned = listOf("لیست قیمت", "tajeritools", "arvatools", "قیمت نهایی", "تومان")
    return banned.none { s == normalize(it) }
}

fun cleanProductName(line: String, priceToken: String): String {
    return line
        .replace(priceToken, " ")
        .replace(Regex("""(?:13|14|20)\d{2}[/.-]\d{1,2}[/.-]\d{1,2}"""), " ")
        .replace(Regex("""(?:قیمت\s*نهایی|قیمت|تومان|ریال)[:：]?"""), " ")
        .replace(Regex("""https?://\S+|www\.\S+|\S+\.com\S*"""), " ")
        .replace(Regex("""\b\d{1,3}\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '،', '|')
}

fun extractProducts(doc: DocItem): List<ProductLine> {
    parseAiProducts(doc)?.filter { isMeaningfulProductName(it.name) }?.takeIf { it.isNotEmpty() }?.let { return it }

    val lines = doc.text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
    val out = mutableListOf<ProductLine>()
    val numberRegex = Regex("""(?<!\w)([0-9۰-۹][0-9۰-۹٬,./]{2,})(?!\w)""")
    val dateRegex = Regex("""^(?:13|14|20)\d{2}[/.-]\d{1,2}[/.-]\d{1,2}$""")
    val codeRegex = Regex("""\b(?:[A-Za-z]{1,10}[-_ ]?\d{1,10}[A-Za-z0-9-]*|\d{4,6}[A-Za-z]?)\b""")

    for (index in lines.indices) {
        val line = lines[index]
        if (line.length < 4) continue

        val candidates = numberRegex.findAll(line).mapNotNull { m ->
            val token = normalize(m.value)
            if (dateRegex.matches(token)) return@mapNotNull null
            val value = parseNumber(token) ?: return@mapNotNull null
            val digits = token.count(Char::isDigit)
            if (value < 100_000 || digits < 6) return@mapNotNull null
            var score = 0
            if (token.contains(",") || token.contains("٬") || token.contains("/")) score += 3
            if (digits >= 7) score += 3
            if (Regex("""(?:قیمت|تومان|ریال|price)""", RegexOption.IGNORE_CASE).containsMatchIn(line)) score += 5
            Triple(m, value, score)
        }.toList()

        val picked = candidates.maxWithOrNull(
            compareBy<Triple<MatchResult, Double, Int>> { it.third }.thenBy { it.first.value.length }
        ) ?: continue

        val priceMatch = picked.first
        val price = picked.second
        var name = repairProductName(cleanProductName(line, priceMatch.value))

        // PDF tables sometimes separate the description from its price column.
        // Only borrow an adjacent line when the current row has no usable product name.
        if (!isMeaningfulProductName(name)) {
            val adjacent = listOfNotNull(
                lines.getOrNull(index - 1),
                lines.getOrNull(index + 1)
            ).map { cleanProductName(it, "") }
             .firstOrNull { isMeaningfulProductName(it) }
            if (adjacent != null) name = repairProductName(adjacent)
        }

        // A wrong name is worse than omitting the row from the customer-facing price list.
        if (!isMeaningfulProductName(name)) continue

        val code = codeRegex.findAll("$name $line")
            .map { it.value.replace(" ", "") }
            .firstOrNull { candidate ->
                if (isLikelyYearCode(candidate)) false else {
                    val n = parseNumber(candidate)
                    n == null || n < 100_000
                }
            }

        out += ProductLine(doc.brand, name.take(160), code, price, line)
    }

    return out.distinctBy { "${it.brand}|${it.code}|${normalize(it.name)}|${it.rawPrice}" }
}

fun detectBrand(value: String): String {
    val s = normalize(value)
    val brands = listOf(
        "Ronix" to listOf("ronix", "رونیکس"),
        "Tosan" to listOf("tosan", "توسن"),
        "Anchor" to listOf("anchor", "آنکور", "انکر"),
        "Nova" to listOf("nova", "نووا"),
        "Arva" to listOf("arva", "آروا"),
        "Pukka" to listOf("pukka", "پوکا"),
        "Vivarex" to listOf("vivarex", "ویوارکس")
    )
    return brands.firstOrNull { (_, keys) -> keys.any { s.contains(normalize(it)) } }?.first.orEmpty()
}

fun parseAiBrand(aiJson: String): String {
    if (aiJson.isBlank()) return ""
    return runCatching { JSONObject(aiJson).optString("brand").trim() }.getOrDefault("")
}

fun parseAiProducts(doc: DocItem): List<ProductLine>? {
    if (doc.aiJson.isBlank()) return null
    return runCatching {
        val root = JSONObject(doc.aiJson)
        val brand = root.optString("brand").ifBlank { doc.brand }
        val arr = root.optJSONArray("products") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val name = repairProductName(p.optString("name").trim())
                val code = p.optString("code").trim()
                    .takeIf { it.isNotBlank() && !isLikelyYearCode(it) }
                val price = p.optDouble("price", Double.NaN)
                if (name.isNotBlank() && price.isFinite() && price >= 50_000) {
                    add(ProductLine(brand, name, code, price, "AI: ${doc.name}"))
                }
            }
        }
    }.getOrNull()
}

fun analyzeWithGemini(apiKey: String, fileName: String, text: String): String {
    val clipped = text.take(50000)
    val prompt = """
You analyze Iranian tool-store price lists.
Return ONLY valid JSON with this exact shape:
{"brand":"brand name","products":[{"name":"product name","code":"model/code or empty","price":123456}]}

Rules:
- Detect brand from the filename and document text.
- Extract only real product rows that contain a price in the supplied document.\n- Every returned product MUST include a specific product name; include its model/code whenever visible.\n- Never return a row whose only description is a date, quantity, page number, or generic placeholder.\n- Never invent a price, product, model, or brand.
- Preserve the numeric price semantically; remove thousands separators only.
- If uncertain about a row, omit it.
- Common brands include Ronix, Tosan, Anchor, Nova, Arva, Pukka, Vivarex.
Filename: $fileName
Document text:
$clipped
""".trimIndent()

    val request = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        }))
        put("generationConfig", JSONObject().apply {
            put("responseMimeType", "application/json")
            put("temperature", 0.0)
        })
    }

    val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 60000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("x-goog-api-key", apiKey)
    }
    connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
    val httpCode = connection.responseCode
    val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    require(httpCode in 200..299) { "Gemini HTTP $httpCode: ${body.take(300)}" }
    val root = JSONObject(body)
    return root.getJSONArray("candidates")
        .getJSONObject(0)
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")
        .trim()
}

fun analyzePdfAnySizeWithGemini(apiKey: String, fileName: String, file: File): String {
    val inlineLimit = 18L * 1024L * 1024L
    if (file.length() <= inlineLimit) return analyzePdfWithGemini(apiKey, fileName, file)

    val merged = JSONObject().apply {
        put("brand", "")
        put("products", JSONArray())
    }
    val mergedProducts = merged.getJSONArray("products")
    val tempDir = File(file.parentFile ?: file.parentFile, "ai_chunks").apply { mkdirs() }

    PDDocument.load(file).use { source ->
        var start = 0
        while (start < source.numberOfPages) {
            val end = minOf(start + 4, source.numberOfPages)
            val chunkFile = File(tempDir, "chunk_${start}_${end}.pdf")
            PDDocument().use { chunk ->
                for (i in start until end) {
                    chunk.importPage(source.getPage(i))
                }
                chunk.save(chunkFile)
            }

            val chunkJson = runCatching {
                analyzePdfWithGemini(apiKey, "$fileName صفحات ${start + 1}-$end", chunkFile)
            }.getOrDefault("")

            if (chunkJson.isNotBlank()) {
                runCatching {
                    val o = JSONObject(chunkJson)
                    if (merged.optString("brand").isBlank()) merged.put("brand", o.optString("brand"))
                    val arr = o.optJSONArray("products") ?: JSONArray()
                    for (i in 0 until arr.length()) mergedProducts.put(arr.getJSONObject(i))
                }
            }
            chunkFile.delete()
            start = end
        }
    }
    tempDir.delete()
    return merged.toString()
}

fun analyzePdfWithGemini(apiKey: String, fileName: String, file: File): String {
    val prompt = """
You analyze Iranian tool-store price-list PDFs.
Return ONLY valid JSON:
{"brand":"brand name","products":[{"name":"product name","code":"model/code or empty","price":123456}]}
Rules:
- Read the PDF itself, including scanned pages and tables.
- Extract only actual product sale-price rows.\n- Every returned row MUST contain the specific product name and model/code when visible in the table.\n- If the name/model cannot be tied confidently to the price, omit that row.\n- Never treat model codes, dates, page numbers, phone numbers, percentages or quantities as prices.
- Never invent data.
- Preserve the actual document price; remove separators only.
- Omit uncertain rows.
Filename: $fileName
""".trimIndent()
    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    val parts = JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", encoded)))
    val request = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.0))
    }
    val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 30000; readTimeout = 120000; doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("x-goog-api-key", apiKey)
    }
    connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
    val httpCode = connection.responseCode
    val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    require(httpCode in 200..299) { "Gemini HTTP $httpCode: ${body.take(300)}" }
    return JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
}
fun normalize(value: String): String {
    val fa = "۰۱۲۳۴۵۶۷۸۹"
    var s = value.lowercase(Locale.ROOT).replace('ي', 'ی').replace('ك', 'ک')
    fa.forEachIndexed { i, c -> s = s.replace(c, ('0'.code + i).toChar()) }
    return s.replace(Regex("\\s+"), " ").trim()
}

fun parseNumber(value: String): Double? {
    val n = normalize(value).replace("٬", "").replace(",", "").replace("/", "")
    return Regex("\\d+(?:\\.\\d+)?").find(n)?.value?.toDoubleOrNull()
}

fun formulaForBrand(formulas: Map<String, String>, brand: String): String? {
    formulas[brand]?.let { return it }
    val canonical = detectBrand(brand)
    return formulas.entries.firstOrNull { (k, _) -> normalize(k) == normalize(brand) || (canonical.isNotBlank() && detectBrand(k) == canonical) }?.value
}

fun applyFormulaSteps(input: Double, formula: String): List<Pair<String, Double>> {
    var value = input
    val tokens = formula.split("=")
        .map { normalize(it).replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", "") }
        .filter { it.isNotBlank() }

    return buildList {
        for (token0 in tokens) {
            val token = token0.replace("قیمت", "price")

            Regex("""discount\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)?.let { m ->
                val pct = m.groupValues[1].toDouble()
                require(pct in 0.0..99.99) { "تخفیف نامعتبر" }
                value *= (1.0 - pct / 100.0)
                add(("تخفیف ${trimNumber(pct)}٪") to value)
                continue
            }

            Regex("""gift\((\d+),(\d+)\)""").matchEntire(token)?.let { m ->
                val buyQty = m.groupValues[1].toInt()
                val freeQty = m.groupValues[2].toInt()
                require(buyQty > 0 && freeQty > 0) { "اشانتیون نامعتبر" }
                value *= buyQty.toDouble() / (buyQty + freeQty).toDouble()
                add(("اشانتیون $buyQty+$freeQty") to value)
                continue
            }

            Regex("""cost\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)?.let { m ->
                val pct = m.groupValues[1].toDouble()
                require(pct >= 0.0) { "هزینه جانبی نامعتبر" }
                value *= (1.0 + pct / 100.0)
                add(("هزینه جانبی ${trimNumber(pct)}٪") to value)
                continue
            }

            Regex("""margin\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)?.let { m ->
                val pct = m.groupValues[1].toDouble()
                require(pct >= 0.0 && pct < 100.0) { "حاشیه سود نامعتبر" }
                value /= (1.0 - pct / 100.0)
                add(("حاشیه سود ${trimNumber(pct)}٪") to value)
                continue
            }

            Regex("""markup\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)?.let { m ->
                val pct = m.groupValues[1].toDouble()
                require(pct >= 0.0) { "سود روی هزینه نامعتبر" }
                value *= (1.0 + pct / 100.0)
                add(("سود روی هزینه ${trimNumber(pct)}٪") to value)
                continue
            }

            Regex("""round\((\d+)\)""").matchEntire(token)?.let { m ->
                val step = m.groupValues[1].toDouble()
                require(step > 0) { "گردکردن نامعتبر" }
                value = ceil(value / step) * step
                add(("گردکردن ${step.toLong()}") to value)
                continue
            }

            var opText = token
            if (opText.startsWith("price")) opText = opText.removePrefix("price")
            val old = Regex("""^([+\-*/])([0-9]+(?:\.[0-9]+)?)(%)?$""").matchEntire(opText)
            if (old != null) {
                val op = old.groupValues[1]
                val num = old.groupValues[2].toDouble()
                val pct = old.groupValues[3] == "%"
                val before = value
                value = when (op) {
                    "+" -> if (pct) before + before * num / 100.0 else before + num
                    "-" -> if (pct) before - before * num / 100.0 else before - num
                    "*" -> if (pct) before * (num / 100.0) else before * num
                    "/" -> {
                        val d = if (pct) num / 100.0 else num
                        require(d != 0.0) { "تقسیم بر صفر" }
                        before / d
                    }
                    else -> before
                }
                add(("$op${trimNumber(num)}${if (pct) "%" else ""}") to value)
            }
        }
        require(isNotEmpty()) { "هیچ مرحله قابل محاسبه‌ای پیدا نشد" }
    }
}
fun applyFormula(input: Double, formula: String): Double = applyFormulaSteps(input, formula).last().second

fun formatFormulaTrace(input: Double, steps: List<Pair<String, Double>>): String = buildString {
    append(formatPrice(input))
    for ((op, result) in steps) { append(" → "); append(op); append(" = "); append(formatPrice(result)) }
}

fun trimNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

fun formatPrice(v: Double): String = DecimalFormat("#,###").format(v.roundToLong()).replace(",", "٬")

fun makePricePdf(
    context: Context,
    brand: String,
    rows: List<Pair<ProductLine, Double>>
): File {
    val pdf = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 36f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        textAlign = Paint.Align.RIGHT
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 19f
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }

    var pageNo = 1
    var y = 70f
    var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
    var canvas = page.canvas
    canvas.drawText("لیست قیمت $brand - TajeriTools", pageWidth - margin, 42f, titlePaint)

    for ((product, finalPrice) in rows) {
        if (y > pageHeight - 50) {
            pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
            canvas = page.canvas
            canvas.drawText("لیست قیمت $brand - TajeriTools", pageWidth - margin, 42f, titlePaint)
            y = 70f
        }
        val title = "دستگاه: ${product.name}"
        val model = product.code?.let { "مدل/کد: $it" }
        val priceLine = "قیمت نهایی: ${formatPrice(finalPrice)} تومان"
        listOfNotNull(title, model, priceLine).forEach { line ->
            val shown = if (line.length > 78) line.take(75) + "…" else line
            canvas.drawText(shown, pageWidth - margin, y, paint)
            y += 20f
        }
        y += 8f
    }
    pdf.finishPage(page)

    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "TajeriTools-${brand.replace(" ", "-")}-price-list.pdf")
    FileOutputStream(file).use { pdf.writeTo(it) }
    pdf.close()
    return file
}

fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "ارسال یا ذخیره قیمت‌نامه"))
}

fun saveDocs(context: Context, docs: List<DocItem>) {
    val arr = JSONArray()
    docs.forEach {
        arr.put(JSONObject().apply {
            put("id", it.id)
            put("brand", it.brand)
            put("name", it.name)
            put("path", it.path)
            put("mime", it.mime)
            put("text", it.text)
            put("aiJson", it.aiJson)
        })
    }
    context.getSharedPreferences("tajeri", Context.MODE_PRIVATE)
        .edit().putString("docs", arr.toString()).apply()
}

fun loadDocs(context: Context): List<DocItem> {
    val raw = context.getSharedPreferences("tajeri", Context.MODE_PRIVATE).getString("docs", "[]") ?: "[]"
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val p = o.optString("path")
            if (!File(p).exists()) null else DocItem(
                o.optString("id"),
                o.optString("brand"),
                o.optString("name"),
                p,
                o.optString("mime"),
                o.optString("text"),
                o.optString("aiJson")
            )
        }
    }.getOrDefault(emptyList())
}

fun saveFormulas(context: Context, formulas: Map<String, String>) {
    val o = JSONObject()
    formulas.forEach { (k, v) -> o.put(k, v) }
    context.getSharedPreferences("tajeri", Context.MODE_PRIVATE)
        .edit().putString("formulas", o.toString()).apply()
}

fun loadFormulas(context: Context): Map<String, String> {
    val raw = context.getSharedPreferences("tajeri", Context.MODE_PRIVATE).getString("formulas", "{}") ?: "{}"
    return runCatching {
        val o = JSONObject(raw)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
}


fun saveAiKey(context: Context, key: String) {
    context.getSharedPreferences("tajeri", Context.MODE_PRIVATE)
        .edit().putString("gemini_api_key", key).apply()
}

fun loadAiKey(context: Context): String =
    context.getSharedPreferences("tajeri", Context.MODE_PRIVATE)
        .getString("gemini_api_key", "").orEmpty()
