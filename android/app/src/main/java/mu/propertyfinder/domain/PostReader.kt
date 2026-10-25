package mu.propertyfinder.domain

import mu.propertyfinder.data.Locality

/** What the reader found in a shared post; the user checks and corrects it. */
data class ReadPost(
    val type: String?,
    val locality: Locality?,
    val sizeValue: Double?,
    val sizeUnit: String?,
    val priceWhole: Long?,
    val currency: String?,
    val negotiable: Boolean,
    val bedrooms: Int?,
)

/**
 * Reads the facts from a property post in French, English or Kreol, on the phone
 * (mirrors backend/mpf/extract/rules.py). Never keeps names or phone numbers.
 */
object PostReader {
    private val types = listOf(
        "apartment" to Regex("\\b(appartement|apartment|flat|penthouse|duplex)\\b"),
        "house" to Regex("\\b(maison|villa|house|bungalow|lakaz)\\b"),
        "land" to Regex("\\b(terrain|terin|land|plot|morcellement|lot)\\b"),
    )
    private val size = Regex("(\\d+(?:[.,]\\d+)?)\\s*(m2|m²|sqm|metres? carres?|perches?|lapers|toises?|arpents?)")
    private const val NUM = "(\\d{1,3}(?:[ ,.]\\d{3})+|\\d+(?:[.,]\\d+)?)"
    private val prices = listOf(
        "MUR" to Regex("(?:rs\\.?|mur|roupies?)\\s*$NUM\\s*(m\\b|millions?|mil\\b|k\\b)?"),
        "EUR" to Regex("(?:€|eur(?:os?)?)\\s*$NUM\\s*(m\\b|millions?|k\\b)?"),
        "USD" to Regex("(?:\\$|usd)\\s*$NUM\\s*(m\\b|millions?|k\\b)?"),
    )
    private val negotiable = Regex("\\b(a discuter|negociable|nego|negotiable|ono)\\b")
    private val beds = Regex("\\b(\\d)\\s*(?:chambres?|bed(?:room)?s?|ch)\\b")

    private fun unit(word: String): String? = when {
        word.startsWith("perch") || word == "lapers" -> "perch"
        word.startsWith("toise") -> "toise"
        word.startsWith("arpent") -> "arpent"
        word.startsWith("m") || word == "sqm" -> "m2"
        else -> null
    }

    private fun number(digits: String, suffix: String?): Long? {
        val d = digits.replace(" ", "")
        val value = if (Regex("\\d{1,3}([.,]\\d{3})+").matches(d)) d.replace(Regex("[.,]"), "").toDouble()
                    else d.replace(',', '.').toDoubleOrNull() ?: return null
        val mul = when { suffix == null -> 1.0; suffix.startsWith("k") -> 1e3; else -> 1e6 }
        return Math.round(value * mul)
    }

    fun read(text: String, localities: List<Locality>): ReadPost {
        // Lower-case without accents, but keeping digits' separators and currency signs.
        val lowered = java.text.Normalizer.normalize(text.lowercase().replace("²", "2"), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val n = norm(text)
        val type = types.firstOrNull { it.second.containsMatchIn(n) }?.first
        val s = size.find(lowered)
        var price: Long? = null
        var currency: String? = null
        for ((cur, rx) in prices) {
            val m = rx.find(lowered) ?: continue
            price = number(m.groupValues[1], m.groupValues[2].ifEmpty { null }) ?: continue
            currency = cur
            break
        }
        // Longest place name or alias found in the text wins ("Pointe aux Piments" over "Piments").
        val place = localities.flatMap { loc -> (listOf(loc.name) + loc.aliases).map { norm(it) to loc } }
            .filter { (key, _) -> key.isNotEmpty() && Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(n) }
            .maxByOrNull { it.first.length }?.second
        return ReadPost(
            type = type, locality = place,
            sizeValue = s?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull(),
            sizeUnit = s?.groupValues?.get(2)?.let { unit(norm(it)) },
            priceWhole = price, currency = currency,
            negotiable = negotiable.containsMatchIn(n),
            bedrooms = beds.find(n)?.groupValues?.get(1)?.toInt(),
        )
    }
}
