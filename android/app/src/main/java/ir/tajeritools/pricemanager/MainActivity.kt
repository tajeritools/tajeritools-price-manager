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
import java.net.URI
import java.net.URL
import java.net.URLEncoder
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
    val source: String,
    val sourcePrice: Double = rawPrice,
    val priceUnit: String = "toman",
    val priceType: String = "list"
)

data class SitePricePreview(
    val product: ProductLine,
    val finalPrice: Double,
    val wooId: Long?,
    val wooName: String?,
    val currentPrice: Double?,
    val matched: Boolean,
    val error: String? = null
)

data class TrustedSource(
    val brand: String,
    val label: String,
    val url: String,
    val domains: List<String>
)

fun defaultTrustedSources(): List<TrustedSource> = listOf(
    TrustedSource("Ronix", "سایت رسمی رونیکس", "https://www.ronix.ir/", listOf("ronix.ir")),
    TrustedSource("Tosan", "سایت رسمی توسن", "https://tosantools.com/", listOf("tosantools.com")),
    TrustedSource("Anchor", "سایت رسمی PM / Anchor", "https://pmtools.tools/", listOf("pmtools.tools")),
    TrustedSource("Arva", "سایت رسمی آروا", "https://arvatools.com/", listOf("arvatools.com")),
    TrustedSource("Nova", "سایت رسمی نوا", "https://novatech-tools.com/", listOf("novatech-tools.com"))
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
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("جستجو", "کاتالوگ", "فایل‌ها", "فرمول", "PDF", "آنلاین", "سایت", "مجوز", "Paddle", "AI").forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    modifier = Modifier.widthIn(min = 88.dp),
                    text = {
                        Text(
                            text = t,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
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
            1 -> CatalogScreen(docs, formulas)
            2 -> FilesScreen(
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
            3 -> FormulaScreen(
                formulas = formulas,
                onSave = { brand, product, formula ->
                    val key = pricingRuleKey(brand, product)
                    formulas = formulas.toMutableMap().apply { put(key, formula) }
                    saveFormulas(context, formulas)
                    message = if (product.isBlank()) "فرمول پیش‌فرض $brand ذخیره شد." else "فرمول $brand / $product ذخیره شد."
                },
                onDelete = { key ->
                    val removedLabel = key.replace("||", " / ").trim().trimEnd('/')
                    formulas = formulas.toMutableMap().apply { remove(key) }
                    saveFormulas(context, formulas)
                    message = "فرمول $removedLabel حذف شد."
                },
                onDeleteAll = {
                    formulas = emptyMap()
                    saveFormulas(context, formulas)
                    message = "همه فرمول‌ها حذف شدند."
                }
            )
            4 -> PdfScreen(docs, formulas)
            5 -> OnlineSourceScreen(
                apiKey = apiKey,
                onImported = { item ->
                    docs = docs + item
                    saveDocs(context, docs)
                    message = "منبع آنلاین ${item.brand} بروزرسانی شد."
                },
                onMessage = { message = it }
            )
            6 -> SiteSyncScreen(
                docs = docs,
                formulas = formulas,
                onMessage = { message = it }
            )
            7 -> LicenseScreen()
            8 -> PaddleScreen()
            9 -> AiScreen(
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
                        Text("قیمت فایل: ${formatPrice(p.sourcePrice)} ${unitLabel(p.priceUnit)}")
                        if (p.priceUnit == "rial") {
                            Text("مبنای محاسبه: ${formatPrice(p.rawPrice)} تومان")
                        }
                        if (p.priceType.isNotBlank() && p.priceType != "list") {
                            Text("نوع قیمت: ${p.priceType}", style = MaterialTheme.typography.bodySmall)
                        }
                        formulaForProduct(formulas, p)?.let { f ->
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
                            Text(
                                "${if (d.brand == "نامشخص" || d.brand.isBlank()) "برند تشخیص داده نشد" else d.brand} • ${d.mime}",
                                style = MaterialTheme.typography.bodySmall
                            )
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
    onSave: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit
) {
    var brand by remember { mutableStateOf("") }
    var product by remember { mutableStateOf("") }
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
            placeholder = { Text("مثلاً Anchor") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            product, { product = it },
            label = { Text("محصول یا مدل") },
            placeholder = { Text("مثلاً DCE12 یا دریل شارژی؛ خالی = پیش‌فرض برند") },
            modifier = Modifier.fillMaxWidth()
        )
        Text("برای هر مدل می‌توانی اشانتیون جدا تعریف کنی؛ مثلاً 7+1 یا 5+1.", style = MaterialTheme.typography.bodySmall)
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
                if (brand.isNotBlank() && formula.isNotBlank()) onSave(brand.trim(), product.trim(), formula)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (product.isBlank()) "ذخیره پیش‌فرض برند" else "ذخیره برای این محصول/مدل") }

        Spacer(Modifier.height(14.dp))
        Divider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("فرمول‌های ذخیره‌شده", style = MaterialTheme.typography.titleMedium)
            if (formulas.isNotEmpty()) {
                TextButton(onClick = onDeleteAll) {
                    Text("حذف همه")
                }
            }
        }

        if (formulas.isEmpty()) {
            Text(
                "هنوز فرمولی ذخیره نشده است.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        } else {
            formulas.forEach { (k, f) ->
                val parts = k.split("||", limit = 2)
                val label = if (parts.size == 2 && parts[1].isNotBlank()) "${parts[0]} / ${parts[1]}" else parts[0]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(f, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onDelete(k) }) {
                            Text("حذف")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun SiteSyncScreen(
    docs: List<DocItem>,
    formulas: Map<String, String>,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var siteUrl by remember { mutableStateOf("https://tajeritools.ir") }
    var consumerKey by remember { mutableStateOf("") }
    var consumerSecret by remember { mutableStateOf("") }
    var brandFilter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var previews by remember { mutableStateOf<List<SitePricePreview>>(emptyList()) }

    val products = remember(docs, formulas, brandFilter) {
        docs.flatMap(::extractProducts)
            .filter { p ->
                p.code?.isNotBlank() == true &&
                formulaForProduct(formulas, p) != null &&
                (brandFilter.isBlank() || normalize(p.brand).contains(normalize(brandFilter)))
            }
            .distinctBy { "${it.brand}|${it.code}" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text("اتصال به سایت WooCommerce", style = MaterialTheme.typography.titleLarge)
        Text("تطبیق فقط با SKU/کد مدل انجام می‌شود و قبل از تغییر قیمت، پیش‌نمایش می‌بینی.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(siteUrl, { siteUrl = it }, label = { Text("آدرس سایت") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(consumerKey, { consumerKey = it }, label = { Text("WooCommerce Consumer Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(consumerSecret, { consumerSecret = it }, label = { Text("WooCommerce Consumer Secret") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("کلیدها فقط در حافظه همین اجرای برنامه نگه داشته می‌شوند.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(brandFilter, { brandFilter = it }, label = { Text("فیلتر برند (اختیاری)") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy && consumerKey.isNotBlank() && consumerSecret.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    confirmed = false
                    previews = runCatching {
                        buildWooPricePreview(siteUrl, consumerKey, consumerSecret, products, formulas)
                    }.getOrElse {
                        onMessage("خطا در پیش‌نمایش سایت: ${it.message}")
                        emptyList()
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "در حال بررسی…" else "تست اتصال و ساخت پیش‌نمایش") }

        if (previews.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("پیدا شد: ${previews.count { it.matched }} • پیدا نشد: ${previews.count { !it.matched }}")

            previews.take(30).forEach { p ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${p.product.name} • ${p.product.code.orEmpty()}")
                        Text("قیمت نهایی برنامه: ${formatPrice(p.finalPrice)} تومان")
                        if (p.matched) {
                            Text("سایت: ${p.wooName.orEmpty()}")
                            Text("قیمت فعلی: ${p.currentPrice?.let(::formatPrice) ?: "نامشخص"}")
                        } else {
                            Text("با این SKU در سایت پیدا نشد.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                Text("پیش‌نمایش را بررسی کردم و تغییر قیمت‌ها را تأیید می‌کنم.")
            }

            Button(
                enabled = confirmed && !busy && previews.any { it.matched },
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            pushWooPrices(siteUrl, consumerKey, consumerSecret, previews.filter { it.matched })
                        }.onSuccess { count ->
                            onMessage("$count قیمت روی سایت بروزرسانی شد.")
                            confirmed = false
                        }.onFailure {
                            onMessage("خطا در ارسال قیمت‌ها: ${it.message}")
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ارسال قیمت‌های تأییدشده به سایت") }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun OnlineSourceScreen(
    apiKey: String,
    onImported: (DocItem) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { defaultTrustedSources() }
    var brand by remember { mutableStateOf(sources.first().brand) }
    var customUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val selected = sources.firstOrNull { it.brand == brand } ?: sources.first()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("بروزرسانی آنلاین قیمت‌ها", style = MaterialTheme.typography.titleLarge)
        Text("فقط منبع رسمی/معتبر همان برند پذیرفته می‌شود.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        sources.forEach { src ->
            FilterChip(
                selected = brand == src.brand,
                onClick = { brand = src.brand; customUrl = "" },
                label = { Text(src.brand) },
                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)
            )
        }

        Text("منبع: ${selected.label}")
        Text(selected.url, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text("لینک رسمی صفحه یا PDF (اختیاری)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy && apiKey.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    val target = customUrl.trim().ifBlank { selected.url }
                    runCatching {
                        syncTrustedOnlineSource(
                            context = context,
                            source = selected.copy(url = target),
                            apiKey = apiKey
                        )
                    }.onSuccess(onImported)
                     .onFailure { onMessage("خطا در بروزرسانی آنلاین: ${it.message}") }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "در حال دریافت و تحلیل…" else "بروزرسانی از منبع معتبر") }

        if (apiKey.isBlank()) Text("ابتدا کلید Gemini را در تب AI ذخیره کن.")
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun AiScreen(apiKey: String, onSave: (String) -> Unit) {
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("هوش مصنوعی Gemini", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("AI خود PDF را می‌خواند و برند، محصول، مدل، قیمت، واحد ریال/تومان و نوع قیمت را تشخیص می‌دهد. قیمت یا واحد حدس زده نمی‌شود.")
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
        Text(if (key.isBlank()) "Gemini شخصی غیرفعال است؛ AI ابری مجوزدار و Paddle همچنان مستقل کار می‌کنند." else "Gemini شخصی فعال است. مدل: gemini-3.8-flash")
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
        val brandDefault = formulaForBrand(formulas, brand)
        val specificCount = formulas.keys.count { key -> key.contains("||") && normalize(key.substringBefore("||")) == normalize(brand) }
        Text(if (brandDefault != null) "فرمول پیش‌فرض برند + $specificCount قانون محصولی" else "$specificCount قانون محصولی؛ ردیف بدون قانون محاسبه نمی‌شود.")
        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = rows.any { formulaForProduct(formulas, it) != null },
            onClick = {
                val finalRows = rows.filter { isMeaningfulProductName(it.name) && !isLikelyYearCode(it.code.orEmpty()) }.mapNotNull { p ->
                    val rule = formulaForProduct(formulas, p) ?: return@mapNotNull null
                    val final = runCatching { applyFormula(p.rawPrice, rule) }.getOrNull() ?: return@mapNotNull null
                    p to final
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
                val f = formulaForProduct(formulas, p)
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
    val isImage = mime.startsWith("image/")
    val paddleUrl = loadPaddleServerUrl(context)
    val cloudToken = loadLicenseToken(context)

    val embeddedText = when {
        isPdf -> runCatching { extractPdfText(outFile) }.getOrDefault("")
        isImage && paddleUrl.isBlank() -> runCatching { extractImageText(outFile) }.getOrDefault("")
        name.endsWith(".csv", true) || mime.contains("csv") || mime.startsWith("text/") ->
            runCatching { outFile.readText() }.getOrDefault("")
        !isPdf && !isImage -> runCatching { outFile.readText() }.getOrDefault("")
        else -> ""
    }

    val initialBrand = brandOverride.ifBlank { detectBrand("$name\n$embeddedText") }

    // New cloud path: Mistral Document AI on TajeriTools server, with Gemini cloud fallback.
    // It needs no PC and the provider keys never live inside the APK.
    val cloudJson = if (cloudToken.isNotBlank() && (isPdf || isImage)) {
        runCatching {
            if (isPdf) {
                analyzePdfAnySizeWithGateway(
                    token = cloudToken,
                    fileName = name,
                    file = outFile,
                    brandHint = initialBrand
                )
            } else {
                analyzeImageWithGateway(
                    token = cloudToken,
                    fileName = name,
                    file = outFile,
                    mimeType = mime,
                    brandHint = initialBrand
                )
            }
        }.getOrDefault("")
    } else ""

    val cloudRows = if (cloudJson.isNotBlank()) {
        runCatching {
            JSONObject(cloudJson).optJSONArray("products")?.length() ?: 0
        }.getOrDefault(0)
    } else 0

    // Existing PaddleOCR-VL path remains intact as the next fallback.
    val paddle = if (cloudRows == 0 && paddleUrl.isNotBlank() && (isPdf || isImage)) {
        runCatching {
            if (isPdf) {
                analyzePdfWithPaddleServer(
                    baseUrl = paddleUrl,
                    file = outFile,
                    brandHint = initialBrand,
                    sourceName = name
                )
            } else {
                analyzeImageWithPaddleServer(
                    baseUrl = paddleUrl,
                    file = outFile,
                    brandHint = initialBrand,
                    sourceName = name
                )
            }
        }.getOrNull()
    } else null

    val text = when {
        paddle != null && paddle.text.isNotBlank() -> paddle.text
        embeddedText.isNotBlank() -> embeddedText
        isPdf -> runCatching { extractPdfTextSmart(outFile) }.getOrDefault("")
        isImage -> runCatching { extractImageText(outFile) }.getOrDefault("")
        else -> ""
    }

    val localBrand = brandOverride.ifBlank {
        detectBrand("$name\n$text").ifBlank { initialBrand }
    }

    val paddleJson = paddle?.aiJson.orEmpty()
    val paddleRows = if (paddleJson.isNotBlank()) {
        runCatching {
            JSONObject(paddleJson).optJSONArray("products")?.length() ?: 0
        }.getOrDefault(0)
    } else 0

    // Existing direct Gemini path also remains available for the owner's personal API key.
    val aiJson = when {
        cloudRows > 0 -> cloudJson
        paddleRows > 0 -> paddleJson
        apiKey.isNotBlank() && isPdf ->
            runCatching { analyzePdfAnySizeWithGemini(apiKey, name, outFile) }.getOrDefault("")
        apiKey.isNotBlank() && text.isNotBlank() ->
            runCatching { analyzeWithGemini(apiKey, name, text) }.getOrDefault("")
        else -> ""
    }

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

fun restoreReadableSpacing(value: String): String {
    var s = value
        .replace('\u00A0', ' ')
        .replace(Regex("""[\t\r\n]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    // Paddle/PDF extraction can glue Persian words to Latin model codes or numbers.
    s = s
        .replace(Regex("""([آ-ی])([A-Za-z0-9])"""), "$1 $2")
        .replace(Regex("""([A-Za-z0-9])([آ-ی])"""), "$1 $2")
        .replace(Regex("""\s*([،؛:])\s*"""), "$1 ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    val phraseRepairs = listOf(
        "دریلپیچگوشتیشارژی" to "دریل پیچ گوشتی شارژی",
        "دریلپیچگوشتی" to "دریل پیچ گوشتی",
        "پیچگوشتیشارژی" to "پیچ گوشتی شارژی",
        "پیچگوشتیبرقی" to "پیچ گوشتی برقی",
        "دریلچکشی" to "دریل چکشی",
        "دریلشارژیچکشی" to "دریل شارژی چکشی",
        "دریلشارژی" to "دریل شارژی",
        "دستگاهجوشکاری" to "دستگاه جوشکاری",
        "سه‌نظام" to "سه نظام",
        "سهنظام" to "سه نظام",
        "نیوتنمتر" to "نیوتن متر",
        "آمپرساعت" to "آمپر ساعت",
        "دوباتری" to "دو باتری",
        "یکعدد" to "یک عدد",
        "روکشدسته" to "روکش دسته",
        "ضدلغزش" to "ضد لغزش",
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
        "ارهعمودبر" to "اره عمود بر",
        "ارهزنجیریشارژی" to "اره زنجیری شارژی",
        "ارهزنجیریبنزینی" to "اره زنجیری بنزینی",
        "فارسیبر" to "فارسی بر",
        "پروفیلبر" to "پروفیل بر",
        "سنبادهلرزان" to "سنباده لرزان",
        "سشوارصنعتی" to "سشوار صنعتی",
        "پیستولهبرقی" to "پیستوله برقی",
        "بلوورشارژی" to "بلوور شارژی",
        "دمندهبرقی" to "دمنده برقی",
        "همزنبرقی" to "همزن برقی",
        "کارواششارژی" to "کارواش شارژی",
        "کارواشپرتابل" to "کارواش پرتابل",
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
    phraseRepairs.forEach { (stuck, spaced) ->
        s = s.replace(stuck, spaced, ignoreCase = true)
    }
    return s.replace(Regex("""\s+"""), " ").trim()
}

fun repairProductName(value: String): String {
    val readable = restoreReadableSpacing(value)
    val normalized = normalize(readable)
    val tokens = normalized.split(" ").filter { it.isNotBlank() }
    val singleRatio = if (tokens.isEmpty()) 0.0 else tokens.count { it.length == 1 && it[0].isLetter() }.toDouble() / tokens.size
    if (singleRatio < 0.55) return readable

    // OCR may return a Persian word as one character per token. Rejoin, then re-space known phrases.
    val compact = tokens.joinToString("")
    return restoreReadableSpacing(compact)
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
    parseAiProducts(doc)?.takeIf { it.isNotEmpty() }?.let { return it }

    val blockRows = extractProductsFromTextBlocks(doc.brand, doc.text, doc.name)
    if (blockRows.isNotEmpty()) return blockRows

    val codeFirstRows = extractProductsFromCodeFirstBlocks(doc.brand, doc.text, doc.name)
    if (codeFirstRows.isNotEmpty()) return codeFirstRows

    return emptyList()
}

fun extractProductsFromTextBlocks(brand: String, text: String, sourceName: String): List<ProductLine> {
    val documentUnit = detectPriceUnit(text)
    val lines = text.lines()
        .map { it.replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotBlank() }

    val dateRegex = Regex("""(?:13|14|20)\d{2}[/.-]\d{1,2}[/.-]\d{1,2}""")
    val priceRegex = Regex("""(?<!\w)([0-9۰-۹]{1,3}(?:[٬,/][0-9۰-۹]{3}){2,3}|[0-9۰-۹]{6,12})(?!\w)""")
    val codeRegex = Regex("""\b(?:[A-Za-z]{1,8}[-_]?[0-9]{2,8}[A-Za-z0-9-]*|[0-9]{4,6}[A-Za-z]?)\b""")

    data class Block(val start: Int, val endExclusive: Int)
    val starts = lines.indices.filter { i ->
        val line = normalize(lines[i])
        priceRegex.containsMatchIn(line) && (
            dateRegex.containsMatchIn(line) ||
            Regex("""(?:قیمت|تومان|ریال|price)""", RegexOption.IGNORE_CASE).containsMatchIn(line) ||
            line.count { it.isDigit() } >= 8
        )
    }

    if (starts.isEmpty()) return emptyList()

    val blocks = starts.mapIndexed { idx, s ->
        Block(s, if (idx + 1 < starts.size) starts[idx + 1] else lines.size)
    }

    val out = mutableListOf<ProductLine>()
    for (block in blocks) {
        val chunk = lines.subList(block.start, block.endExclusive).take(8)
        if (chunk.isEmpty()) continue

        val allText = chunk.joinToString(" ")
        val priceCandidates = priceRegex.findAll(normalize(allText)).mapNotNull { m ->
            val raw = m.value
            val value = parseNumber(raw) ?: return@mapNotNull null
            val digits = raw.count(Char::isDigit)
            if (digits < 6 || value < 50_000) return@mapNotNull null
            val score = when {
                raw.contains("/") || raw.contains(",") || raw.contains("٬") -> 10
                digits >= 8 -> 6
                else -> 2
            }
            Triple(raw, value, score)
        }.toList()

        val pickedPrice = priceCandidates.maxWithOrNull(
            compareBy<Triple<String, Double, Int>> { it.third }.thenBy { it.first.length }
        ) ?: continue
        val priceToken = pickedPrice.first
        val sourcePrice = pickedPrice.second
        val price = priceToToman(sourcePrice, documentUnit)

        val codeCandidates = codeRegex.findAll(allText)
            .map { it.value.replace(" ", "") }
            .filterNot { isLikelyYearCode(it) }
            .filterNot { candidate ->
                val n = parseNumber(candidate)
                n != null && kotlin.math.abs(n - price) < 0.5
            }
            .toList()

        val code = codeCandidates
            .sortedByDescending { candidate ->
                var score = 0
                if (candidate.any(Char::isLetter)) score += 20
                if (candidate.length in 4..7) score += 10
                if (chunk.any { normalize(it).trim() == normalize(candidate) }) score += 30
                score
            }
            .firstOrNull()

        val cleanedParts = chunk.map { rawLine ->
            var s = rawLine
            s = s.replace(dateRegex, " ")
            s = s.replace(priceToken, " ")
            if (!code.isNullOrBlank()) {
                s = s.replace(Regex("""\b${Regex.escape(code)}\b""", RegexOption.IGNORE_CASE), " ")
            }
            s = s.replace(Regex("""https?://\S+|www\.\S+|\S+\.com\S*""", RegexOption.IGNORE_CASE), " ")
            s = s.replace(Regex("""(?:آخرین\s*بروزرسانی|کد\s*کالا|تصویر\s*محصول|نام\s*کالا|تعداد\s*در\s*کارتن|قیمت)"""), " ")
            s = s.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '،', '|')
            s
        }.filter { part ->
            part.isNotBlank() &&
                part.any(Char::isLetter) &&
                !part.matches(Regex("""^\d{1,3}$"""))
        }

        var name = repairProductName(cleanedParts.joinToString(" "))
        name = name.replace(Regex("""\s+"""), " ").trim()

        if (!isMeaningfulProductName(name)) continue
        if (code == null && name.length < 6) continue

        out += ProductLine(
            brand = brand,
            name = name.take(180),
            code = code,
            rawPrice = price,
            source = "${sourceName} • بلوک ${block.start + 1}"
        )
    }

    return out.distinctBy { "${it.brand}|${it.code}|${normalize(it.name)}|${it.rawPrice}" }
}

fun parseBrokenGroupedPrice(value: String): Double? {
    val s = normalize(value).replace("٬", "/").replace(",", "/")
    val m = Regex("""/(\d{3})\s*/(\d{3})\s*(\d{1,3})(?!\d)""").find(s)
    if (m != null) {
        val high = m.groupValues[3]
        val mid = m.groupValues[2]
        val low = m.groupValues[1]
        return "$high$mid$low".toDoubleOrNull()
    }
    return null
}

fun extractProductsFromCodeFirstBlocks(brand: String, text: String, sourceName: String): List<ProductLine> {
    val documentUnit = detectPriceUnit(text)
    val lines = text.lines()
        .map { normalize(it).replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotBlank() }

    val codeOnly = Regex("""^(?:[a-z]{0,8}[-_]?)?\d{4,6}[a-z]?$""", RegexOption.IGNORE_CASE)
    val dateRegex = Regex("""^(?:13|14|20)\d{2}[/.-]\d{1,2}[/.-]\d{1,2}$""")
    val codeIndices = lines.indices.filter { i ->
        val v = lines[i].replace(" ", "")
        codeOnly.matches(v) && !isLikelyYearCode(v)
    }
    if (codeIndices.isEmpty()) return emptyList()

    val out = mutableListOf<ProductLine>()
    for ((idx, start) in codeIndices.withIndex()) {
        val end = if (idx + 1 < codeIndices.size) codeIndices[idx + 1] else lines.size
        val chunk = lines.subList(start, end).take(18)
        val code = lines[start].replace(" ", "")
        if (dateRegex.matches(code)) continue

        var price: Double? = null
        var priceLineIndex = -1
        for (i in chunk.indices) {
            val line = chunk[i]
            val broken = parseBrokenGroupedPrice(line)
            if (broken != null && broken >= 50_000) {
                price = broken
                priceLineIndex = i
                break
            }
            val normalCandidates = Regex("""(?<!\w)([0-9]{1,3}(?:[٬,/][0-9]{3}){2,3}|[0-9]{6,12})(?!\w)""")
                .findAll(line)
                .mapNotNull { mr -> parseNumber(mr.value) }
                .filter { v -> v >= 50_000 }
                .toList()
            if (normalCandidates.isNotEmpty()) {
                price = normalCandidates.maxOrNull()
                priceLineIndex = i
                break
            }
        }
        if (price == null) continue
        val sourcePrice = price
        val normalizedPrice = priceToToman(sourcePrice, documentUnit)

        val nameLines = chunk.drop(1)
            .take(if (priceLineIndex > 0) priceLineIndex else 8)
            .filter { line ->
                line.any(Char::isLetter) &&
                    !line.contains("عدد") &&
                    !line.contains("ست") &&
                    !line.contains("کارتن") &&
                    !line.contains("قیمت") &&
                    !line.contains("بروزرسانی") &&
                    !line.contains("تصویر") &&
                    !line.contains("کد کالا")
            }

        val name = repairProductName(nameLines.joinToString(" "))
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (!isMeaningfulProductName(name)) continue

        out += ProductLine(
            brand = brand,
            name = name.take(180),
            code = code,
            rawPrice = normalizedPrice,
            source = "$sourceName • code-first",
            sourcePrice = sourcePrice,
            priceUnit = documentUnit,
            priceType = "list"
        )
    }
    return out.distinctBy { "${it.brand}|${it.code}|${it.rawPrice}" }
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
        "Vivarex" to listOf("vivarex", "ویوارکس"),
        "Hans" to listOf("hans", "هنس"),
        "Winner" to listOf("winner", "وینر")
    )
    return brands.firstOrNull { (_, keys) -> keys.any { s.contains(normalize(it)) } }?.first.orEmpty()
}

fun parseAiBrand(aiJson: String): String {
    if (aiJson.isBlank()) return ""
    return runCatching { JSONObject(aiJson).optString("brand").trim() }.getOrDefault("")
}

fun shouldAcceptAiProduct(name: String, code: String?, price: Double, confidence: Double): Boolean {
    val identityOk = isMeaningfulProductName(name) && (code != null || name.length >= 5)
    return identityOk && price.isFinite() && price >= 50_000 && confidence >= 0.72
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
                val code = p.optString("code").trim().takeIf { it.isNotBlank() && !isLikelyYearCode(it) }
                val sourcePrice = p.optDouble("price", Double.NaN)
                val unit = normalizePriceUnit(p.optString("price_unit"))
                val priceType = p.optString("price_type").trim().ifBlank { "list" }
                val price = priceToToman(sourcePrice, unit)
                val confidence = p.optDouble("confidence", 0.0)
                val page = p.optInt("page", 0)
                val evidence = p.optString("evidence").trim()
                if (shouldAcceptAiProduct(name, code, price, confidence) && unit != "unknown") {
                    val src = buildString {
                        append("AI: ${doc.name}")
                        if (page > 0) append(" • صفحه $page")
                        if (evidence.isNotBlank()) append(" • ${evidence.take(120)}")
                    }
                    add(ProductLine(
                        brand = brand,
                        name = name,
                        code = code,
                        rawPrice = price,
                        source = src,
                        sourcePrice = sourcePrice,
                        priceUnit = unit,
                        priceType = priceType
                    ))
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
{"brand":"brand name","products":[{"name":"specific product name","code":"model/code or empty","price":123456,"price_unit":"rial|toman","price_type":"list|wholesale|retail|special|mrp|other","page":1,"confidence":0.95,"promotion":"7+1 or other visible offer, else empty","bbox":[120,80,250,920],"evidence":"short row context"}]}

Rules:
- Detect brand from the filename and document text.
- Extract only real product rows that contain a price in the supplied document.\n- Every returned product MUST include a specific product name; include its model/code whenever visible.\n- Never return a row whose only description is a date, quantity, page number, or generic placeholder.\n- Never invent a price, product, model, or brand.
- Preserve the numeric price semantically; remove thousands separators only.
- Detect the currency/unit from the document itself. price_unit MUST be exactly "rial" or "toman"; never guess. If the unit is not supported by visible evidence, omit that row.
- If multiple prices exist for a product, identify price_type and choose the CURRENT operative price shown by the list. Do not mix MRP, old price, tax, discount percentage, wholesale tier, or promotional price unless that is explicitly the operative price column.
- If multiple quantity tiers exist, extract the normal/base current price unless the row explicitly marks a tier as the primary trade price.
- Keep each product tied to the SAME row/table cell group as its price. Never borrow a name from another row.
- confidence must be 0..1; use high confidence only when name/model/price alignment is visually clear.
- page is the 1-based page number and evidence is short row-level context.
- promotion is only a visible free-item/buy-X-get-Y offer tied to that exact product row; otherwise empty.
- bbox is [ymin,xmin,ymax,xmax] for the exact visual product row/card, normalized to 0..1000 within the page. It must include the product image, name/model and price when they are in the same row.
- If uncertain about identity or price alignment, omit the row.
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
    val merged = JSONObject().apply {
        put("brand", "")
        put("products", JSONArray())
    }
    val mergedProducts = merged.getJSONArray("products")
    val tempDir = File(file.parentFile ?: File("."), "ai_chunks").apply { mkdirs() }

    PDDocument.load(file).use { source ->
        var start = 0
        val pagesPerChunk = 4
        while (start < source.numberOfPages) {
            val end = minOf(start + pagesPerChunk, source.numberOfPages)
            val chunkFile = File(tempDir, "chunk_${start}_${end}.pdf")

            PDDocument().use { chunk ->
                for (i in start until end) chunk.importPage(source.getPage(i))
                chunk.save(chunkFile)
            }

            val chunkJson = runCatching {
                analyzePdfWithGemini(
                    apiKey = apiKey,
                    fileName = "$fileName | original pages ${start + 1}-$end",
                    file = chunkFile,
                    originalPageOffset = start
                )
            }.getOrDefault("")

            if (chunkJson.isNotBlank()) {
                runCatching {
                    val o = JSONObject(chunkJson)
                    if (merged.optString("brand").isBlank()) {
                        merged.put("brand", o.optString("brand"))
                    }
                    val arr = o.optJSONArray("products") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        val page = p.optInt("page", 0)
                        if (page in 1..pagesPerChunk) p.put("page", page + start)
                        mergedProducts.put(p)
                    }
                }
            }

            chunkFile.delete()
            start = end
        }
    }

    tempDir.delete()

    val seen = mutableSetOf<String>()
    val deduped = JSONArray()
    for (i in 0 until mergedProducts.length()) {
        val p = mergedProducts.getJSONObject(i)
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

fun analyzePdfWithGemini(apiKey: String, fileName: String, file: File, originalPageOffset: Int = 0): String {
    val prompt = """
You analyze Iranian tool-store price-list PDFs.
Return ONLY valid JSON:
{"brand":"brand name","products":[{"name":"specific product name","code":"model/code or empty","price":123456,"price_unit":"rial|toman","price_type":"list|wholesale|retail|special|mrp|other","page":1,"confidence":0.95,"promotion":"7+1 or other visible offer, else empty","bbox":[120,80,250,920],"evidence":"short row context"}]}
Rules:
- Read the PDF itself, including scanned pages and tables.
- Extract only actual product sale-price rows.\n- Every returned row MUST contain the specific product name and model/code when visible in the table.\n- If the name/model cannot be tied confidently to the price, omit that row.\n- Never treat model codes, dates, page numbers, phone numbers, percentages or quantities as prices.
- Never invent data.
- Preserve the actual document price; remove separators only.
- Detect price_unit from visible document labels; it MUST be "rial" or "toman". Never infer the unit from number size alone.
- If the page contains retail, wholesale, MRP, old/new, tax-inclusive/exclusive, or quantity-tier prices, classify price_type and select the current operative/base price for that row. Never merge numbers from different columns.
- Promotional free-item text such as "7+1" is NOT a price; keep it out of price and mention it only in evidence.
- Keep name/model/price from the SAME visual row or cell group; never pair adjacent products.
- page is 1-based inside the provided PDF. confidence is 0..1. evidence must summarize the exact visual row.
- promotion is only a visible buy-X-get-Y/free-item offer tied to that exact row; otherwise empty.
- bbox is [ymin,xmin,ymax,xmax], normalized to 0..1000 within the page, covering the exact product row/card including its image/name/model/price when possible.
- Omit uncertain rows; missing a row is better than assigning a wrong price.
Filename: $fileName\nOriginal PDF page offset: $originalPageOffset\nReturn page numbers relative to THIS chunk starting from 1.\n""".trimIndent()
    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    val parts = JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", encoded)))
    val request = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.0).put("maxOutputTokens", 32768))
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


fun wooAuthHeader(consumerKey: String, consumerSecret: String): String {
    val raw = "$consumerKey:$consumerSecret"
    return "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

fun normalizedSiteBase(siteUrl: String): String {
    val trimmed = siteUrl.trim().trimEnd('/')
    require(trimmed.startsWith("https://")) { "آدرس سایت باید HTTPS باشد" }
    return trimmed
}

fun wooRequest(
    method: String,
    url: String,
    consumerKey: String,
    consumerSecret: String,
    body: JSONObject? = null
): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("Authorization", wooAuthHeader(consumerKey, consumerSecret))
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    if (body != null) {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    require(code in 200..299) { "WooCommerce HTTP $code: ${text.take(300)}" }
    return text
}

suspend fun buildWooPricePreview(
    siteUrl: String,
    consumerKey: String,
    consumerSecret: String,
    products: List<ProductLine>,
    formulas: Map<String, String>
): List<SitePricePreview> = withContext(Dispatchers.IO) {
    val base = normalizedSiteBase(siteUrl)
    val out = mutableListOf<SitePricePreview>()

    for (product in products) {
        val code = product.code?.trim().orEmpty()
        if (code.isBlank()) continue
        val formula = formulaForProduct(formulas, product) ?: continue
        val finalPrice = runCatching { applyFormula(product.rawPrice, formula) }.getOrNull() ?: continue

        val sku = URLEncoder.encode(code, "UTF-8")
        val response = runCatching {
            wooRequest("GET", "$base/wp-json/wc/v3/products?sku=$sku&per_page=10", consumerKey, consumerSecret)
        }

        if (response.isFailure) {
            out += SitePricePreview(product, finalPrice, null, null, null, false, response.exceptionOrNull()?.message)
            continue
        }

        val arr = JSONArray(response.getOrThrow())
        val exact = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { normalize(it.optString("sku")) == normalize(code) }

        if (exact == null) {
            out += SitePricePreview(product, finalPrice, null, null, null, false)
        } else {
            val current = exact.optString("regular_price").toDoubleOrNull()
                ?: exact.optString("price").toDoubleOrNull()
            out += SitePricePreview(
                product = product,
                finalPrice = finalPrice,
                wooId = exact.optLong("id"),
                wooName = exact.optString("name"),
                currentPrice = current,
                matched = true
            )
        }
    }
    out
}

suspend fun pushWooPrices(
    siteUrl: String,
    consumerKey: String,
    consumerSecret: String,
    previews: List<SitePricePreview>
): Int = withContext(Dispatchers.IO) {
    val base = normalizedSiteBase(siteUrl)
    var updated = 0
    for (item in previews) {
        val id = item.wooId ?: continue
        val body = JSONObject().put("regular_price", item.finalPrice.roundToLong().toString())
        wooRequest("PUT", "$base/wp-json/wc/v3/products/$id", consumerKey, consumerSecret, body)
        updated++
    }
    updated
}


fun isTrustedUrl(url: String, source: TrustedSource): Boolean {
    val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
    return source.domains.any { domain -> host == domain || host.endsWith(".$domain") }
}

fun fetchUrlBytes(url: String, maxBytes: Int = 12 * 1024 * 1024): Pair<ByteArray, String> {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 45_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "TajeriToolsPriceManager/1.0")
        setRequestProperty("Accept", "text/html,application/pdf,text/plain,*/*")
    }
    val code = connection.responseCode
    require(code in 200..299) { "HTTP $code" }
    val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
    val bytes = connection.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            require(out.size() + n <= maxBytes) { "حجم منبع بیشتر از حد مجاز است" }
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    return bytes to type
}

fun htmlToText(html: String): String =
    html.replace(Regex("""(?is)<script.*?>.*?</script>"""), " ")
        .replace(Regex("""(?is)<style.*?>.*?</style>"""), " ")
        .replace(Regex("""(?is)<[^>]+>"""), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("""\s+"""), " ").trim()

fun analyzeOnlineSourceWithGemini(apiKey: String, brand: String, sourceUrl: String, content: String): String {
    val prompt = """
You normalize CURRENT product pricing from an authoritative tool-brand source.
Expected brand: $brand
Authoritative source: $sourceUrl

Return ONLY JSON:
{"brand":"brand","products":[{"name":"specific product name","code":"model/code or empty","price":123456,"price_unit":"rial|toman","price_type":"list|wholesale|retail|special|mrp|other","page":0,"confidence":0.95,"promotion":"visible offer or empty","bbox":[0,0,0,0],"evidence":"short exact source context"}]}

Rules:
- Include only products whose current price is explicitly present.
- Never invent missing prices, units, models, or products.
- Keep name/code/price from the same row or product card.
- price_unit must come from explicit source evidence and be exactly rial or toman.
- If multiple prices exist, classify price_type and choose the current operative/base price.
- Do not treat discounts, tax percentages, quantity thresholds, or 7+1 / 5+1 promotions as prices.
- Omit uncertain identity-price matches.
CONTENT:
${content.take(220_000)}
""".trimIndent()

    val request = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        }))
        put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json")
            .put("temperature", 0.0)
            .put("maxOutputTokens", 32768))
    }

    val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 30_000
        readTimeout = 120_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("x-goog-api-key", apiKey)
    }
    connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    require(code in 200..299) { "Gemini HTTP $code: ${body.take(300)}" }
    return JSONObject(body).getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
        .getString("text").trim()
}

suspend fun syncTrustedOnlineSource(
    context: Context,
    source: TrustedSource,
    apiKey: String
): DocItem = withContext(Dispatchers.IO) {
    require(apiKey.isNotBlank()) { "کلید AI تنظیم نشده است" }
    require(isTrustedUrl(source.url, source)) { "این URL خارج از دامنه معتبر برند است" }

    val (bytes, contentType) = fetchUrlBytes(source.url)
    val isPdf = contentType.contains("pdf") || source.url.lowercase(Locale.ROOT).endsWith(".pdf")
    val dir = File(context.filesDir, "online").apply { mkdirs() }

    if (isPdf) {
        val file = File(dir, "${source.brand}-${System.currentTimeMillis()}.pdf")
        file.writeBytes(bytes)
        val ai = analyzePdfAnySizeWithGemini(apiKey, file.name, file)
        val text = runCatching { extractPdfTextSmart(file) }.getOrDefault("")
        DocItem(
            id = "online-${System.currentTimeMillis()}",
            brand = parseAiBrand(ai).ifBlank { source.brand },
            name = "آنلاین ${source.label}",
            path = file.absolutePath,
            mime = "application/pdf",
            text = text,
            aiJson = ai
        )
    } else {
        val raw = htmlToText(bytes.toString(Charsets.UTF_8))
        val ai = analyzeOnlineSourceWithGemini(apiKey, source.brand, source.url, raw)
        val file = File(dir, "${source.brand}-${System.currentTimeMillis()}.txt")
        file.writeText(raw)
        DocItem(
            id = "online-${System.currentTimeMillis()}",
            brand = parseAiBrand(ai).ifBlank { source.brand },
            name = "آنلاین ${source.label}",
            path = file.absolutePath,
            mime = "text/plain",
            text = raw,
            aiJson = ai
        )
    }
}

fun normalizePriceUnit(value: String): String {
    val s = normalize(value)
    return when {
        s.contains("تومان") || s.contains("toman") -> "toman"
        s.contains("ریال") || s.contains("rial") -> "rial"
        else -> "unknown"
    }
}

fun detectPriceUnit(value: String): String = normalizePriceUnit(value)

fun priceToToman(price: Double, unit: String): Double =
    when (unit) {
        "rial" -> price / 10.0
        "toman" -> price
        else -> price
    }

fun unitLabel(unit: String): String =
    when (unit) {
        "rial" -> "ریال"
        "toman" -> "تومان"
        else -> "واحد نامشخص"
    }

fun pricingRuleKey(brand: String, product: String): String =
    if (product.isBlank()) brand.trim() else "${brand.trim()}||${product.trim()}"

fun formulaForProduct(formulas: Map<String, String>, product: ProductLine): String? {
    val brandNorm = normalize(product.brand)
    val codeNorm = normalize(product.code.orEmpty())
    val nameNorm = normalize(product.name)
    val specific = formulas.entries.mapNotNull { (key, formula) ->
        val parts = key.split("||", limit = 2)
        if (parts.size != 2 || normalize(parts[0]) != brandNorm) return@mapNotNull null
        val target = normalize(parts[1])
        if (target.isBlank()) return@mapNotNull null
        val score = when {
            codeNorm.isNotBlank() && target == codeNorm -> 1000 + target.length
            codeNorm.isNotBlank() && codeNorm.contains(target) -> 800 + target.length
            nameNorm == target -> 700 + target.length
            nameNorm.contains(target) -> 500 + target.length
            else -> 0
        }
        if (score > 0) score to formula else null
    }.maxByOrNull { it.first }
    return specific?.second ?: formulaForBrand(formulas, product.brand)
}

fun formulaForBrand(formulas: Map<String, String>, brand: String): String? {
    formulas[brand]?.let { return it }
    val canonical = detectBrand(brand)
    return formulas.entries.firstOrNull { (k, _) -> !k.contains("||") && (normalize(k) == normalize(brand) || (canonical.isNotBlank() && detectBrand(k) == canonical)) }?.value
}

fun applyFormulaSteps(input: Double, formula: String): List<Pair<String, Double>> {
    var value = input
    val tokens = formula.split("=")
        .map { normalize(it).replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", "") }
        .filter { it.isNotBlank() }

    val result = mutableListOf<Pair<String, Double>>()

    for (token0 in tokens) {
        val token = token0.replace("قیمت", "price")

        val discountMatch = Regex("""discount\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)
        if (discountMatch != null) {
            val pct = discountMatch.groupValues[1].toDouble()
            require(pct in 0.0..99.99) { "تخفیف نامعتبر" }
            value *= (1.0 - pct / 100.0)
            result += ("تخفیف ${trimNumber(pct)}٪" to value)
            continue
        }

        val giftMatch = Regex("""gift\((\d+),(\d+)\)""").matchEntire(token)
        if (giftMatch != null) {
            val buyQty = giftMatch.groupValues[1].toInt()
            val freeQty = giftMatch.groupValues[2].toInt()
            require(buyQty > 0 && freeQty > 0) { "اشانتیون نامعتبر" }
            value *= buyQty.toDouble() / (buyQty + freeQty).toDouble()
            result += ("اشانتیون $buyQty+$freeQty" to value)
            continue
        }

        val costMatch = Regex("""cost\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)
        if (costMatch != null) {
            val pct = costMatch.groupValues[1].toDouble()
            require(pct >= 0.0) { "هزینه جانبی نامعتبر" }
            value *= (1.0 + pct / 100.0)
            result += ("هزینه جانبی ${trimNumber(pct)}٪" to value)
            continue
        }

        val marginMatch = Regex("""margin\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)
        if (marginMatch != null) {
            val pct = marginMatch.groupValues[1].toDouble()
            require(pct >= 0.0 && pct < 100.0) { "حاشیه سود نامعتبر" }
            value /= (1.0 - pct / 100.0)
            result += ("حاشیه سود ${trimNumber(pct)}٪" to value)
            continue
        }

        val markupMatch = Regex("""markup\(([0-9]+(?:\.[0-9]+)?)\)""").matchEntire(token)
        if (markupMatch != null) {
            val pct = markupMatch.groupValues[1].toDouble()
            require(pct >= 0.0) { "سود روی هزینه نامعتبر" }
            value *= (1.0 + pct / 100.0)
            result += ("سود روی هزینه ${trimNumber(pct)}٪" to value)
            continue
        }

        val roundMatch = Regex("""round\((\d+)\)""").matchEntire(token)
        if (roundMatch != null) {
            val step = roundMatch.groupValues[1].toDouble()
            require(step > 0) { "گردکردن نامعتبر" }
            value = ceil(value / step) * step
            result += ("گردکردن ${step.toLong()}" to value)
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
            result += ("$op${trimNumber(num)}${if (pct) "%" else ""}" to value)
        }
    }

    require(result.isNotEmpty()) { "هیچ مرحله قابل محاسبه‌ای پیدا نشد" }
    return result
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
