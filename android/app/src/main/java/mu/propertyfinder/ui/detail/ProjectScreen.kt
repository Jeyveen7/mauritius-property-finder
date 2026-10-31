package mu.propertyfinder.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.propertyfinder.App
import mu.propertyfinder.R
import mu.propertyfinder.ui.Badge
import mu.propertyfinder.ui.EligibleBg
import mu.propertyfinder.ui.EligibleFg

/** An official approved project from the EDB lists: facts, the developer's site, and the source. */
@Composable
fun ProjectScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val snap by App.from(context).snapshots.current.collectAsStateWithLifecycle()
    val p = snap?.projectById?.get(id)
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        if (p == null) {
            Text(stringResource(R.string.listing_gone))
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(p.scheme, EligibleBg, EligibleFg)
                Badge(p.status, Color(0xFFECEFEE), Color(0xFF3E4A48))
            }
            Text(p.developer, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(listOfNotNull(p.place, p.region).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.near_village_centre), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Panel {
            Text(stringResource(R.string.project_what), fontWeight = FontWeight.Bold)
            Text(p.project)
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.can_foreigners_buy), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Badge(stringResource(R.string.eligible_yes), EligibleBg, EligibleFg)
            }
            Text(stringResource(R.string.project_scheme, p.schemeName))
            Text(stringResource(R.string.project_no_price), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        p.website?.let { url ->
            Button(onClick = { open(url) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.developer_site)) }
        }
        Panel {
            Text(stringResource(R.string.project_source), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.project_source_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            OutlinedButton(onClick = { open(p.sourceUrl) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_edb)) }
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
