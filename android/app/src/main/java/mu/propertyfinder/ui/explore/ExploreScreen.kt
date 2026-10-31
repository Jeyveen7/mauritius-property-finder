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
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.height
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
fun ExploreScreen(onOpen: (String) -> Unit, onSearch: () -> Unit, onResults: () -> Unit) {
    val vm = exploreViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val converter = remember(s.fx) { Converter(s.fx) }
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
    val searching = s.hasSearch

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
                            else stringResource(R.string.n_saved_here, s.count),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (s.stackIds != null) IconButton(onClick = vm::closeStack) { Icon(Icons.Filled.Close, null) }
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

        // One way in, as in Airbnb or Rightmove: a single search button that opens the guided search.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, color = Color.White,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSearch)) {
                Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        if (searching) {
                            Text(s.panelQuery.ifBlank { stringResource(R.string.all_mauritius) }, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(searchSummary(s, withPlace = false).ifBlank { stringResource(R.string.any_property) },
                                 fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        } else {
                            Text(stringResource(R.string.welcome_start), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.search_pill_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (searching) IconButton(onClick = vm::clearSearch) { Icon(Icons.Filled.Close, stringResource(R.string.clear_search)) }
                    else Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Filled.Tune, null, tint = Color.White, modifier = Modifier.padding(10.dp))
                    }
                }
            }
            if (s.hasProjects) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.filters.projects, onClick = { vm.setFilters(s.filters.copy(projects = !s.filters.projects)) },
                           label = { Text(stringResource(R.string.approved_projects)) },
                           colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = Color.White))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.view == ExploreView.MAP) {
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
                // The search's listings live on the sites: one obvious button takes you there, full screen.
                if (searching && s.selected == null && s.selectedProject == null) {
                    Button(onClick = onResults, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.Public, null)
                        Text(stringResource(R.string.see_listings), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searching && !s.filters.projects) Surface(shape = RoundedCornerShape(50), shadowElevation = 6.dp) {
                        IconButton(onClick = { saveSearch() }) {
                            Icon(Icons.Filled.NotificationsNone, contentDescription = stringResource(R.string.save_search))
                        }
                    }
                    if (s.count > 0) ExtendedFloatingActionButton(
                        onClick = { vm.setView(ExploreView.LIST) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        text = { Text(stringResource(if (s.filters.projects) R.string.list_projects else R.string.list_saved, s.count)) },
                        containerColor = Color(0xFF12201F), contentColor = Color.White,
                    )
                }
            } else {
                ExtendedFloatingActionButton(
                    onClick = { vm.setView(ExploreView.MAP) },
                    icon = { Icon(Icons.Filled.Map, null) }, text = { Text(stringResource(R.string.map)) },
                    containerColor = Color(0xFF12201F), contentColor = Color.White,
                )
            }
        }
    }

    if (s.showWelcome) {
        WelcomeDialog(projects = s.hasProjects, onStart = { vm.dismissWelcome(); onSearch() }, onDismiss = vm::dismissWelcome)
    }
}

/** "Vacoas · Apartments · 3+ bedrooms · Rs 5M – 15M · Open to foreigners": what the panel searched for. */
@Composable
fun searchSummary(s: ExploreState, withPlace: Boolean = true): String {
    val f = s.filters
    val parts = mutableListOf<String>()
    if (withPlace) parts += s.panelQuery.ifBlank { f.regions.joinToString(", ").ifBlank { stringResource(R.string.all_mauritius) } }
    f.type?.let { parts += stringResource(when (it) { "land" -> R.string.type_land; "apartment" -> R.string.type_apartment; else -> R.string.type_house }) }
    if (f.type != "land") f.minBedrooms?.let { parts += stringResource(R.string.bedrooms_min, it) }
    f.budgetBand?.let { parts += Budgets.label(s.display.currency, it, stringResource(R.string.budget_under)) }
    f.plotBand?.let { i -> parts += "≥ " + Format.area(mu.propertyfinder.domain.Units.toM2(plotBands.getValue(s.display.plotUnit)[i], s.display.plotUnit), s.display.plotUnit, unitLabels()) }
    if (f.foreign) parts += stringResource(R.string.foreigners_can_buy)
    return parts.joinToString(" · ")
}
