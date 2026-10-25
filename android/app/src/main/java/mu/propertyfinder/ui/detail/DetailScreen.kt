package mu.propertyfinder.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mu.propertyfinder.R
import mu.propertyfinder.data.Detail
import mu.propertyfinder.data.Eligibility
import mu.propertyfinder.data.Money
import mu.propertyfinder.domain.Converter
import mu.propertyfinder.domain.Format
import mu.propertyfinder.ui.Badge
import mu.propertyfinder.ui.ChoiceChips
import mu.propertyfinder.ui.DropBg
import mu.propertyfinder.ui.DropFg
import mu.propertyfinder.ui.EligibleBg
import mu.propertyfinder.ui.EligibleFg
import mu.propertyfinder.ui.Ink
import mu.propertyfinder.ui.Teal
import mu.propertyfinder.ui.WarnBg
import mu.propertyfinder.ui.WarnFg
import mu.propertyfinder.ui.sizeText
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(id) { vm.load(id) }
    val s by vm.state.collectAsStateWithLifecycle()
    val converter = remember(s.fx) { Converter(s.fx) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun shown(m: Money) = Format.money(converter.convert(m, s.display.currency))

    val d = s.detail
    if (d == null && !s.missing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (d == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.listing_gone))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(if (d.photos.isEmpty()) 120.dp else 260.dp).background(Color(0xFFE3E8D5))) {
                if (d.photos.isNotEmpty()) {
                    val pager = rememberPagerState { d.photos.size }
                    HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                        AsyncImage(model = d.photos[i].url, contentDescription = null, contentScale = ContentScale.Crop,
                                   modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        "${pager.currentPage + 1}/${d.photos.size} · " +
                            (d.photos[pager.currentPage].credit ?: stringResource(R.string.photo_by_seller)),
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    RoundIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Spacer(Modifier.weight(1f))
                    RoundIcon(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "${d.title} · ${d.locality} · ${shown(d.price)}")
                        context.startActivity(Intent.createChooser(share, null))
                    }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(onClick = { vm.toggleSave() }) {
                        if (s.saved) Icon(Icons.Filled.Favorite, stringResource(R.string.unsave), tint = Color(0xFFC2410C))
                        else Icon(Icons.Filled.FavoriteBorder, stringResource(R.string.save))
                    }
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shown(d.price), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    if (d.mine) Badge(stringResource(R.string.only_on_this_phone), WarnBg, WarnFg)
                    if (d.price.currency != s.display.currency) {
                        Text(Format.money(d.price), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(d.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(listOfNotNull(d.locality, d.district, d.region).joinToString(" · "),
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.locationPrecision == "locality") {
                        Text(stringResource(R.string.near_village_centre), fontSize = 12.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (!d.verified) Panel(WarnBg) { Text(stringResource(R.string.detail_unverified), color = WarnFg) }

                d.eligibility?.let { EligibilityPanel(it) }

                Panel {
                    Fact(stringResource(R.string.facts_size), sizeText(d.type, d.plotM2, d.floorM2, d.bedrooms, s.display))
                    Fact(stringResource(R.string.facts_coast),
                         stringResource(R.string.km, "%.1f".format(d.coastDistanceM / 1000)) + " · " +
                             stringResource(R.string.straight_line))
                    d.lastCheckedAt?.let { Fact(stringResource(R.string.facts_checked), stringResource(R.string.checked_ago, ago(it))) }
                }

                if (s.areaPhotos.isNotEmpty()) Panel {
                    Title(stringResource(R.string.area_photos_title))
                    Text(stringResource(R.string.area_photos_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(s.areaPhotos) { photo ->
                            Column(Modifier.width(220.dp).clickable { openUrl(context, photo.pageUrl) }) {
                                AsyncImage(model = photo.url, contentDescription = null, contentScale = ContentScale.Crop,
                                           modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)))
                                // The licence requires crediting the author and the licence.
                                Text(stringResource(R.string.photo_credit, photo.author, photo.license), fontSize = 11.sp,
                                     maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                d.priceCheck?.let { pc ->
                    Panel {
                        Title(stringResource(R.string.price_check))
                        val scope = d.region ?: ""
                        Text(when {
                            !pc.available -> stringResource(R.string.price_check_unavailable, pc.comparables, pc.minimum ?: 5)
                            (pc.diffPct ?: 0) <= -3 -> stringResource(R.string.price_check_below, -(pc.diffPct ?: 0), scope, pc.comparables)
                            (pc.diffPct ?: 0) >= 3 -> stringResource(R.string.price_check_above, pc.diffPct ?: 0, scope, pc.comparables)
                            else -> stringResource(R.string.price_check_in_line, scope, pc.comparables)
                        })
                    }
                }

                if (d.history.isNotEmpty()) Panel {
                    Title(stringResource(R.string.price_history))
                    d.history.forEach { h ->
                        val day = OffsetDateTime.parse(h.at).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (h.kind == "first_seen") stringResource(R.string.history_first_seen, day)
                                     else stringResource(R.string.history_price_change, day), fontWeight = FontWeight.Bold)
                                Text(h.source, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(shown(h.price), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                s.costs?.let { c ->
                    Panel {
                        Title(stringResource(R.string.cost_title))
                        Text(stringResource(R.string.per_month, shown(c.monthly)), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text(stringResource(R.string.loan_line, shown(c.loan), c.termYears), fontSize = 13.sp)
                        Text(stringResource(R.string.deposit), fontWeight = FontWeight.Bold)
                        ChoiceChips(listOf(10, 20, 30, 40).map { it to "$it%" }, s.depositPct) { vm.setCosts(depositPct = it) }
                        Text(stringResource(R.string.loan_term), fontWeight = FontWeight.Bold)
                        ChoiceChips(listOf(15, 20, 25).map { it to stringResource(R.string.years, it) }, s.termYears) {
                            vm.setCosts(termYears = it)
                        }
                        Text(stringResource(R.string.upfront, shown(c.upfront)), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.upfront_note, c.registrationDutyPct), fontSize = 12.sp)
                        if (d.eligibility?.status == "yes") Text(stringResource(R.string.non_citizen_bank_note), fontSize = 13.sp)
                        Text(stringResource(R.string.cost_disclaimer, c.assumedRatePct), fontSize = 12.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (d.coastal) Panel { Text(stringResource(R.string.coastal_note)) }

                Panel {
                    Title(if (d.sources.size == 1) stringResource(R.string.found_on_one_site)
                          else stringResource(R.string.found_on_sites, d.sources.size))
                    d.sources.forEachIndexed { i, src ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(src.agency ?: src.source, fontWeight = FontWeight.Bold)
                                Text(listOf(src.source, shown(src.price)).joinToString(" · "), fontSize = 13.sp)
                            }
                            if (src.url != null) OutlinedButton(onClick = {
                                openUrl(context, src.url)
                            }) { Text(stringResource(R.string.open)) }
                        }
                    }
                    Text(stringResource(R.string.sources_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (d.mine) OutlinedButton(onClick = { vm.deleteMine(); onBack() }) { Text(stringResource(R.string.delete)) }
                else if (s.hidden) Text(stringResource(R.string.hidden_on_phone), color = EligibleFg, fontWeight = FontWeight.Bold)
                else OutlinedButton(onClick = { vm.hide() }) { Text(stringResource(R.string.report_wrong)) }
                Spacer(Modifier.height(8.dp))
            }
        }

        Surface(shadowElevation = 8.dp) {
            val first = d.sources.firstOrNull()
            Button(
                onClick = { first?.url?.let { openUrl(context, it) } },
                enabled = first?.url != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            ) { Text(stringResource(if (d.verified) R.string.contact else R.string.open_post)) }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) =
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private fun ago(iso: String): String {
    val mins = Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).toMinutes()
    return when {
        mins < 60 -> "${mins}m"
        mins < 48 * 60 -> "${mins / 60}h"
        else -> "${mins / (24 * 60)}d"
    }
}

@Composable
private fun EligibilityPanel(e: Eligibility) {
    val (answer, bg, fg) = when (e.status) {
        "yes" -> Triple(stringResource(R.string.elig_yes), EligibleBg, EligibleFg)
        "unknown" -> Triple(stringResource(R.string.elig_unknown), WarnBg, WarnFg)
        else -> Triple(stringResource(R.string.elig_no), Color(0xFFECEFEE), Color(0xFF3E4A48))
    }
    val scheme = e.route?.removePrefix("scheme:")?.let { if (it == "SMART_CITY") "Smart City" else it }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(stringResource(R.string.can_foreigners_buy), Modifier.weight(1f))
            Badge(answer, bg, fg)
        }
        Text(when (e.reason) {
            "open_scheme" -> stringResource(R.string.elig_open_scheme, scheme ?: "")
            "g2_apartment" -> stringResource(R.string.elig_g2_apartment)
            "g2_floors_unknown" -> stringResource(R.string.elig_g2_floors_unknown)
            "g2_below_min_price" -> stringResource(R.string.elig_g2_below_min_price)
            "g2_too_few_floors" -> stringResource(R.string.elig_g2_too_few_floors)
            else -> stringResource(R.string.elig_citizens)
        })
        if (e.residency) Text(stringResource(R.string.elig_residency))
        Text(stringResource(R.string.elig_footer, e.rulesVersion), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(bg: Color = Color.White, content: @Composable () -> Unit) {
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = modifier)

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.95f)) { IconButton(onClick = onClick) { icon() } }
}
