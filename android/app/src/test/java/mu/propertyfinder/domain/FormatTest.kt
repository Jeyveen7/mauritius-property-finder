package mu.propertyfinder.domain

import mu.propertyfinder.data.Fx
import mu.propertyfinder.data.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    private val labels = mapOf("m2" to "m²", "perch" to "perches", "toise" to "toises", "arpent" to "arpents")
    private val converter = Converter(Fx("2026-10-01", mapOf("EUR" to "52.5", "USD" to "45.5")))

    @Test fun money() {
        assertEquals("Rs 5,900,000", Format.money(Money(590_000_000, "MUR")))
        assertEquals("Rs 5.9M", Format.moneyShort(Money(590_000_000, "MUR")))
        // Rs 5.9M at 52.5 MUR/EUR is €112,381.
        assertEquals("€112k", Format.moneyShort(converter.convert(Money(590_000_000, "MUR"), "EUR")))
        assertEquals("$1.4M", Format.moneyShort(Money(136_000_000, "USD")))
    }

    @Test fun conversionGoesThroughRupees() {
        assertEquals(Money(1_848_000_000, "MUR"), converter.convert(Money(35_200_000, "EUR"), "MUR"))
        assertEquals(Money(590_000_000, "MUR"), converter.convert(Money(590_000_000, "MUR"), "MUR"))
    }

    @Test fun plotAreasInMauritianUnits() {
        assertEquals("507 m²", Format.area(507.0, "m2", labels))
        assertEquals("12.0 perches", Format.area(506.52, "perch", labels))
        assertEquals("0.50 arpents", Format.area(2110.435, "arpent", labels))
        assertEquals(506.52, Units.toM2(12.0, "perch"), 1e-9)
    }

    @Test fun budgetsAreRoundInEachCurrency() {
        assertEquals("Under Rs 5M", Budgets.label("MUR", 0, "Under"))
        assertEquals("€100k–€300k", Budgets.label("EUR", 1, "Under"))
        assertEquals(700_000L to null, Budgets.range("USD", 3))
    }
}
