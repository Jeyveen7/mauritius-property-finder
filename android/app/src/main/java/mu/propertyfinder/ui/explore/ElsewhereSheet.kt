package mu.propertyfinder.ui.explore

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mu.propertyfinder.R
import mu.propertyfinder.domain.Elsewhere

/** "Search everywhere": opens the current search on each site, in the browser or the site's app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElsewhereSheet(links: List<Elsewhere.Link>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 24.dp),
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.everywhere), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.everywhere_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            links.forEach { link ->
                Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                        }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${link.site} ↗", fontWeight = FontWeight.Bold)
                        Text(
                            when (link.applies) {
                                Elsewhere.Applies.FILTERS -> stringResource(R.string.ew_filters)
                                Elsewhere.Applies.REGION -> stringResource(R.string.ew_region)
                                Elsewhere.Applies.TYPE -> stringResource(R.string.ew_type)
                                Elsewhere.Applies.TEXT -> stringResource(R.string.ew_text, link.text)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        )
                    }
                }
            }
            Text(stringResource(R.string.everywhere_add), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
