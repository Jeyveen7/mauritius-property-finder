package mu.propertyfinder.domain

import mu.propertyfinder.data.Costs
import mu.propertyfinder.data.Detail
import mu.propertyfinder.data.MapItem
import mu.propertyfinder.data.Money
import mu.propertyfinder.data.SearchFilters
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Lower-case, no accents or punctuation: "Pointe d'Esny" -> "pointe d esny". */
fun norm(text: String?): String =
    Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

/** Search and filtering over the snapshot, on the phone (mirrors backend/mpf/search.py). */
object Search {
    fun matches(p: Detail, f: SearchFilters, converter: Converter): Boolean {
        if (f.type != null && p.type != f.type) return false
        if (f.regions.isNotEmpty() && p.region !in f.regions) return false
        f.minPrice?.let { if (p.priceMurMinor < toMurMinor(it, f.currency, converter)) return false }
        f.maxPrice?.let { if (p.priceMurMinor >= toMurMinor(it, f.currency, converter)) return false }
        // 1% slack so "half an arpent" plots match a half-arpent minimum.
        f.minPlotM2?.toDoubleOrNull()?.let { if ((p.plotM2 ?: 0.0) < it * 0.99) return false }
        f.minBedrooms?.let { if (p.type == "land" || (p.bedrooms ?: 0) < it) return false }
        if (f.foreignEligible && !p.foreignEligible) return false
        val q = norm(f.q)
        if (q.isNotEmpty()) {
            val text = p.searchText.ifBlank { norm("${p.locality} ${p.district}") }
            // Every word typed must start a word of the place name, district or alias.
            val words = text.split(' ')
            if (!text.contains(q) && !q.split(' ').all { w -> words.any { it.startsWith(w) } }) return false
        }
        return true
    }

    private fun toMurMinor(whole: String, currency: String, converter: Converter): Long =
        converter.convert(Money(BigDecimal(whole).movePointRight(2).toLong(), currency), "MUR").minor

    fun sort(list: List<Detail>, order: String): List<Detail> = when (order) {
        "price" -> list.sortedBy { it.priceMurMinor }
        "value" -> list.sortedBy { p ->
            val area = (if (p.type == "land") p.plotM2 else p.floorM2) ?: return@sortedBy Double.MAX_VALUE
            p.priceMurMinor / area
        }
        else -> list.sortedByDescending { it.firstSeenAt }
    }

    private fun mercY(lat: Double): Double = Math.toDegrees(ln(tan(PI / 4 + Math.toRadians(lat) / 2)))

    /**
     * Groups properties into cells about [cellPx] screen pixels wide at [zoom]. One property
     * is a pin; several at one exact point are a "stack" (no zoom separates them, so the
     * app lists them); anything else is a cluster to zoom into.
     */
    fun clusters(items: List<Detail>, zoom: Double, cellPx: Int = 64): List<MapItem> {
        val cell = 360.0 / (256 * 2.0.pow(zoom)) * cellPx
        return items.groupBy { floor(it.lon / cell).toLong() to floor(mercY(it.lat) / cell).toLong() }.values.map { group ->
            if (group.size == 1) {
                val p = group[0]
                MapItem("pin", p.id, p.lat, p.lon, p.price, unverified = !p.verified)
            } else {
                val samePoint = group.map { "%.6f,%.6f".format(it.lat, it.lon) }.toSet().size == 1
                MapItem(if (samePoint) "stack" else "cluster", null, group.map { it.lat }.average(), group.map { it.lon }.average(),
                        count = group.size, ids = if (samePoint) group.map { it.id } else null)
            }
        }
    }

    /** Monthly repayment and cash needed up front: an estimate, not a loan offer. */
    fun costs(price: Money, depositPct: Int, termYears: Int, ratePct: String, dutyPct: String): Costs {
        val mc = MathContext.DECIMAL64
        val p = BigDecimal(price.minor)
        val loan = p.multiply(BigDecimal(100 - depositPct)).divide(BigDecimal(100), mc)
        val r = BigDecimal(ratePct).divide(BigDecimal(1200), mc)
        val n = termYears * 12
        val monthly = if (r.signum() == 0) loan.divide(BigDecimal(n), mc)
        else loan.multiply(r).divide(BigDecimal.ONE.subtract(BigDecimal.ONE.add(r).pow(-n, mc)), mc)
        val upfront = p.multiply(BigDecimal(depositPct)).add(p.multiply(BigDecimal(dutyPct))).divide(BigDecimal(100), mc)
        fun m(x: BigDecimal) = Money(x.setScale(0, RoundingMode.HALF_EVEN).toLong(), price.currency)
        return Costs(depositPct, termYears, ratePct, dutyPct, m(loan), m(monthly), m(upfront))
    }
}
