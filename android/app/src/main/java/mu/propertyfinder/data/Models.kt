package mu.propertyfinder.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The shapes of the static data snapshot (backend/mpf/snapshot.py) and of what the
// screens show. Everything is read from files on the phone; there is no server.

@Serializable
data class Money(val minor: Long, val currency: String)

@Serializable
data class Eligibility(
    val status: String, // "yes" | "no" | "unknown"
    val route: String? = null,
    val residency: Boolean = false,
    val reason: String,
    @SerialName("rules_version") val rulesVersion: String,
)

@Serializable
data class PriceCheck(
    val available: Boolean,
    val reason: String? = null,
    val comparables: Int = 0,
    val minimum: Int? = null,
    val scope: String? = null,
    @SerialName("diff_pct") val diffPct: Int? = null,
)

@Serializable
data class HistoryEntry(val kind: String, val at: String, val source: String, val price: Money,
                        @SerialName("old_price") val oldPrice: Money? = null)

@Serializable
data class SourceEntry(
    @SerialName("listing_id") val listingId: String,
    val source: String,
    val kind: String,
    val url: String? = null,
    val agency: String? = null,
    val price: Money,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_checked_at") val lastCheckedAt: String,
    val verified: Boolean,
    val images: List<String> = emptyList(),
)

@Serializable
data class Photo(val url: String, val kind: String, val credit: String? = null)

/** A freely licensed photo of the surroundings (Wikimedia Commons), never of the property. */
@Serializable
data class AreaPhoto(
    val url: String,
    @SerialName("page_url") val pageUrl: String,
    val author: String,
    val license: String,
)

/** One property, with everything the listing screen shows. */
@Serializable
data class Detail(
    val id: String,
    val status: String = "active",
    val type: String = "land",
    val title: String = "",
    val locality: String? = null,
    val district: String? = null,
    val region: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("location_precision") val locationPrecision: String = "locality",
    val price: Money,
    @SerialName("price_mur_minor") val priceMurMinor: Long = price.minor,
    @SerialName("plot_m2") val plotM2: Double? = null,
    @SerialName("floor_m2") val floorM2: Double? = null,
    val bedrooms: Int? = null,
    val negotiable: Boolean = false,
    val coastal: Boolean = false,
    val verified: Boolean = true,
    @SerialName("coast_distance_m") val coastDistanceM: Double = 0.0,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String = "",
    @SerialName("price_dropped") val priceDropped: Boolean = false,
    @SerialName("foreign_eligible") val foreignEligible: Boolean = false,
    @SerialName("search_text") val searchText: String = "",
    val thumb: String? = null,
    val eligibility: Eligibility? = null,
    @SerialName("price_check") val priceCheck: PriceCheck? = null,
    val history: List<HistoryEntry> = emptyList(),
    val sources: List<SourceEntry> = emptyList(),
    val photos: List<Photo> = emptyList(),
    /** True for listings the user added on this phone; they are not on the public map. */
    val mine: Boolean = false,
)

/** What list rows and map previews show (a subset of [Detail]). */
data class Card(
    val id: String,
    val type: String,
    val title: String,
    val locality: String?,
    val price: Money,
    val plotM2: Double?,
    val floorM2: Double?,
    val bedrooms: Int?,
    val sources: Int,
    val foreignEligible: Boolean,
    val unverified: Boolean,
    val priceDropped: Boolean,
    val firstSeenAt: String,
    val thumb: String?,
    val mine: Boolean,
)

fun Detail.card() = Card(id, type, title, locality, price, plotM2, floorM2, bedrooms, sources.size.coerceAtLeast(1),
                         foreignEligible, !verified, priceDropped, firstSeenAt, thumb, mine)

data class MapItem(
    val kind: String, // "pin" | "cluster" | "stack"
    val id: String? = null,
    val lat: Double,
    val lon: Double,
    val price: Money? = null,
    val unverified: Boolean = false,
    val count: Int = 1,
    val ids: List<String>? = null,
    /** Set for an official approved project (PDS, RES, IRS): shown instead of a price. */
    val scheme: String? = null,
)

data class Costs(
    val depositPct: Int,
    val termYears: Int,
    val assumedRatePct: String,
    val registrationDutyPct: String,
    val loan: Money,
    val monthly: Money,
    val upfront: Money,
)

@Serializable
data class Fx(val day: String, @SerialName("mur_per_unit") val murPerUnit: Map<String, String>)

@Serializable
data class SnapRules(val label: String, @SerialName("registration_duty_pct") val registrationDutyPct: String)

@Serializable
data class SnapMeta(
    val format: Int,
    val version: String,
    @SerialName("generated_at") val generatedAt: String,
    val count: Int,
    val rules: SnapRules,
    val fx: Fx,
    @SerialName("assumed_mortgage_rate_pct") val assumedMortgageRatePct: String,
    val attribution: List<String> = emptyList(),
    val projects: Int = 0,
)

/**
 * A development the Economic Development Board approved for sale to foreign buyers, from its
 * public lists. Official facts, not an advert: there is no price. Placed at its village centre.
 */
@Serializable
data class Project(
    val id: String,
    val scheme: String,
    @SerialName("scheme_name") val schemeName: String,
    val developer: String,
    val location: String,
    val project: String,
    val status: String,
    val website: String? = null,
    @SerialName("source_url") val sourceUrl: String,
    val locality: String? = null,
    val district: String? = null,
    val region: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("search_text") val searchText: String = "",
) {
    /** "Trou aux Biches, Pamplemousses" stays as is; "Tamarin" becomes "Tamarin · Black River". */
    val place: String
        get() {
            val words = " ${mu.propertyfinder.domain.norm(location)} "
            return if (district == null || words.contains(" ${mu.propertyfinder.domain.norm(district)} ")) location else "$location · $district"
        }
}

@Serializable
data class Locality(
    val name: String,
    val district: String,
    val region: String,
    val lat: Double,
    val lon: Double,
    val aliases: List<String> = emptyList(),
    @SerialName("search_text") val searchText: String = "",
    @SerialName("area_photos") val areaPhotos: List<AreaPhoto> = emptyList(),
    /** This place's name in lexpressproperty.com search links, when the site knows it. */
    @SerialName("lexpress_city") val lexpressCity: String? = null,
)

/** Filters as stored with a saved search. Budgets are in [currency]'s whole units. */
@Serializable
data class SearchFilters(
    val type: String? = null,
    val regions: List<String> = emptyList(),
    val q: String? = null,
    @SerialName("min_price") val minPrice: String? = null,
    @SerialName("max_price") val maxPrice: String? = null,
    val currency: String = "MUR",
    @SerialName("min_plot_m2") val minPlotM2: String? = null,
    @SerialName("min_bedrooms") val minBedrooms: Int? = null,
    @SerialName("foreign_eligible") val foreignEligible: Boolean = false,
)

@Serializable
data class SavedSearch(val id: String, val filters: SearchFilters)
