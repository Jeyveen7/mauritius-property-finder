package mu.propertyfinder.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import mu.propertyfinder.R

/** First visit only: what the app does, in three lines, then straight to the search box. */
@Composable
fun WelcomeDialog(projects: Boolean, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.welcome_title), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Point(Icons.Filled.Public, stringResource(R.string.welcome_search))
                Point(Icons.Filled.BookmarkAdd, stringResource(R.string.welcome_save))
                if (projects) Point(Icons.Filled.Verified, stringResource(R.string.welcome_projects))
            }
        },
        confirmButton = { Button(onClick = onStart) { Text(stringResource(R.string.welcome_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.welcome_later)) } },
    )
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}
