package mu.propertyfinder.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mu.propertyfinder.App
import mu.propertyfinder.data.AreaPhoto
import mu.propertyfinder.data.Costs
import mu.propertyfinder.data.Detail
import mu.propertyfinder.data.DisplayPrefs
import mu.propertyfinder.data.Fx
import mu.propertyfinder.domain.Search

data class DetailState(
    val detail: Detail? = null,
    val missing: Boolean = false,
    val display: DisplayPrefs = DisplayPrefs(),
    val fx: Fx? = null,
    val saved: Boolean = false,
    val hidden: Boolean = false,
    val depositPct: Int = 20,
    val termYears: Int = 20,
    val costs: Costs? = null,
    val areaPhotos: List<AreaPhoto> = emptyList(),
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val a = App.from(app)
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state
    private var id: String? = null
    private var rate = "6.0"
    private var duty = "5"

    init {
        viewModelScope.launch { a.prefs.display.collect { d -> _state.update { it.copy(display = d) } } }
        viewModelScope.launch {
            combine(a.snapshots.current, a.local.state) { snap, personal -> snap to personal }.collect { (snap, personal) ->
                val pid = id ?: return@collect
                snap?.let { rate = it.meta.assumedMortgageRatePct; duty = it.meta.rules.registrationDutyPct }
                val d = snap?.byId?.get(pid) ?: personal.mine.firstOrNull { it.id == pid }
                _state.update {
                    it.copy(detail = d, missing = d == null && snap != null, fx = snap?.meta?.fx,
                            saved = pid in personal.savedIds, hidden = pid in personal.hiddenIds,
                            areaPhotos = d?.locality?.let { name -> snap?.localityByName?.get(name)?.areaPhotos }.orEmpty())
                }
                recost()
            }
        }
    }

    fun load(id: String) {
        if (this.id == id) return
        this.id = id
        // Re-emit so the collector above picks the new id up.
        viewModelScope.launch { a.snapshots.load() }
        val snap = a.snapshots.current.value
        val personal = a.local.state.value
        val d = snap?.byId?.get(id) ?: personal.mine.firstOrNull { it.id == id }
        snap?.let { rate = it.meta.assumedMortgageRatePct; duty = it.meta.rules.registrationDutyPct }
        _state.update {
            it.copy(detail = d, missing = d == null && snap != null, fx = snap?.meta?.fx, saved = id in personal.savedIds,
                    areaPhotos = d?.locality?.let { name -> snap?.localityByName?.get(name)?.areaPhotos }.orEmpty())
        }
        recost()
    }

    fun setCosts(depositPct: Int = state.value.depositPct, termYears: Int = state.value.termYears) {
        _state.update { it.copy(depositPct = depositPct, termYears = termYears) }
        recost()
    }

    private fun recost() = _state.update { s ->
        s.copy(costs = s.detail?.let { Search.costs(it.price, s.depositPct, s.termYears, rate, duty) })
    }

    fun toggleSave() = viewModelScope.launch {
        val d = state.value.detail ?: return@launch
        a.local.toggleSaved(d.id, d.priceMurMinor)
    }

    /** Hides a sold or wrong listing on this phone. */
    fun hide() = viewModelScope.launch { id?.let { a.local.hide(it) } }

    fun deleteMine() = viewModelScope.launch { id?.let { a.local.deleteMine(it) } }
}
