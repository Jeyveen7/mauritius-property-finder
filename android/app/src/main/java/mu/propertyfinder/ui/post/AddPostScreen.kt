package mu.propertyfinder.ui.post

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mu.propertyfinder.R
import mu.propertyfinder.SharedContent
import mu.propertyfinder.ui.ChoiceChips
import mu.propertyfinder.ui.DropFg
import mu.propertyfinder.ui.unitLabels

@Composable
fun AddPostScreen(shared: SharedContent?, onSharedConsumed: () -> Unit, onPublished: (String) -> Unit) {
    val vm: AddPostViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(shared) {
        shared ?: return@LaunchedEffect
        vm.appendShared(shared.text)
        shared.image?.let(vm::readScreenshot)
        onSharedConsumed()
    }
    val added = stringResource(R.string.post_added_local)
    LaunchedEffect(s.published) {
        s.published?.let {
            Toast.makeText(context, added, Toast.LENGTH_LONG).show()
            vm.reset()
            onPublished(it)
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::readScreenshot)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.post_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        s.error?.let { Text(stringResource(it), color = DropFg) }

        when (s.step) {
            PostStep.TEXT -> {
                Text(stringResource(R.string.post_intro))
                OutlinedTextField(
                    value = s.text, onValueChange = vm::setText, label = { Text(stringResource(R.string.post_text)) },
                    minLines = 5, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.read_screenshot)) }
                Button(onClick = { vm.read() }, enabled = s.text.length >= 10 && !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(if (s.busy) R.string.reading else R.string.read_post))
                }
                Text(stringResource(R.string.no_contact_copied), style = MaterialTheme.typography.bodySmall)
            }
            PostStep.REVIEW -> Review(s, vm)
        }
    }
}

@Composable
private fun Review(s: PostState, vm: AddPostViewModel) {
    val d = s.draft
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.what_we_found), fontWeight = FontWeight.Bold)
            ChoiceChips(
                listOf("land" to stringResource(R.string.type_land), "apartment" to stringResource(R.string.type_apartment),
                       "house" to stringResource(R.string.type_house)),
                d.type,
            ) { t -> vm.edit { it.copy(type = t, sizeIsPlot = t == "land" || it.sizeIsPlot) } }

            OutlinedTextField(d.locality, vm::setLocality, label = { Text(stringResource(R.string.town)) },
                              isError = d.locality.isNotBlank() && !s.localityKnown, modifier = Modifier.fillMaxWidth())
            s.suggestions.forEach { l ->
                Text("${l.name} · ${l.district}", modifier = Modifier.fillMaxWidth().clickable { vm.pickLocality(l) }.padding(8.dp))
            }
            if (d.locality.isNotBlank() && !s.localityKnown && s.suggestions.isEmpty()) {
                Text(stringResource(R.string.unknown_town), color = DropFg, style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(d.sizeValue, { v -> vm.edit { it.copy(sizeValue = v) } }, label = { Text(stringResource(R.string.size)) },
                              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            ChoiceChips(unitLabels().toList(), d.sizeUnit) { u -> vm.edit { it.copy(sizeUnit = u) } }
            if (d.type != "land") Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.plot_or_floor), modifier = Modifier.weight(1f))
                Switch(checked = d.sizeIsPlot, onCheckedChange = { v -> vm.edit { it.copy(sizeIsPlot = v) } })
            }

            OutlinedTextField(d.price, { v -> vm.edit { it.copy(price = v.filter(Char::isDigit)) } },
                              label = { Text(stringResource(R.string.price)) },
                              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            ChoiceChips(listOf("MUR" to "Rs", "EUR" to "€", "USD" to "$"), d.currency) { c -> vm.edit { it.copy(currency = c) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.negotiable), modifier = Modifier.weight(1f))
                Switch(checked = d.negotiable, onCheckedChange = { v -> vm.edit { it.copy(negotiable = v) } })
            }
            Text(stringResource(R.string.no_contact_copied), style = MaterialTheme.typography.bodySmall)
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { vm.setPhotos(it) }
    OutlinedButton(onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Text(if (s.photos.isEmpty()) stringResource(R.string.add_photos) else stringResource(R.string.photos_selected, s.photos.size))
    }
    val ready = s.localityKnown && d.sizeValue.isNotBlank() && d.price.isNotBlank() && !s.busy
    Button(onClick = vm::publish, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.add_to_map))
    }
}
