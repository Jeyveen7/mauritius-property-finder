package mu.propertyfinder.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.propertyfinder.R
import mu.propertyfinder.domain.Budgets
import mu.propertyfinder.domain.Format
import mu.propertyfinder.domain.Units
import mu.propertyfinder.ui.Teal
import mu.propertyfinder.ui.unitLabels

private enum class Step { WHERE, WHAT, DETAILS }

private val regions = listOf("North", "West", "Centre", "East", "South")

/**
 * The guided search, one question at a time (as in Airbnb's search): where, then what kind of
 * property, then budget and the rest, ending in one obvious "Show listings" button. Answers
 * already given stay visible above and can be tapped to change.
 */
@Composable
fun SearchScreen(onClose: () -> Unit, onDone: () -> Unit) {
    val vm = exploreViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(s.filters.copy(q = s.panelQuery, projects = false)) }
    var step by remember { mutableStateOf(Step.WHERE) }
    var text by remember { mutableStateOf(draft.q) }
    val d = s.display
    val anywhere = stringResource(R.string.all_mauritius)
    val place = draft.q.ifBlank { draft.regions.joinToString(", ").ifBlank { anywhere } }
    val typeLabel = when (draft.type) {
        "land" -> stringResource(R.string.type_land); "apartment" -> stringResource(R.string.type_apartment)
        "house" -> stringResource(R.string.type_house); else -> stringResource(R.string.any_property)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { draft = Filters(); text = ""; step = Step.WHERE }) { Text(stringResource(R.string.clear_all)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step != Step.WHERE) Answered(stringResource(R.string.q_where), place) { step = Step.WHERE }
            if (step == Step.DETAILS) Answered(stringResource(R.string.q_what), typeLabel) { step = Step.WHAT }

            when (step) {
                Step.WHERE -> Card(stringResource(R.string.q_where_long)) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    fun choose(q: String, region: String? = null) {
                        draft = draft.copy(q = q, regions = region?.let { setOf(it) } ?: emptySet())
                        text = q
                        step = Step.WHAT
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { choose(text.trim()) }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    vm.towns(text).forEach { l ->
                        Row(Modifier.fillMaxWidth().clickable { choose(l.name) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Place, null, tint = Teal)
                            Text(l.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                            Text(" · ${l.district}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                    Text(stringResource(R.string.or_region), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        regions.forEach { r ->
                            FilterChip(selected = draft.q.isBlank() && draft.regions == setOf(r), onClick = { choose("", r) },
                                       label = { Text(stringResource(regionLabel(r))) })
                        }
                    }
                    TextButton(onClick = { choose("") }) {
                        Icon(Icons.Filled.Public, null)
                        Text(anywhere, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Step.WHAT -> Card(stringResource(R.string.q_what_long)) {
                    val kinds = listOf(
                        Triple("land", Icons.Filled.Landscape, R.string.type_land),
                        Triple("house", Icons.Filled.House, R.string.type_house),
                        Triple("apartment", Icons.Filled.Apartment, R.string.type_apartment),
                        Triple(null, Icons.Filled.Home, R.string.any_property),
                    )
                    kinds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (type, icon, label) ->
                                Kind(icon, stringResource(label), selected = draft.type == type, modifier = Modifier.weight(1f)) {
                                    draft = draft.copy(type = type)
                                    step = Step.DETAILS
                                }
                            }
                        }
                    }
                }
                Step.DETAILS -> {
                    val any = stringResource(R.string.any)
                    Card(stringResource(R.string.budget)) {
                        val under = stringResource(R.string.budget_under)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf<Pair<Int?, String>>(null to any) + (0..3).map { it to Budgets.label(d.currency, it, under) }).forEach { (v, l) ->
                                FilterChip(selected = draft.budgetBand == v, onClick = { draft = draft.copy(budgetBand = v) }, label = { Text(l) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("MUR" to "Rs", "EUR" to "€", "USD" to "$").forEach { (c, l) ->
                                FilterChip(selected = d.currency == c, onClick = { vm.setCurrency(c) }, label = { Text(l) })
                            }
                        }
                    }
                    if (draft.type != "land") Card(stringResource(R.string.bedrooms_homes_only)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf<Pair<Int?, String>>(null to any) + (1..4).map { it to "$it+" }).forEach { (v, l) ->
                                FilterChip(selected = draft.minBedrooms == v, onClick = { draft = draft.copy(minBedrooms = v) }, label = { Text(l) })
                            }
                        }
                    }
                    if (draft.type != "apartment") Card(stringResource(R.string.plot_size)) {
                        val labels = unitLabels()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf<Pair<Int?, String>>(null to any) + plotBands.getValue(d.plotUnit).mapIndexed { i, v ->
                                i to "≥ " + Format.area(Units.toM2(v, d.plotUnit), d.plotUnit, labels)
                            }).forEach { (v, l) ->
                                FilterChip(selected = draft.plotBand == v, onClick = { draft = draft.copy(plotBand = v) }, label = { Text(l) })
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.foreigners_can_buy), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.foreigners_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = draft.foreign, onCheckedChange = { draft = draft.copy(foreign = it) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // Always one step from the results: skip the questions you don't care about.
        Surface(shadowElevation = 8.dp) {
            Button(onClick = { vm.runSearch(draft.copy(q = if (step == Step.WHERE && text.isNotBlank()) text.trim() else draft.q)); onDone() },
                   shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) {
                Icon(Icons.Filled.Search, null)
                Text(stringResource(R.string.show_listings), fontWeight = FontWeight.Bold, fontSize = 17.sp,
                     modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

private fun regionLabel(r: String) = when (r) {
    "North" -> R.string.region_north; "West" -> R.string.region_west; "Centre" -> R.string.region_centre
    "East" -> R.string.region_east; else -> R.string.region_south
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            content()
        }
    }
}

/** A question already answered: tap to change it. */
@Composable
private fun Answered(question: String, answer: String, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(question, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(answer, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Kind(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) Teal.copy(alpha = 0.08f) else Color.White,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Teal else Color(0xFFDDE3E1)),
            modifier = modifier.height(110.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = Teal, modifier = Modifier.size(32.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
