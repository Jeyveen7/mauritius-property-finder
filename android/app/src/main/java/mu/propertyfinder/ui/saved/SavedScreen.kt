package mu.propertyfinder.ui.saved

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mu.propertyfinder.App
import mu.propertyfinder.R
import mu.propertyfinder.data.Card
import mu.propertyfinder.data.DisplayPrefs
import mu.propertyfinder.data.Fx
import mu.propertyfinder.data.SavedSearch
import mu.propertyfinder.data.card
import mu.propertyfinder.domain.Search
import kotlinx.coroutines.flow.combine
import mu.propertyfinder.domain.Converter
import mu.propertyfinder.ui.PropertyCardRow
import mu.propertyfinder.ui.explore.exploreViewModel

data class SavedState(
    val searches: List<Pair<SavedSearch, Int>> = emptyList(),
    val homes: List<Card> = emptyList(),
    val mine: List<Card> = emptyList(),
    val gone: Int = 0,
    val display: DisplayPrefs = DisplayPrefs(),
    val fx: Fx? = null,
)

class SavedViewModel(app: Application) : AndroidViewModel(app) {
    private val a = App.from(app)
    private val _state = MutableStateFlow(SavedState())
    val state: StateFlow<SavedState> = _state

    init {
        viewModelScope.launch { a.prefs.display.collect { d -> _state.update { it.copy(display = d) } } }
        viewModelScope.launch {
            combine(a.snapshots.current, a.local.state) { snap, personal -> snap to personal }.collect { (snap, personal) ->
                val all = snap?.properties.orEmpty()
                val converter = Converter(snap?.meta?.fx)
                _state.update {
                    it.copy(
                        fx = snap?.meta?.fx,
                        searches = personal.searches.map { s -> s to all.count { p -> Search.matches(p, s.filters, converter) } },
                        homes = personal.savedIds.mapNotNull { id -> snap?.byId?.get(id)?.card() },
                        mine = personal.mine.map { m -> m.card() },
                        gone = if (snap == null) 0 else personal.savedIds.count { id -> id !in snap.byId },
                    )
                }
            }
        }
    }

    fun deleteSearch(id: String) = viewModelScope.launch { a.local.deleteSearch(id) }
}

/** A short description of a saved search, e.g. "Land · North, West · “tamarin” · Foreigners can buy". */
@Composable
private fun describe(s: SavedSearch): String {
    val f = s.filters
    val parts = mutableListOf(
        when (f.type) {
            "land" -> stringResource(R.string.type_land)
            "apartment" -> stringResource(R.string.type_apartment)
            "house" -> stringResource(R.string.type_house)
            else -> stringResource(R.string.type_all)
        },
    )
    if (f.regions.isNotEmpty()) parts += f.regions.joinToString(", ")
    f.q?.let { parts += "“$it”" }
    if (f.foreignEligible) parts += stringResource(R.string.foreigners_can_buy)
    return parts.joinToString(" · ")
}

@Composable
fun SavedScreen(onOpen: (String) -> Unit, onApplySearch: () -> Unit) {
    val vm: SavedViewModel = viewModel()
    val explore = exploreViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val converter = remember(s.fx) { Converter(s.fx) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) }
        item { Text(stringResource(R.string.saved_searches), fontWeight = FontWeight.ExtraBold) }
        if (s.searches.isEmpty()) item { Text(stringResource(R.string.saved_searches_empty)) }
        items(s.searches, key = { it.first.id }) { (search, matches) ->
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { explore.applySaved(search.filters); onApplySearch() }.padding(14.dp)) {
                        Text(describe(search), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.matches_now, matches), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.deleteSearch(search.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.delete))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.saved_homes), fontWeight = FontWeight.ExtraBold) }
        if (s.homes.isEmpty()) item { Text(stringResource(R.string.saved_homes_empty)) }
        if (s.gone > 0) item { Text(stringResource(R.string.no_longer_listed, s.gone)) }
        items(s.homes, key = { it.id }) { card -> PropertyCardRow(card, s.display, converter) { onOpen(card.id) } }
        if (s.mine.isNotEmpty()) item { Text(stringResource(R.string.my_listings), fontWeight = FontWeight.ExtraBold) }
        items(s.mine, key = { "mine-" + it.id }) { card -> PropertyCardRow(card, s.display, converter) { onOpen(card.id) } }
    }
}
