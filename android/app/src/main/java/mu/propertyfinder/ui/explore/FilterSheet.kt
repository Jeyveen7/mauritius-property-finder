package mu.propertyfinder.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mu.propertyfinder.R
import mu.propertyfinder.domain.Budgets
import mu.propertyfinder.ui.ChoiceChips
import mu.propertyfinder.ui.unitLabels
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(state: ExploreState, vm: ExploreViewModel, onDismiss: () -> Unit) {
    val f = state.filters
    val d = state.display
    val any = stringResource(R.string.any)
    val units = unitLabels()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleLarge,
                     fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setFilters(Filters(q = f.q)) }) { Text(stringResource(R.string.reset)) }
            }

            Section(R.string.region) {
                // Multi-select: tapping toggles a region in or out.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("North", "West", "Centre", "East", "South").forEach { r ->
                        FilterChip(
                            selected = r in f.regions,
                            onClick = { vm.setFilters(f.copy(regions = if (r in f.regions) f.regions - r else f.regions + r)) },
                            label = { Text(r) },
                        )
                    }
                }
            }

            Section(R.string.budget) {
                val under = stringResource(R.string.budget_under)
                ChoiceChips(
                    listOf<Pair<Int?, String>>(null to any) + (0..3).map { it to Budgets.label(d.currency, it, under) },
                    f.budgetBand,
                ) { vm.setFilters(f.copy(budgetBand = it)) }
            }

            Section(R.string.bedrooms_homes_only) {
                ChoiceChips(listOf<Pair<Int?, String>>(null to any) + (1..4).map { it to "$it+" }, f.minBedrooms) {
                    vm.setFilters(f.copy(minBedrooms = it))
                }
            }

            Section(R.string.plot_size) {
                ChoiceChips(
                    listOf<Pair<Int?, String>>(null to any) + plotBands.getValue(d.plotUnit).mapIndexed { i, v ->
                        i to "${BigDecimal(v.toString()).stripTrailingZeros().toPlainString()} ${units.getValue(d.plotUnit)}+"
                    },
                    f.plotBand,
                ) { vm.setFilters(f.copy(plotBand = it)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.foreigners_can_buy), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.foreigners_filter_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = f.foreign, onCheckedChange = { vm.setFilters(f.copy(foreign = it)) })
            }

            Section(R.string.show_plot_sizes_in) {
                ChoiceChips(units.toList(), d.plotUnit, vm::setPlotUnit)
                Text(stringResource(R.string.floor_always_m2), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.show_prices_in) {
                ChoiceChips(listOf("MUR" to "Rs", "EUR" to "€", "USD" to "$"), d.currency, vm::setCurrency)
                state.fx?.let { Text(stringResource(R.string.fx_note, it.day), style = MaterialTheme.typography.bodySmall) }
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(stringResource(R.string.show_n, state.count))
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), fontWeight = FontWeight.ExtraBold)
        content()
    }
}
