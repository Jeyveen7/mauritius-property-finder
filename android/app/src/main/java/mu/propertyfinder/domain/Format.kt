package mu.propertyfinder.domain

import mu.propertyfinder.data.Fx
import mu.propertyfinder.data.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Mauritian land units, in m² per unit. Floor areas are always shown in m². */
object Units {
    val m2PerUnit = linkedMapOf(
        "m2" to BigDecimal("1"),
        "perch" to BigDecimal("42.21"),
        "toise" to BigDecimal("3.7987"),
        "arpent" to BigDecimal("4220.87"),
    )

    fun toM2(value: Double, unit: String): Double =
        BigDecimal(value.toString()).multiply(m2PerUnit.getValue(unit)).toDouble()

    fun fromM2(m2: Double, unit: String): Double =
        BigDecimal(m2.toString()).divide(m2PerUnit.getValue(unit), 6, RoundingMode.HALF_EVEN).toDouble()
}

/** Converts amounts for display with the API's published rates (MUR per unit). */
class Converter(private val fx: Fx?) {
    private fun rate(currency: String): BigDecimal =
        if (currency == "MUR") BigDecimal.ONE else fx?.murPerUnit?.get(currency)?.let(::BigDecimal) ?: BigDecimal.ONE

    fun canConvert(to: String) = to == "MUR" || fx?.murPerUnit?.containsKey(to) == true

    fun convert(m: Money, to: String): Money {
        if (m.currency == to || !canConvert(to) || !canConvert(m.currency)) return m
        val minor = BigDecimal(m.minor).multiply(rate(m.currency)).divide(rate(to), 0, RoundingMode.HALF_EVEN)
        return Money(minor.toLong(), to)
    }
}

object Format {
    private fun symbol(currency: String) = when (currency) {
        "MUR" -> "Rs "
        "EUR" -> "€"
        "USD" -> "$"
        else -> "$currency "
    }

    /** "Rs 5,900,000" */
    fun money(m: Money): String {
        val whole = BigDecimal(m.minor).divide(BigDecimal(100), 0, RoundingMode.HALF_EVEN)
        return symbol(m.currency) + NumberFormat.getIntegerInstance(Locale.US).format(whole)
    }

    /** "Rs 5.9M", "€113k" — for map pins. */
    fun moneyShort(m: Money): String {
        val v = m.minor / 100.0
        val text = when {
            v >= 1e6 -> trim(v / 1e6) + "M"
            v >= 1e3 -> "${Math.round(v / 1e3)}k"
            else -> "${Math.round(v)}"
        }
        return symbol(m.currency) + text
    }

    private fun trim(x: Double): String =
        BigDecimal(x).setScale(if (x >= 100) 0 else 1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

    fun area(m2: Double, unit: String, labels: Map<String, String>): String {
        val v = Units.fromM2(m2, unit)
        val n = when {
            v >= 100 -> NumberFormat.getIntegerInstance(Locale.US).format(Math.round(v))
            v >= 10 -> BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
            else -> BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
        }
        return "$n ${labels.getValue(unit)}"
    }
}

/** Budget ranges, chosen to be round numbers in each display currency. */
object Budgets {
    private val bands = mapOf(
        "MUR" to listOf(5_000_000L, 15_000_000L, 30_000_000L),
        "EUR" to listOf(100_000L, 300_000L, 600_000L),
        "USD" to listOf(100_000L, 300_000L, 700_000L),
    )

    /** (minWhole, maxWhole) for band index 0..3, in [currency]. */
    fun range(currency: String, band: Int): Pair<Long?, Long?> {
        val t = bands.getValue(currency)
        return when (band) {
            0 -> null to t[0]
            1 -> t[0] to t[1]
            2 -> t[1] to t[2]
            else -> t[2] to null
        }
    }

    fun label(currency: String, band: Int, under: String): String {
        val (lo, hi) = range(currency, band)
        fun f(v: Long) = Format.moneyShort(Money(v * 100, currency))
        return when {
            lo == null -> "$under ${f(hi!!)}"
            hi == null -> "${f(lo)}+"
            else -> "${f(lo)}–${f(hi)}"
        }
    }
}
