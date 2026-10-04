package ir.tajeritools.pricemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class CatalogEntry(
    val doc: DocItem,
    val product: ProductLine,
    val page: Int = 0,
    val promotion: String = "",
    val confidence: Double = 0.0
)

fun buildCatalogEntries(docs: List<DocItem>): List<CatalogEntry> {
    val out = mutableListOf<CatalogEntry>()
    for (doc in docs) {
        val products = extractProducts(doc)
        for (p in products) {
            var page = 0
            var promo = ""
            var confidence = 0.0
            if (doc.aiJson.isNotBlank()) {
                runCatching {
                    val root = org.json.JSONObject(doc.aiJson)
                    val arr = root.optJSONArray("products") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val code = o.optString("code")
                        val name = o.optString("name")
                        val codeMatch = p.code != null && normalize(code) == normalize(p.code.orEmpty())
                        val nameMatch = normalize(name) == normalize(p.name)
                        if (codeMatch || nameMatch) {
                            page = o.optInt("page", 0)
                            promo = o.optString("promotion")
                            confidence = o.optDouble("confidence", 0.0)
                            break
                        }
                    }
                }
            }
            out += CatalogEntry(doc, p, page, promo, confidence)
        }
    }
    return out.distinctBy { normalize(it.product.brand) + "|" + normalize(it.product.code.orEmpty()) + "|" + normalize(it.product.name) }
}

@Composable
fun CatalogScreen(docs: List<DocItem>, formulas: Map<String, String>) {
    var q by remember { mutableStateOf("") }
    val entries = remember(docs) { buildCatalogEntries(docs) }
    val filtered = remember(entries, q) {
        val needle = normalize(q)
        if (needle.isBlank()) entries.take(120) else entries.filter {
            val p = it.product
            normalize(p.brand + " " + p.code.orEmpty() + " " + p.name + " " + it.promotion).contains(needle)
        }.take(120)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("کاتالوگ محصولات PDF", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("نام یا مدل؛ مثال: دریل، اینورتر، 5304") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Text(filtered.size.toString() + " نتیجه", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered) { entry ->
                val p = entry.product
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("برند: " + p.brand)
                        if (!p.code.isNullOrBlank()) Text("مدل/کد: " + p.code)
                        if (entry.page > 0) Text("صفحه PDF: " + entry.page)
                        Text("قیمت منبع: " + formatPrice(p.sourcePrice) + " " + unitLabel(p.priceUnit))
                        if (entry.promotion.isNotBlank()) Text("شرایط/اشانتیون: " + entry.promotion)
                        formulaForProduct(formulas, p)?.let { f ->
                            runCatching { applyFormula(p.rawPrice, f) }.getOrNull()?.let { finalPrice ->
                                Text("قیمت نهایی: " + formatPrice(finalPrice) + " تومان")
                            }
                        }
                        if (entry.confidence > 0) {
                            Text("اطمینان AI: " + (entry.confidence * 100).toInt() + "٪", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
