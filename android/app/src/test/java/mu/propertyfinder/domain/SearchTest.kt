package mu.propertyfinder.domain

import mu.propertyfinder.alerts.changes
import mu.propertyfinder.data.Detail
import mu.propertyfinder.data.Eligibility
import mu.propertyfinder.data.Fx
import mu.propertyfinder.data.Locality
import mu.propertyfinder.data.Money
import mu.propertyfinder.data.Personal
import mu.propertyfinder.data.Project
import mu.propertyfinder.data.SavedSearch
import mu.propertyfinder.data.SearchFilters
import mu.propertyfinder.data.SnapMeta
import mu.propertyfinder.data.SnapRules
import mu.propertyfinder.data.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val fx = Fx("2026-10-01", mapOf("EUR" to "52.5", "USD" to "45.5"))
    private val converter = Converter(fx)

    private fun plot(id: String, price: Long, lat: Double = -20.015, lon: Double = 57.595, plot: Double = 507.0,
                     text: String = "pereybere riviere du rempart", region: String = "North") =
        Detail(id = id, type = "land", title = "Plot $id", locality = "Pereybère", region = region, lat = lat, lon = lon,
               price = Money(price * 100, "MUR"), priceMurMinor = price * 100, plotM2 = plot, searchText = text,
               eligibility = Eligibility("no", reason = "citizens_and_permit_holders", rulesVersion = "t"))

    @Test fun searchIgnoresAccentsAndMatchesDistrictWords() {
        val p = plot("a", 5_900_000)
        assertTrue(Search.matches(p, SearchFilters(q = "Pereybère"), converter))
        assertTrue(Search.matches(p, SearchFilters(q = "riviere du"), converter))
        assertFalse(Search.matches(p, SearchFilters(q = "tamarin"), converter))
        assertEquals("pointe d esny", norm("Pointe d’Esny"))
    }

    @Test fun budgetInEurosAndHalfArpentSlack() {
        val p = plot("a", 5_900_000, plot = 2110.0)
        // €100k–€300k is Rs 5.25M–15.75M at 52.5.
        assertTrue(Search.matches(p, SearchFilters(minPrice = "100000", maxPrice = "300000", currency = "EUR"), converter))
        assertFalse(Search.matches(p, SearchFilters(maxPrice = "100000", currency = "EUR"), converter))
        assertTrue(Search.matches(p, SearchFilters(minPlotM2 = Units.toM2(0.5, "arpent").toString()), converter))
    }

    @Test fun clustersPinsAndStacks() {
        val far = listOf(plot("a", 1, lat = -20.0, lon = 57.5), plot("b", 2, lat = -20.4, lon = 57.7))
        assertEquals(listOf("pin", "pin"), Search.clusters(far, 12.0).map { it.kind })
        val samePoint = listOf(plot("a", 1), plot("b", 2), plot("c", 3))
        val stack = Search.clusters(samePoint, 18.0).single()
        assertEquals("stack", stack.kind)
        assertEquals(3, stack.ids!!.size)
        val near = listOf(plot("a", 1, lat = -20.015), plot("b", 2, lat = -20.016))
        assertEquals("cluster", Search.clusters(near, 9.0).single().kind)
    }

    @Test fun costsMatchAnnuityFormula() {
        val c = Search.costs(Money(840_000_000, "MUR"), 20, 20, "6.0", "5")
        assertEquals(Money(672_000_000, "MUR"), c.loan)
        assertEquals(4_814_417, c.monthly.minor) // Rs 48,144.17, as the backend computes
        assertEquals(Money(210_000_000, "MUR"), c.upfront)
    }

    @Test fun postReaderFindsFactsAndPlace() {
        val places = listOf(Locality("Pointe aux Piments", "Pamplemousses", "North", -20.07, 57.53),
                            Locality("Grand Baie", "Rivière du Rempart", "North", -20.01, 57.58, aliases = listOf("Grand Bay")))
        val a = PostReader.read("Terrain résidentiel à vendre Pointe aux Piments, 12 perches. Rs 4.2M à discuter. https://fb.com/p/1", places)
        assertEquals("land", a.type); assertEquals("Pointe aux Piments", a.locality?.name)
        assertEquals(12.0, a.sizeValue!!, 0.0); assertEquals("perch", a.sizeUnit)
        assertEquals(4_200_000L, a.priceWhole); assertEquals("MUR", a.currency); assertTrue(a.negotiable)
        val b = PostReader.read("Villa 3 chambres Grand Bay, 1 arpent, €850k", places)
        assertEquals("house", b.type); assertEquals("Grand Baie", b.locality?.name)
        assertEquals(850_000L, b.priceWhole); assertEquals("EUR", b.currency); assertEquals(3, b.bedrooms)
        val c = PostReader.read("Lavant terin 15 lapers Grand Baie Rs 8,400,000", places)
        assertEquals("perch", c.sizeUnit); assertEquals(8_400_000L, c.priceWhole)
    }

    @Test fun alertsComeFromComparingSnapshots() {
        fun snap(vararg ps: Detail) = Snapshot(SnapMeta(1, "v", "", ps.size, SnapRules("t", "5"), fx, "6.0"), ps.toList(), emptyList())
        val old = snap(plot("saved", 6_000_000), plot("sold", 3_000_000))
        val new = snap(plot("saved", 5_500_000), plot("fresh", 4_000_000))
        val personal = Personal(savedIds = setOf("saved", "sold"),
                                searches = listOf(SavedSearch("s", SearchFilters(type = "land", q = "pereybere"))),
                                lastSeenPrices = mapOf("saved" to 600_000_000L, "sold" to 300_000_000L))
        val c = changes(old, new, personal)
        assertEquals(listOf("fresh"), c.newMatches)
        assertEquals(listOf("saved"), c.priceDrops)
        assertEquals(listOf("sold"), c.gone)
    }

    @Test fun projectsSearchByPlaceAndClusterWithSchemeLabels() {
        fun p(id: String, place: String, region: String, lat: Double) = Project(
            id, "PDS", "Property Development Scheme", "Dev $id", place, "12 villas", "PDS certificate",
            sourceUrl = "https://edbmauritius.org/x.pdf", locality = place, district = "D", region = region,
            lat = lat, lon = 57.5, searchText = norm("Dev $id $place 12 villas"))
        val list = listOf(p("a", "Grand Baie", "North", -20.0), p("b", "Tamarin", "West", -20.33))
        assertEquals(listOf("a"), list.filter { Search.projectMatches(it, emptyList(), "grand ba") }.map { it.id })
        assertEquals(listOf("b"), list.filter { Search.projectMatches(it, listOf("West"), null) }.map { it.id })
        val pins = Search.projectClusters(list, 14.0)
        assertEquals(listOf("PDS", "PDS"), pins.map { it.scheme })
        assertEquals("Grand Baie · D", list[0].place)
    }
}
