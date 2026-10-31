package mu.propertyfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mu.propertyfinder.R
import mu.propertyfinder.data.Card as PropertyCard
import mu.propertyfinder.data.Project
import mu.propertyfinder.data.DisplayPrefs
import mu.propertyfinder.domain.Converter
import mu.propertyfinder.domain.Format
import java.time.Duration
import java.time.OffsetDateTime

@Composable
fun unitLabels(): Map<String, String> = mapOf(
    "m2" to stringResource(R.string.unit_m2),
    "perch" to stringResource(R.string.unit_perch),
    "toise" to stringResource(R.string.unit_toise),
    "arpent" to stringResource(R.string.unit_arpent),
)

@Composable
fun Badge(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Size line: plot sizes in the user's unit, floor areas in m². */
@Composable
fun sizeText(type: String, plotM2: Double?, floorM2: Double?, bedrooms: Int?, display: DisplayPrefs): String {
    val labels = unitLabels()
    val parts = mutableListOf<String>()
    if (bedrooms != null && type != "land") parts += stringResource(R.string.bedrooms_short, bedrooms)
    if (floorM2 != null && type != "land") parts += Format.area(floorM2, "m2", labels)
    if (plotM2 != null) parts += Format.area(plotM2, display.plotUnit, labels)
    return parts.joinToString(" · ")
}

fun isNew(firstSeenAt: String): Boolean =
    Duration.between(OffsetDateTime.parse(firstSeenAt), OffsetDateTime.now()).toDays() <= 3

/** An official approved project: scheme and status, developer, what is built, where. No price. */
@Composable
fun ProjectCardRow(p: Project, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(p.scheme, EligibleBg, EligibleFg)
                Badge(p.status, Color(0xFFECEFEE), Color(0xFF3E4A48))
            }
            Text(p.developer, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(p.project, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(p.place, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
fun PropertyCardRow(card: PropertyCard, display: DisplayPrefs, converter: Converter, onClick: () -> Unit) {
    val price = converter.convert(card.price, display.currency)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        card.thumb?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop,
                       modifier = Modifier.fillMaxWidth().height(150.dp))
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Format.money(price), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                if (card.priceDropped) Badge(stringResource(R.string.badge_price_drop), DropBg, DropFg)
            }
            Text(card.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(card.locality, sizeText(card.type, card.plotM2, card.floorM2, card.bedrooms, display))
                     .joinToString(" · "),
                 color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (card.foreignEligible) Badge(stringResource(R.string.foreigners_can_buy), EligibleBg, EligibleFg)
                if (card.mine) Badge(stringResource(R.string.saved_by_you), WarnBg, WarnFg)
                else if (card.unverified) Badge(stringResource(R.string.badge_unverified), WarnBg, WarnFg)
                if (isNew(card.firstSeenAt)) Badge(stringResource(R.string.badge_new), Ink, Color.White)
                Badge(if (card.sources == 1) stringResource(R.string.on_one_site)
                      else stringResource(R.string.on_sites, card.sources), Color(0xFFECEFEE), Color(0xFF3E4A48))
            }
        }
    }
}
