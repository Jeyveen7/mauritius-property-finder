package mu.propertyfinder.domain

import mu.propertyfinder.data.Locality
import mu.propertyfinder.data.Money
import java.math.BigDecimal
import java.net.URLEncoder

/**
 * The user's search on the listing sites and social networks, as links: every filter the
 * app has is passed in each site's own search parameters (place, type, budget, bedrooms,
 * plot size, open to foreigners), the way aggregators deep-link. Linking is all this does:
 * nothing is copied from those sites, and the listings open on the sites themselves.
 * Mirrors elsewhereLinks() in the web app.
 */
object Elsewhere {
    /** How much of the search a site's link can carry, shown under its name. */
    enum class Applies { ALL, ALL_BUT_SIZE, TEXT }

    /**
     * [inApp] sites open in the app's own browser (with "Save to my map"); Facebook and
     * Instagram open in their apps, where you sign in, and posts come back via Share.
     */
    data class Link(val site: String, val applies: Applies, val url: String, val text: String = "", val inApp: Boolean = true)

    data class Query(
        val type: String?,
        val q: String,
        val regions: Collection<String>,
        val minPrice: String?,
        val maxPrice: String?,
        val currency: String,
        val foreign: Boolean,
        val minBedrooms: Int? = null,
        val minPlotM2: Long? = null,
    )

    private val regionFr = mapOf("North" to "Nord", "West" to "Ouest", "Centre" to "Centre", "East" to "Est", "South" to "Sud")

    /** Westimmo's region groups, as its search form sends them. */
    private val westimmoRegion = mapOf(
        "West" to "78111e22,albio,flic,rivnoire,tamarin", "North" to "balac,calody,capmal,grandb,grandg,mapou,monc,perey,rivremp,troub",
        "South" to "belomb,mahebrg,Savannah", "Centre" to "bc,curep,floreal,qb", "East" to "belair,bellem,flacq,plf,rc",
    )

    /** Westimmo's property kinds; the PDS, RES, IRS and Smart City ones are those open to foreigners. */
    private fun westimmoTypes(type: String?, foreign: Boolean): String? = when (type) {
        "land" -> if (foreign) "lpds" else "ter,lpds"
        "apartment" -> if (foreign) "apds,airs,ares,WFM,ppds,pirs" else "apt,r+2,apds,airs,ares,WFM,WEZ,ppds,pirs,WFK"
        "house" -> if (foreign) "vpds,virs,vres,tpds,tirs" else "villa,vpds,virs,vres,ma,tpds,tirs"
        else -> if (foreign) "apds,airs,ares,WFM,ppds,pirs,vpds,virs,vres,tpds,tirs,lpds" else null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun url(base: String, params: List<Pair<String, Any?>>): String {
        val qs = params.filter { it.second != null && it.second.toString().isNotEmpty() }
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v.toString())}" }
        return if (qs.isEmpty()) base else "$base?$qs"
    }

    fun links(query: Query, localities: List<Locality>, converter: Converter): List<Link> {
        val nq = norm(query.q)
        val loc = if (nq.isEmpty()) null else localities.firstOrNull { l -> norm(l.name) == nq || l.aliases.any { norm(it) == nq } }
        val region = loc?.region ?: query.regions.singleOrNull()
        val place = loc?.name ?: query.q.trim().ifEmpty { region?.let { regionFr[it] }.orEmpty() }
        fun mur(whole: String?): Long? = whole?.let {
            converter.convert(Money(BigDecimal(it).movePointRight(2).toLong(), query.currency), "MUR").minor / 100
        }
        val lo = mur(query.minPrice)
        val hi = mur(query.maxPrice)
        val land = query.type == "land"
        val beds = query.minBedrooms?.takeIf { !land }
        // Plot size only means something for land and houses; flats have floor areas.
        val plot = query.minPlotM2?.takeIf { query.type != "apartment" }

        val lexTypes = when (query.type) {
            "land" -> "residential_land-agricultural_land"
            "apartment" -> "apartment-penthouse"
            "house" -> "villa-townhouse_duplex"
            else -> "villa-townhouse_duplex-apartment-penthouse-residential_land"
        }
        val lexpress = url("https://www.lexpressproperty.com/en/buy-mauritius/$lexTypes/", listOf(
            "city" to (loc?.lexpressCity ?: region?.lowercase()),
            "price_min" to lo, "price_max" to hi, "currency" to if (lo != null || hi != null) "MUR" else null,
            "foreigners" to if (query.foreign) "yes" else null,
            "filters[nb_bedrooms][ge]" to beds?.coerceAtMost(5),
            "filters[land_surface][ge]" to plot, "filters[land_unit][eq]" to if (plot != null) "m2" else null,
        ))

        val pcType = when (query.type) { "land" -> "land"; "apartment" -> "apartments"; "house" -> "houses"; else -> "property" }
        val pcPlace = loc?.propertycloudPath ?: region?.lowercase()
        val propertyCloud = url("https://www.propertycloud.mu/$pcType-for-sale" + (pcPlace?.let { "/$it" } ?: "") +
                                    (if (query.foreign) "/foreigners" else ""),
                                listOf("minprice" to lo, "maxprice" to hi, "minbeds" to beds, "size" to if (land) plot else null))

        val westimmo = url("https://real-estate-mauritius.mu/en/buy-property-mauritius/", listOf(
            "ctypmandat" to "v", "ctyp" to westimmoTypes(query.type, query.foreign),
            "localite" to loc?.westimmoLocalite, "cregion" to if (loc?.westimmoLocalite == null) region?.let(westimmoRegion::get) else null,
            "min_rooms" to beds, "price_min" to lo, "price_max" to hi,
        ))

        val word = when (query.type) { "land" -> "terrain"; "house" -> "maison"; "apartment" -> "appartement"; else -> "immobilier" }
        val text = listOfNotNull(word, "à vendre", place, beds?.let { "$it chambres" }, if (query.foreign) "étrangers" else null, "Maurice")
            .joinToString(" ").replace(Regex("\\s+"), " ")

        return listOf(
            Link("lexpressproperty.com", Applies.ALL, lexpress),
            Link("PropertyCloud", if (plot != null && !land) Applies.ALL_BUT_SIZE else Applies.ALL, propertyCloud),
            Link("Westimmo", if (plot != null) Applies.ALL_BUT_SIZE else Applies.ALL, westimmo),
            Link("Google", Applies.TEXT, url("https://www.google.com/search", listOf("q" to text)), text),
            Link("Facebook", Applies.TEXT, url("https://www.facebook.com/search/posts/", listOf("q" to text)), text, inApp = false),
            Link("Facebook Marketplace", Applies.TEXT, url("https://www.facebook.com/marketplace/search/", listOf("query" to text)), text, inApp = false),
            Link("Instagram", Applies.TEXT, url("https://www.instagram.com/explore/search/keyword/", listOf("q" to text)), text, inApp = false),
        )
    }
}
