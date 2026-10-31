package mu.propertyfinder.ui.explore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mu.propertyfinder.App
import mu.propertyfinder.data.Card
import mu.propertyfinder.data.Detail
import mu.propertyfinder.data.DisplayPrefs
import mu.propertyfinder.data.Fx
import mu.propertyfinder.data.Locality
import mu.propertyfinder.data.MapItem
import mu.propertyfinder.data.json
import kotlinx.serialization.Serializable
import mu.propertyfinder.data.Project
import mu.propertyfinder.data.SearchFilters
import mu.propertyfinder.data.card
import mu.propertyfinder.domain.Budgets
import mu.propertyfinder.domain.Converter
import mu.propertyfinder.domain.Elsewhere
import mu.propertyfinder.domain.Search
import mu.propertyfinder.domain.Units
import mu.propertyfinder.domain.norm

/** Plot-size thresholds that are round numbers in each unit. */
val plotBands = mapOf(
    "m2" to listOf(500.0, 1000.0, 4000.0),
    "perch" to listOf(10.0, 25.0, 100.0),
    "toise" to listOf(150.0, 300.0, 1000.0),
    "arpent" to listOf(0.25, 0.5, 1.0),
)

@Serializable
data class Filters(
    val type: String? = null,
    val regions: Set<String> = emptySet(),
    val q: String = "",
    val budgetBand: Int? = null,
    val plotBand: Int? = null,
    val minBedrooms: Int? = null,
    val foreign: Boolean = false,
    /** Show the official approved projects instead of listings. */
    val projects: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(type != null, regions.isNotEmpty(), budgetBand != null, plotBand != null,
                       minBedrooms != null, foreign).count { it }
}

enum class ExploreView { MAP, LIST }

data class ExploreState(
    val filters: Filters = Filters(),
    val display: DisplayPrefs = DisplayPrefs(),
    val fx: Fx? = null,
    val view: ExploreView = ExploreView.MAP,
    val mapItems: List<MapItem> = emptyList(),
    val count: Int = 0,
    val list: List<Card> = emptyList(),
    val projectList: List<Project> = emptyList(),
    val hasProjects: Boolean = false,
    val selectedProject: Project? = null,
    val sort: String = "new",
    /** Set when the user opened a stack of listings that share one map point. */
    val stackIds: List<String>? = null,
    val selected: Card? = null,
    /** False until data is on the phone, so "no results" isn't shown while loading. */
    val loaded: Boolean = false,
    val dataDate: String? = null,
    val searchSavedAt: Long = 0,
    /** The town or words the panel searched for: set on a known town or the keyboard's Search key. */
    val panelQuery: String = "",
    val panelLinks: List<Elsewhere.Link> = emptyList(),
    /** First visit: a short card saying what the app does. */
    val showWelcome: Boolean = false,
    /** Where the map flies when a town is searched: lat, lon. */
    val focus: Pair<Double, Double>? = null,
) {
    /** The user has a search (a place or any filter) whose listings are on the other sites. */
    val hasSearch: Boolean get() = panelQuery.isNotBlank() || (filters.activeCount > 0 && !filters.projects)
}

class ExploreViewModel(app: Application) : AndroidViewModel(app) {
    private val a = App.from(app)
    private val _state = MutableStateFlow(ExploreState())
    val state: StateFlow<ExploreState> = _state

    private var all: List<Detail> = emptyList()
    private var projects: List<Project> = emptyList()
    private var localities: List<Locality> = emptyList()
    private var openedOnProjects = false
    private var viewport: Pair<DoubleArray, Double>? = null

    init {
        viewModelScope.launch { a.prefs.display.collect { d -> _state.update { it.copy(display = d) }; recompute() } }
        // Public listings plus the user's own, minus anything they hid.
        viewModelScope.launch {
            combine(a.snapshots.current, a.local.state) { snap, personal ->
                Triple(snap, personal.mine, personal.hiddenIds)
            }.collect { (snap, mine, hidden) ->
                all = ((snap?.properties ?: emptyList()) + mine).filter { it.id !in hidden }
                projects = snap?.projects.orEmpty()
                localities = snap?.localities.orEmpty()
                val firstLoad = snap != null && !openedOnProjects
                if (snap != null) openedOnProjects = true
                // Pick up the search where the user left it; otherwise, until listing sources join,
                // open on the official projects rather than an empty map.
                val restored = if (firstLoad) a.prefs.lastSearch()?.let { runCatching { json.decodeFromString<Filters>(it) }.getOrNull() } else null
                val startOnProjects = firstLoad && restored == null && all.isEmpty() && projects.isNotEmpty()
                val welcome = firstLoad && !a.prefs.welcomeSeen()
                _state.update {
                    it.copy(fx = snap?.meta?.fx, loaded = snap != null, dataDate = snap?.meta?.generatedAt?.take(10),
                            hasProjects = projects.isNotEmpty(),
                            showWelcome = if (firstLoad) welcome else it.showWelcome,
                            filters = restored ?: if (startOnProjects) it.filters.copy(projects = true) else it.filters)
                }
                recompute()
                if (restored != null) {
                    commitSearch()
                }
            }
        }
    }

    private val converter get() = Converter(state.value.fx)

    /** The filters with budgets in the display currency, as stored with saved searches. */
    fun query(f: Filters = state.value.filters): SearchFilters {
        val d = state.value.display
        val (min, max) = f.budgetBand?.let { Budgets.range(d.currency, it) } ?: (null to null)
        val minPlot = f.plotBand?.let { Units.toM2(plotBands.getValue(d.plotUnit)[it], d.plotUnit) }
        return SearchFilters(
            type = f.type, regions = f.regions.toList(), q = f.q.ifBlank { null },
            minPrice = min?.toString(), maxPrice = max?.toString(), currency = d.currency,
            minPlotM2 = minPlot?.toString(), minBedrooms = f.minBedrooms, foreignEligible = f.foreign,
        )
    }

    private fun matching(): List<Detail> {
        val q = query()
        val c = converter
        return all.filter { Search.matches(it, q, c) }
    }

    private fun recompute() {
        val s = state.value
        if (s.filters.projects) return recomputeProjects()
        val found = matching()
        val listSource = s.stackIds?.let { ids -> all.filter { it.id in ids } } ?: found
        val vp = viewport
        val inView = if (vp == null) found else found.filter { p ->
            p.lat in vp.first[1]..vp.first[3] && p.lon in vp.first[0]..vp.first[2]
        }
        _state.update {
            it.copy(
                count = found.size,
                list = Search.sort(listSource, it.sort).map { d -> d.card() },
                mapItems = if (vp == null) emptyList() else Search.clusters(inView, vp.second),
                selected = it.selected?.let { sel -> found.firstOrNull { d -> d.id == sel.id }?.card() },
            )
        }
    }

    private fun recomputeProjects() {
        val f = state.value.filters
        val found = projects.filter { Search.projectMatches(it, f.regions, f.q) }
        val vp = viewport
        val inView = if (vp == null) found else found.filter { p ->
            p.lat != null && p.lon != null && p.lat in vp.first[1]..vp.first[3] && p.lon in vp.first[0]..vp.first[2]
        }
        _state.update {
            it.copy(
                count = found.size, list = emptyList(), selected = null,
                projectList = found.sortedBy { p -> norm(p.developer) },
                mapItems = if (vp == null) emptyList() else Search.projectClusters(inView, vp.second),
            )
        }
    }

    fun setFilters(f: Filters) {
        _state.update {
            it.copy(filters = f, selected = null, selectedProject = null, stackIds = null)
        }
        refreshPanel()
        persist()
        recompute()
    }

    /** Applies a saved search; its budget becomes a band again when it matches one. */
    fun applySaved(s: SearchFilters) {
        val band = (0..3).firstOrNull { i ->
            val (lo, hi) = Budgets.range(s.currency, i)
            lo?.toString() == s.minPrice && hi?.toString() == s.maxPrice
        }
        viewModelScope.launch { a.prefs.setCurrency(s.currency) }
        setFilters(Filters(type = s.type, regions = s.regions.toSet(), q = s.q.orEmpty(), budgetBand = band,
                           minBedrooms = s.minBedrooms, foreign = s.foreignEligible))
    }

    /** Towns whose name or another name starts with what was typed, best first. */
    fun towns(q: String): List<Locality> {
        val nq = norm(q)
        if (nq.length < 2) return emptyList()
        return localities
            .filter { l -> (listOf(l.name) + l.aliases).any { n -> norm(n).startsWith(nq) || norm(n).split(' ').any { it.startsWith(nq) } } }
            .sortedWith(compareBy({ if (norm(it.name).startsWith(nq)) 0 else 1 }, { it.name.length }))
            .take(6)
    }

    /** The guided search's answer: place and every filter at once. */
    fun runSearch(f: Filters) {
        _state.update { it.copy(filters = f.copy(projects = false), view = ExploreView.MAP,
                                selected = null, selectedProject = null, stackIds = null) }
        recompute()
        commitSearch()
    }

    fun clearSearch() {
        _state.update { it.copy(filters = Filters(projects = projects.isNotEmpty() && all.isEmpty()), panelQuery = "") }
        recompute()
        refreshPanel()
        persist()
    }

    fun dismissWelcome() {
        _state.update { it.copy(showWelcome = false) }
        viewModelScope.launch { a.prefs.setWelcomeSeen() }
    }

    private fun persist() = viewModelScope.launch { a.prefs.setLastSearch(json.encodeToString(Filters.serializer(), state.value.filters)) }

    /** Remembers the place searched for, points the other sites' links at it and flies the map there. */
    private fun commitSearch() {
        val q = state.value.filters.q.trim()
        val nq = norm(q)
        val town = if (nq.isEmpty()) null else localities.firstOrNull { l -> norm(l.name) == nq || l.aliases.any { norm(it) == nq } }
        _state.update {
            it.copy(panelQuery = q, focus = town?.let { t -> t.lat to t.lon } ?: it.focus)
        }
        refreshPanel()
        persist()
    }


    private fun refreshPanel() = _state.update { it.copy(panelLinks = elsewhere(it.panelQuery)) }

    fun setView(v: ExploreView) {
        _state.update { it.copy(view = v, selected = null) }
        recompute()
    }

    fun setSort(sort: String) {
        _state.update { it.copy(sort = sort) }
        recompute()
    }

    /** bbox is "west,south,east,north". */
    fun onViewport(bbox: String, zoom: Double) {
        viewport = bbox.split(",").map { it.toDouble() }.toDoubleArray() to zoom
        recompute()
    }

    fun select(id: String) = _state.update {
        it.copy(selected = all.firstOrNull { d -> d.id == id }?.card(), selectedProject = projects.firstOrNull { p -> p.id == id })
    }
    fun clearSelection() = _state.update { it.copy(selected = null, selectedProject = null) }

    fun openStack(ids: List<String>) {
        _state.update { it.copy(stackIds = ids, view = ExploreView.LIST, selected = null) }
        recompute()
    }

    fun closeStack() {
        _state.update { it.copy(stackIds = null) }
        recompute()
    }

    /** The current search as links to other sites ("Search everywhere"). */
    fun elsewhere(text: String = state.value.filters.q): List<Elsewhere.Link> {
        val q = query()
        return Elsewhere.links(
            Elsewhere.Query(q.type, text, q.regions, q.minPrice, q.maxPrice, q.currency, q.foreignEligible,
                            minBedrooms = q.minBedrooms, minPlotM2 = q.minPlotM2?.toDouble()?.toLong()),
            localities, converter,
        )
    }

    fun saveSearch() = viewModelScope.launch {
        a.local.addSearch(query())
        _state.update { it.copy(searchSavedAt = System.currentTimeMillis()) }
    }

    fun setCurrency(c: String) = viewModelScope.launch { a.prefs.setCurrency(c) }
    fun setPlotUnit(u: String) = viewModelScope.launch { a.prefs.setPlotUnit(u) }
}
