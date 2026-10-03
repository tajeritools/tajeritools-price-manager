package ir.tajeritools.pricemanager

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.roundToLong

data class DocItem(
    val id: String,
    val brand: String,
    val name: String,
    val path: String,
    val mime: String,
    val text: String
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
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingBrand by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (pendingBrand.isBlank()) {
            message = "نام برند را وارد کنید."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            val added = mutableListOf<DocItem>()
            for (uri in uris) {
                runCatching { importDocument(context, uri, pendingBrand.trim()) }
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
            listOf("جستجو", "فایل‌ها", "فرمول", "PDF").forEachIndexed { i, t ->
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
                        if (!p.code.isNullOrBlank()) Text("کد: ${p.code}")
                        Text("قیمت فایل: ${formatPrice(p.rawPrice)}")
                        formulas[p.brand]?.let { f ->
                            runCatching { applyFormula(p.rawPrice, f) }.getOrNull()?.let {
                                Text("قیمت نهایی: ${formatPrice(it)} تومان")
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
            label = { Text("نام برند") },
            placeholder = { Text("مثلاً Anchor / آنکور") },
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
    var formula by remember { mutableStateOf("") }
    var test by remember { mutableStateOf("10000000") }
    var preview by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("فرمول‌ها مرحله‌به‌مرحله اجرا می‌شوند و خروجی آخر = قیمت نهایی")\n        Text("مثال Anchor: price-7%=price*7=price/8=price+10%")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(brand, { brand = it }, label = { Text("برند") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(formula, { formula = it }, label = { Text("فرمول") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(test, { test = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("قیمت آزمایشی") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                preview = runCatching {
                    "قیمت نهایی: " + formatPrice(applyFormula(test.toDouble(), formula)) + " تومان"
                }.getOrElse { "فرمول نامعتبر" }
            }) { Text("آزمایش") }
            Button(onClick = {
                if (brand.isNotBlank() && formula.isNotBlank()) onSave(brand.trim(), formula.trim())
            }) { Text("ذخیره") }
        }
        if (preview.isNotBlank()) Text(preview, modifier = Modifier.padding(vertical = 8.dp))
        Divider()
        formulas.forEach { (b, f) ->
            Text("$b : $f", modifier = Modifier.padding(vertical = 5.dp))
        }
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
        Text("فرمول: ${formulas[brand] ?: "بدون فرمول"}")
        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val finalRows = rows.map {
                    val final = formulas[brand]?.let { f ->
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
                val final = formulas[brand]?.let { f -> runCatching { applyFormula(p.rawPrice, f) }.getOrDefault(p.rawPrice) } ?: p.rawPrice
                Text("${p.name} ${p.code.orEmpty()} — قیمت نهایی: ${formatPrice(final)} تومان", Modifier.padding(vertical = 5.dp))
            }
        }
    }
}

suspend fun importDocument(context: Context, uri: Uri, brand: String): DocItem = withContext(Dispatchers.IO) {
    val name = queryName(context, uri) ?: "file_${System.currentTimeMillis()}"
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val dir = File(context.filesDir, "uploads").apply { mkdirs() }
    val outFile = File(dir, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._آ-ی-]"), "_")}")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }

    val text = when {
        mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdfText(outFile)
        mime.startsWith("image/") -> extractImageText(outFile)
        name.endsWith(".csv", true) || mime.contains("csv") || mime.startsWith("text/") -> outFile.readText()
        else -> runCatching { outFile.readText() }.getOrDefault("")
    }

    DocItem(
        id = "${System.currentTimeMillis()}-${name.hashCode()}",
        brand = brand,
        name = name,
        path = outFile.absolutePath,
        mime = mime,
        text = text
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

fun extractProducts(doc: DocItem): List<ProductLine> {
    val out = mutableListOf<ProductLine>()
    val priceRegex = Regex("""(?<!\w)([0-9۰-۹][0-9۰-۹٬,/.]{2,})(?!\w)""")
    val codeRegex = Regex("""\b(?:[A-Za-z]{1,8}[-_ ]?\d{1,8}[A-Za-z0-9-]*|\d{3,6}[A-Za-z]?)\b""")
    for (raw in doc.text.lines()) {
        val line = raw.replace(Regex("\\s+"), " ").trim()
        if (line.length < 4) continue
        val matches = priceRegex.findAll(line).toList()
        if (matches.isEmpty()) continue
        val priceMatch = matches.maxByOrNull { it.value.length } ?: continue
        val price = parseNumber(priceMatch.value) ?: continue
        if (price < 1000) continue
        val code = codeRegex.find(line)?.value?.replace(" ", "")
        val name = (line.removeRange(priceMatch.range)).trim(' ', '-', ':', '،').ifBlank { line }
        out += ProductLine(doc.brand, name.take(180), code, price, line)
    }
    return out.distinctBy { "${it.brand}|${it.code}|${it.name}|${it.rawPrice}" }
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

fun applyFormula(input: Double, formula: String): Double {
    var value = input
    val steps = formula.split("=").map { it.trim() }.filter { it.isNotBlank() }
    for (raw in steps) {
        var step = normalize(raw).replace("قیمت", "price").replace(" ", "")
        if (step.startsWith("price")) step = step.removePrefix("price")
        if (step.isBlank()) continue
        val m = Regex("""^([+\-*/])([0-9.]+)(%)?$""").matchEntire(step)
            ?: error("مرحله نامعتبر: $raw")
        val op = m.groupValues[1]
        val num = m.groupValues[2].toDouble()
        val pct = m.groupValues[3] == "%"
        val amount = if (pct) value * num / 100.0 else num
        value = when (op) {
            "+" -> value + amount
            "-" -> value - amount
            "*" -> if (pct) value * (num / 100.0) else value * num
            "/" -> {
                val d = if (pct) num / 100.0 else num
                require(d != 0.0) { "تقسیم بر صفر" }
                value / d
            }
            else -> value
        }
    }
    return value
}

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
        val code = product.code?.takeIf { !normalize(product.name).contains(normalize(it)) }?.let { " $it" } ?: ""
        val line = "${product.name}$code   قیمت نهایی: ${formatPrice(finalPrice)} تومان"
        val shown = if (line.length > 85) line.take(82) + "…" else line
        canvas.drawText(shown, pageWidth - margin, y, paint)
        y += 24f
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
                o.optString("text")
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
