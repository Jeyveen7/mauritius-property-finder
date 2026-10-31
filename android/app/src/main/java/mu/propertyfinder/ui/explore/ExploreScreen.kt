package mu.propertyfinder.ui.explore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Place
import mu.propertyfinder.data.Locality
import mu.propertyfinder.domain.Budgets
import mu.propertyfinder.ui.unitLabels
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mu.propertyfinder.R
import mu.propertyfinder.domain.Converter
import mu.propertyfinder.domain.Format
import mu.propertyfinder.ui.ChoiceChips
import mu.propertyfinder.ui.ProjectCardRow
import mu.propertyfinder.ui.PropertyCardRow

/** One ExploreViewModel per activity, so Saved can apply a search to it. */
@Composable
fun exploreViewModel(): ExploreViewModel = viewModel(LocalContext.current as ComponentActivity)

@Composable
fun ExploreScreen(onOpen: (String) -> Unit, onBrowse: (Int) -> Unit, onSave: (String) -> Unit) {
    val vm = exploreViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val converter = remember(s.fx) { Converter(s.fx) }
    var sheetOpen by remember { mutableStateOf(false) }
    var elsewhereOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val elsewhereButton = @Composable {
        Surface(shape = RoundedCornerShape(50), shadowElevation = 6.dp) {
            IconButton(onClick = { elsewhereOpen = true }) {
                Icon(Icons.Filled.Public, contentDescription = stringResource(R.string.everywhere))
            }
        }
    }
    val context = LocalContext.current
    // Alerts for saved searches arrive as notifications; Android 13+ asks for permission.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val saveSearch = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.saveSearch()
    }
    val savedMessage = stringResource(R.string.search_saved)
    LaunchedEffect(s.searchSavedAt) {
        if (s.searchSavedAt > 0) Toast.makeText(context, savedMessage, Toast.LENGTH_LONG).show()
    }

    Box(Modifier.fillMaxSize()) {
        if (s.view == ExploreView.MAP) {
            PropertyMap(
                items = s.mapItems,
                label = { item -> item.scheme ?: item.price?.let { Format.moneyShort(converter.convert(it, s.display.currency)) }.orEmpty() },
                onViewport = vm::onViewport,
                onPin = { vm.select(it) },
                onStack = vm::openStack,
                focus = s.focus,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 128.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s.filters.projects) {
                    item {
                        Text(stringResource(R.string.n_projects, s.count), style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.ExtraBold)
                        Text(stringResource(R.string.projects_intro), color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.padding(top = 4.dp))
                    }
                    items(s.projectList, key = { it.id }) { p -> ProjectCardRow(p) { onOpen(p.id) } }
                } else item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (s.stackIds != null) stringResource(R.string.stack_title, s.list.size)
                            else stringResource(R.string.n_properties, s.count),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (s.stackIds != null) IconButton(onClick = vm::closeStack) { Icon(Icons.Filled.Close, null) }
                        else OutlinedButton(onClick = { saveSearch() }) {
                            Icon(Icons.Filled.NotificationsNone, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.save_search))
                        }
                    }
                    ChoiceChips(
                        listOf("new" to stringResource(R.string.sort_new), "price" to stringResource(R.string.sort_price),
                               "value" to stringResource(R.string.sort_value)),
                        s.sort, vm::setSort,
                    )
                }
                if (!s.filters.projects) items(s.list, key = { it.id }) { card ->
                    PropertyCardRow(card, s.display, converter) { onOpen(card.id) }
                }
            }
        }

        SearchHeader(
            query = s.filters.q, onQuery = vm::setQuery, onSearch = { vm.commitSearch() }, activeFilters = s.filters.activeCount,
            suggestions = s.suggestions, onPick = vm::pickTown, focusRequester = searchFocus,
            onFilters = { sheetOpen = true },
            quickChips = {
                val f = s.filters
                listOf("land" to R.string.type_land, "apartment" to R.string.type_apartment, "house" to R.string.type_house)
                    .forEach { (t, label) ->
                        FilterChip(selected = f.type == t && !f.projects,
                                   onClick = { vm.setFilters(f.copy(type = if (f.type == t) null else t, projects = false)) },
                                   label = { Text(stringResource(label)) })
                    }
                FilterChip(selected = f.foreign && !f.projects, onClick = { vm.setFilters(f.copy(foreign = !f.foreign, projects = false)) },
                           label = { Text(stringResource(R.string.foreigners_can_buy)) })
                if (s.hasProjects) {
                    FilterChip(selected = f.projects, onClick = { vm.setFilters(f.copy(projects = !f.projects)) },
                               label = { Text(stringResource(R.string.approved_projects)) })
                }
            },
            opaque = s.view == ExploreView.LIST,
        )

        if (s.panel == Panel.OPEN) {
            // The other sites' own results for this search, under the map. Their pages, unchanged.
            Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), shadowElevation = 12.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.66f)) {
                Column {
                    Text(searchSummary(s), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
                    SiteBrowser(s.panelLinks, 0, onSave, onBackOut = vm::collapsePanel, modifier = Modifier.fillMaxSize()) {
                        IconButton(onClick = vm::collapsePanel) {
                            Icon(Icons.Filled.ExpandMore, stringResource(R.string.panel_collapse))
                        }
                    }
                }
            }
        }

        if (s.panel != Panel.OPEN) Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.panel == Panel.BAR) {
                Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, color = Color.White,
                        modifier = Modifier.fillMaxWidth().clickable { vm.openPanel() }) {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(if (s.panelQuery.isBlank()) stringResource(R.string.panel_bar_any)
                                 else stringResource(R.string.panel_bar, s.panelQuery), fontWeight = FontWeight.Bold)
                            Text(s.panelLinks.filter { it.inApp }.joinToString(" · ") { it.site }, fontSize = 12.sp,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = vm::closePanel) { Icon(Icons.Filled.Close, stringResource(R.string.panel_hide)) }
                    }
                }
            }
            // With the other sites' panel on offer, it already answers "nothing here".
            if (s.view == ExploreView.MAP && s.loaded && s.count == 0 && s.panel == Panel.HIDDEN) {
                Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.no_results))
                        OutlinedButton(onClick = { vm.setFilters(Filters()) }) { Text(stringResource(R.string.clear_filters)) }
                    }
                }
            }
            if (s.view == ExploreView.MAP) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    elsewhereButton()
                    if (!s.filters.projects) Surface(shape = RoundedCornerShape(50), shadowElevation = 6.dp) {
                        IconButton(onClick = { saveSearch() }) {
                            Icon(Icons.Filled.NotificationsNone, contentDescription = stringResource(R.string.save_search))
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { vm.setView(ExploreView.LIST) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        text = { Text(stringResource(if (s.filters.projects) R.string.list_projects else R.string.list_count, s.count)) },
                        containerColor = Color(0xFF12201F), contentColor = Color.White,
                    )
                }
                s.selectedProject?.let { p ->
                    Box {
                        ProjectCardRow(p) { onOpen(p.id) }
                        IconButton(onClick = vm::clearSelection, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
                s.selected?.let { card ->
                    Box {
                        PropertyCardRow(card, s.display, converter) { onOpen(card.id) }
                        IconButton(onClick = vm::clearSelection, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    elsewhereButton()
                    ExtendedFloatingActionButton(
                        onClick = { vm.setView(ExploreView.MAP) },
                        icon = { Icon(Icons.Filled.Map, null) }, text = { Text(stringResource(R.string.map)) },
                        containerColor = Color(0xFF12201F), contentColor = Color.White,
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        FilterSheet(state = s, vm = vm, onDismiss = { sheetOpen = false })
    }
    if (s.showWelcome) {
        WelcomeDialog(projects = s.hasProjects, onStart = { vm.dismissWelcome(); runCatching { searchFocus.requestFocus() } },
                      onDismiss = vm::dismissWelcome)
    }
    if (elsewhereOpen) {
        ElsewhereSheet(links = vm.elsewhere(), onDismiss = { elsewhereOpen = false },
                       onBrowse = { i -> elsewhereOpen = false; onBrowse(i) })
    }
}

@Composable
private fun SearchHeader(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    activeFilters: Int,
    onFilters: () -> Unit,
    quickChips: @Composable () -> Unit,
    opaque: Boolean,
    suggestions: List<Locality> = emptyList(),
    onPick: (Locality) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val focus = LocalFocusManager.current
    Surface(color = if (opaque) MaterialTheme.colorScheme.background else Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp, modifier = Modifier.weight(1f)) {
                    TextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(); focus.clearFocus() }),
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.clear_search))
                            }
                        },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
                    )
                }
                Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp) {
                    IconButton(onClick = onFilters) {
                        BadgedBox(badge = {
                            if (activeFilters > 0) androidx.compose.material3.Badge { Text("$activeFilters") }
                        }) { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.filters)) }
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                // Towns as you type: one tap searches there and puts the keyboard away.
                Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp, color = Color.White) {
                    Column {
                        suggestions.forEach { l ->
                            Row(Modifier.fillMaxWidth().clickable { focus.clearFocus(); onPick(l) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                                Text(l.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                                Text(" · ${l.district}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickChips()
            }
        }
    }
}

/** "Vacoas · Apartments · 3+ bedrooms · Rs 5M – 15M · Open to foreigners": what the panel searched for. */
@Composable
private fun searchSummary(s: ExploreState): String {
    val f = s.filters
    val parts = mutableListOf<String>()
    parts += s.panelQuery.ifBlank { f.regions.joinToString(", ").ifBlank { stringResource(R.string.all_mauritius) } }
    f.type?.let { parts += stringResource(when (it) { "land" -> R.string.type_land; "apartment" -> R.string.type_apartment; else -> R.string.type_house }) }
    if (f.type != "land") f.minBedrooms?.let { parts += stringResource(R.string.bedrooms_min, it) }
    f.budgetBand?.let { parts += Budgets.label(s.display.currency, it, stringResource(R.string.budget_under)) }
    f.plotBand?.let { i -> parts += "≥ " + Format.area(mu.propertyfinder.domain.Units.toM2(plotBands.getValue(s.display.plotUnit)[i], s.display.plotUnit), s.display.plotUnit, unitLabels()) }
    if (f.foreign) parts += stringResource(R.string.foreigners_can_buy)
    return parts.joinToString(" · ")
}
