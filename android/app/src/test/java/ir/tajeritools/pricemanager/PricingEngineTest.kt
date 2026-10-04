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
        assertTrue(shouldAcceptAiProduct("جارو شارژی", "5951", 59_999_000.0, 0.96))
        assertFalse(shouldAcceptAiProduct("دستگاه", null, 12_345_678.0, 0.40))
        assertFalse(shouldAcceptAiProduct("جارو شارژی", "5951", 59_999_000.0, 0.60))
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
    @Test
    fun arvaMultilineRowKeepsCodeNameAndPriceTogether() {
        val text = """
            1405/06/20 349/999/000 1
            دستگاه جوشکاری
            اینورتر
            ARC 215 IGBT
            2101
            1405/06/20 369/999/000 1
            دستگاه جوشکاری
            اینورتر
            ARC 220 IGBT
            2102
        """.trimIndent()
        val rows = extractProductsFromTextBlocks("Arva", text, "arva.pdf")
        assertEquals(2, rows.size)
        assertEquals("2101", rows[0].code)
        assertEquals(349_999_000.0, rows[0].rawPrice, 0.5)
        assertTrue(rows[0].name.contains("ARC 215 IGBT"))
        assertEquals("2102", rows[1].code)
        assertEquals(369_999_000.0, rows[1].rawPrice, 0.5)
    }

    @Test
    fun arvaInlineRowKeepsDrillModelAndPriceTogether() {
        val text = """
            دريل 500 وات 10 66/999/000 1405/06/20
            5303 10 ميليمتری اتومات
            1405/06/20 85/999/000 8
            دريل چکشی
            850 وات
            13 ميليمتری
            5304
        """.trimIndent()
        val rows = extractProductsFromTextBlocks("Arva", text, "arva.pdf")
        assertEquals(2, rows.size)
        assertEquals("5303", rows[0].code)
        assertEquals(66_999_000.0, rows[0].rawPrice, 0.5)
        assertTrue(rows[0].name.contains("دريل"))
        assertEquals("5304", rows[1].code)
        assertEquals(85_999_000.0, rows[1].rawPrice, 0.5)
        assertTrue(rows[1].name.contains("850"))
    }

    @Test
    fun ronixSingleLineTableKeepsIdentityAndPrice() {
        val text = """
            آخرین بروزرسانی قیمت(ریال) تعداد شرح کد کالا
            1405/02/15 56,980,000 1 12 دریل 6/5 میلی متری معمولی سه نظام آچاری 400وات B2106
            1405/02/15 59,980,000 1 12 دریل 6/5 میلی متری معمولی سه نظام اتوماتیک 400وات C2106
        """.trimIndent()
        val rows = extractProductsFromTextBlocks("Ronix", text, "ronix.pdf")
        assertEquals(2, rows.size)
        assertEquals("B2106", rows[0].code)
        assertEquals(5_698_000.0, rows[0].rawPrice, 0.5)
        assertTrue(rows[0].name.contains("دریل"))
        assertEquals("C2106", rows[1].code)
        assertEquals(5_998_000.0, rows[1].rawPrice, 0.5)
    }

    @Test
    fun novaBrokenPriceIsReconstructed() {
        assertEquals(169_980_000.0, parseBrokenGroupedPrice("۱عدد /۰۰۰ /۹۸۰ ۱۶۹")!!, 0.5)
        assertEquals(309_980_000.0, parseBrokenGroupedPrice("۱عدد /۰۰۰ /۹۸۰ ۳۰۹")!!, 0.5)
    }

    @Test
    fun novaCodeFirstBlockKeepsNameCodeAndPrice() {
        val text = """
            قیمت (ریال)
            ۵۵۱۰
            دریل پیچ گوشتی شارژی ۱۰
            میلیمتری ۱۶ولت
            براشلس دو سرعته چکشی ۵۰
            نیوتن متر، ۲ باتری
            با کیف BMC
            ۴
            ست
            ۱عدد /۰۰۰ /۹۸۰ ۱۶۹
            ۵۵۱۵
            پیچ گوشتی شارژی ۱۶ ولت
            براشلس ضربه ای
            سه سرعته ۱۵۰ نیوتن متر
            ۲ باتری - با کیف BMC
            ۴
            ست
            ۱عدد /۰۰۰ /۹۸۰ ۱۷۹
        """.trimIndent()
        val rows = extractProductsFromCodeFirstBlocks("Nova", text, "nova.pdf")
        assertEquals(2, rows.size)
        assertEquals("5510", rows[0].code)
        assertEquals(16_998_000.0, rows[0].rawPrice, 0.5)
        assertTrue(rows[0].name.contains("دریل"))
        assertEquals("5515", rows[1].code)
        assertEquals(17_998_000.0, rows[1].rawPrice, 0.5)
    }

    @Test
    fun currencyDetectionPreventsTenTimesPricingError() {
        assertEquals("rial", detectPriceUnit("قیمت (ریال)"))
        assertEquals("toman", detectPriceUnit("تمامی قیمت ها به تومان میباشد"))
        assertEquals(5_698_000.0, priceToToman(56_980_000.0, "rial"), 0.5)
        assertEquals(3_299_000.0, priceToToman(3_299_000.0, "toman"), 0.5)
    }

    @Test
    fun unknownCurrencyIsExplicitlyMarkedUnknown() {
        assertEquals("unknown", detectPriceUnit("PRICE LIST"))
        assertEquals("واحد نامشخص", unitLabel("unknown"))
    }

}
