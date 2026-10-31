package mu.propertyfinder.domain

import mu.propertyfinder.data.Locality
import mu.propertyfinder.data.Money
import java.math.BigDecimal
import java.net.URLEncoder

/**
 * "Search everywhere": the same search on the listing sites and social networks, as links.
 * Linking is all this does: nothing is copied from those sites, and the listings open on the
 * sites themselves (or in the Facebook and Instagram apps). Mirrors elsewhereLinks() in the web app.
 */
object Elsewhere {
    /** What each link can apply of the user's filters, shown under its name. */
    enum class Applies { FILTERS, REGION, TYPE, TEXT }

    data class Link(val site: String, val applies: Applies, val url: String, val text: String = "")

    data class Query(
        val type: String?,
        val q: String,
        val regions: Collection<String>,
        val minPrice: String?,
        val maxPrice: String?,
        val currency: String,
        val foreign: Boolean,
    )

    private val regionFr = mapOf("North" to "Nord", "West" to "Ouest", "Centre" to "Centre", "East" to "Est", "South" to "Sud")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun url(base: String, params: List<Pair<String, Any?>>): String {
        val qs = params.filter { it.second != null && it.second.toString().isNotEmpty() }
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v.toString())}" }
        return if (qs.isEmpty()) base else "$base?$qs"
    }

    fun links(query: Query, localities: List<Locality>, lexpressCity: (Locality) -> String?, converter: Converter): List<Link> {
        val nq = norm(query.q)
        val loc = if (nq.isEmpty()) null else localities.firstOrNull { l -> norm(l.name) == nq || l.aliases.any { norm(it) == nq } }
        val region = loc?.region ?: query.regions.singleOrNull()
        val place = loc?.name ?: query.q.trim().ifEmpty { region?.let { regionFr[it] }.orEmpty() }
        fun mur(whole: String?): Long? = whole?.let {
            converter.convert(Money(BigDecimal(it).movePointRight(2).toLong(), query.currency), "MUR").minor / 100
        }
        val lo = mur(query.minPrice)
        val hi = mur(query.maxPrice)
        val word = when (query.type) { "land" -> "terrain"; "house" -> "maison"; "apartment" -> "appartement"; else -> "immobilier" }
        val text = "$word à vendre $place Maurice".replace(Regex("\\s+"), " ")
        val lexTypes = when (query.type) {
            "land" -> "residential_land-agricultural_land"
            "apartment" -> "apartment-penthouse"
            "house" -> "villa-townhouse_duplex"
            else -> "villa-townhouse_duplex-apartment-penthouse-residential_land"
        }
        val pcType = when (query.type) { "land" -> "land"; "apartment" -> "apartments"; "house" -> "houses"; else -> "property" }
        return listOf(
            Link("lexpressproperty.com", Applies.FILTERS, url(
                "https://www.lexpressproperty.com/en/buy-mauritius/$lexTypes/",
                listOf("city" to (loc?.let(lexpressCity) ?: region?.lowercase()), "price_min" to lo, "price_max" to hi,
                       "currency" to if (lo != null || hi != null) "MUR" else null, "foreigners" to if (query.foreign) "yes" else null),
            )),
            Link("PropertyCloud", Applies.REGION, "https://www.propertycloud.mu/$pcType-for-sale" +
                (region?.let { "/" + it.lowercase() } ?: "") + (if (query.foreign) "/foreigners" else "")),
            Link("Westimmo", Applies.TYPE,
                 if (query.type == "land") "https://real-estate-mauritius.mu/en/buy-invest-mauritius/land-for-sale"
                 else "https://real-estate-mauritius.mu/en/buy-property-mauritius/"),
            Link("Facebook", Applies.TEXT, url("https://www.facebook.com/search/posts/", listOf("q" to text)), text),
            Link("Facebook Marketplace", Applies.TEXT, url("https://www.facebook.com/marketplace/search/", listOf("query" to text)), text),
            Link("Instagram", Applies.TEXT, url("https://www.instagram.com/explore/search/keyword/", listOf("q" to text)), text),
            Link("Google", Applies.TEXT, url("https://www.google.com/search", listOf("q" to text)), text),
        )
    }
}
