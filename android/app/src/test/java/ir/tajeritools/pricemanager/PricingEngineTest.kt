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
