package ir.tajeritools.pricemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingEngineTest {

    @Test
    fun oldFormulaStillMatchesUserExample() {
        val final = applyFormula(20_000_000.0, "discount(18)=markup(10)")
        assertEquals(18_040_000.0, final, 0.5)
    }

    @Test
    fun professionalFormulaHandlesGiftCostsMarginAndRounding() {
        val final = applyFormula(
            20_000_000.0,
            "discount(18)=gift(10,1)=cost(2)=margin(10)=round(10000)"
        )
        assertEquals(16_900_000.0, final, 0.5)
    }

    @Test
    fun giftReducesEffectiveUnitCost() {
        val final = applyFormula(16_400_000.0, "gift(10,1)")
        assertEquals(14_909_090.909, final, 1.0)
    }

    @Test
    fun persianYearIsNeverAcceptedAsModelCode() {
        assertTrue(isLikelyYearCode("1405"))
        assertTrue(isLikelyYearCode("2026"))
        assertFalse(isLikelyYearCode("5951"))
    }

    @Test
    fun productSpecificRuleOverridesBrandDefault() {
        val formulas = mapOf(
            "Anchor" to "discount(18)=margin(10)",
            "Anchor||DCE12" to "discount(18)=gift(7,1)=margin(10)",
            "Anchor||DCE20" to "discount(18)=gift(5,1)=margin(10)"
        )
        val p12 = ProductLine("Anchor", "دریل شارژی", "DCE12", 20_000_000.0, "test")
        val p20 = ProductLine("Anchor", "دریل شارژی", "DCE20", 20_000_000.0, "test")
        val other = ProductLine("Anchor", "دریل شارژی", "DCE99", 20_000_000.0, "test")

        assertEquals("discount(18)=gift(7,1)=margin(10)", formulaForProduct(formulas, p12))
        assertEquals("discount(18)=gift(5,1)=margin(10)", formulaForProduct(formulas, p20))
        assertEquals("discount(18)=margin(10)", formulaForProduct(formulas, other))
    }

    @Test
    fun aiRowsNeedConfidenceAndIdentity() {
        val doc = DocItem(
            id = "ai",
            brand = "Arva",
            name = "sample.pdf",
            path = "",
            mime = "application/pdf",
            text = "",
            aiJson = """{"brand":"Arva","products":[
                {"name":"جارو شارژی","code":"5951","price":59999000,"page":15,"confidence":0.96,"evidence":"5951 جارو شارژی 59,999,000"},
                {"name":"دستگاه","code":"","price":12345678,"page":1,"confidence":0.40,"evidence":"unclear"}
            ]}"""
        )
        val rows = parseAiProducts(doc).orEmpty()
        assertEquals(1, rows.size)
        assertEquals("5951", rows.first().code)
        assertEquals(59_999_000.0, rows.first().rawPrice, 0.5)
    }

    @Test
    fun parserRejectsDateAsPriceAndKeepsRealModel() {
        val doc = DocItem(
            id = "t",
            brand = "Arva",
            name = "sample.pdf",
            path = "",
            mime = "application/pdf",
            text = "1405/06/20 10 جارو شارژی 8 ولت 5951 59/999/000",
            aiJson = ""
        )
        val rows = extractProducts(doc)
        assertEquals(1, rows.size)
        assertEquals(59_999_000.0, rows.first().rawPrice, 0.5)
        assertEquals("5951", rows.first().code)
        assertTrue(rows.first().name.contains("جارو"))
    }
}
