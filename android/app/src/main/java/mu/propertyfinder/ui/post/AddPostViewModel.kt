package mu.propertyfinder.ui.post

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import mu.propertyfinder.App
import mu.propertyfinder.R
import mu.propertyfinder.data.Detail
import mu.propertyfinder.data.Locality
import mu.propertyfinder.data.Money
import mu.propertyfinder.data.Photo
import mu.propertyfinder.data.SourceEntry
import mu.propertyfinder.domain.PostReader
import mu.propertyfinder.domain.Units
import mu.propertyfinder.domain.norm
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/** The editable fields of a post, as the user confirms them. */
data class Draft(
    val type: String = "land",
    val locality: String = "",
    val sizeValue: String = "",
    val sizeUnit: String = "m2",
    val sizeIsPlot: Boolean = true,
    val price: String = "",
    val currency: String = "MUR",
    val negotiable: Boolean = false,
)

enum class PostStep { TEXT, REVIEW }

data class PostState(
    val step: PostStep = PostStep.TEXT,
    val text: String = "",
    val busy: Boolean = false,
    val draft: Draft = Draft(),
    val suggestions: List<Locality> = emptyList(),
    val localityKnown: Boolean = false,
    val error: Int? = null,
    val published: String? = null,
    val photos: List<Uri> = emptyList(),
)

private val URL = Regex("https?://\\S+")

class AddPostViewModel(private val app: Application) : AndroidViewModel(app) {
    private val a = App.from(app)
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state

    private val localities get() = a.snapshots.current.value?.localities.orEmpty()

    fun setText(t: String) = _state.update { it.copy(text = t) }

    fun appendShared(text: String?) {
        if (!text.isNullOrBlank()) _state.update { it.copy(text = listOf(it.text, text).filter(String::isNotBlank).joinToString("\n")) }
    }

    /** Reads a screenshot on the phone (ML Kit) and adds its text to the post. */
    fun readScreenshot(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching {
            val image = InputImage.fromFilePath(app, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image).await().text
        }.onSuccess(::appendShared)
        _state.update { it.copy(busy = false) }
    }

    fun read() {
        val r = PostReader.read(state.value.text, localities)
        val draft = Draft(
            type = r.type ?: "land",
            locality = r.locality?.name.orEmpty(),
            sizeValue = r.sizeValue?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty(),
            sizeUnit = r.sizeUnit ?: "m2",
            sizeIsPlot = r.type != "apartment",
            price = r.priceWhole?.toString().orEmpty(),
            currency = r.currency ?: "MUR",
            negotiable = r.negotiable,
        )
        _state.update { it.copy(step = PostStep.REVIEW, draft = draft, localityKnown = r.locality != null, error = null) }
    }

    fun edit(transform: (Draft) -> Draft) = _state.update { it.copy(draft = transform(it.draft)) }

    fun setLocality(text: String) {
        val q = norm(text)
        val matches = if (q.length < 2) emptyList() else localities.filter { loc ->
            loc.searchText.split(' ').any { it.startsWith(q) } || loc.searchText.contains(q)
        }.take(6)
        _state.update { s ->
            s.copy(draft = s.draft.copy(locality = text), suggestions = matches,
                   localityKnown = localities.any { norm(it.name) == q })
        }
    }

    fun pickLocality(l: Locality) =
        _state.update { it.copy(draft = it.draft.copy(locality = l.name), suggestions = emptyList(), localityKnown = true) }

    fun setPhotos(uris: List<Uri>) = _state.update { it.copy(photos = uris.take(8)) }

    /** Saves the listing on this phone only: it shows on your map, not on anyone else's. */
    fun publish() = viewModelScope.launch {
        val s = state.value
        val d = s.draft
        val place = localities.firstOrNull { norm(it.name) == norm(d.locality) }
        val size = d.sizeValue.replace(',', '.').toDoubleOrNull()
        val price = d.price.filter(Char::isDigit).toLongOrNull()
        if (place == null) return@launch _state.update { it.copy(error = R.string.unknown_town) }
        if (size == null || price == null) return@launch
        _state.update { it.copy(busy = true) }
        val id = "mine-" + UUID.randomUUID()
        val photos = withContext(Dispatchers.IO) { copyPhotos(id, s.photos) }
        val m2 = Units.toM2(size, d.sizeUnit)
        val now = OffsetDateTime.now().toString()
        val money = Money(price * 100, d.currency)
        // Keep a link back to the original post or advert, named after its site.
        val link = URL.find(s.text)?.value
        val sourceName = link?.let { Uri.parse(it).host?.removePrefix("www.") } ?: app.getString(R.string.shared_by_you)
        val listing = Detail(
            id = id, type = d.type,
            title = app.getString(when (d.type) { "apartment" -> R.string.mine_apartment; "house" -> R.string.mine_house; else -> R.string.mine_plot }),
            locality = place.name, district = place.district, region = place.region, lat = place.lat, lon = place.lon,
            price = money, priceMurMinor = mu.propertyfinder.domain.Converter(a.snapshots.current.value?.meta?.fx).convert(money, "MUR").minor,
            plotM2 = if (d.sizeIsPlot) m2 else null, floorM2 = if (d.sizeIsPlot) null else m2,
            negotiable = d.negotiable, verified = false, firstSeenAt = now, lastCheckedAt = now,
            searchText = place.searchText, thumb = photos.firstOrNull()?.url, photos = photos, mine = true,
            sources = listOf(SourceEntry(id, sourceName, "user_post",
                                         url = link, price = money, firstSeenAt = now,
                                         lastCheckedAt = now, verified = false)),
        )
        a.local.addMine(listing)
        _state.update { PostState(published = id) }
    }

    private fun copyPhotos(id: String, uris: List<Uri>): List<Photo> {
        val dir = File(app.filesDir, "my_photos").apply { mkdirs() }
        return uris.mapIndexedNotNull { i, uri ->
            val out = File(dir, "$id-$i.jpg")
            runCatching { app.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } }
                .getOrNull() ?: return@mapIndexedNotNull null
            Photo(Uri.fromFile(out).toString(), "user_upload")
        }
    }

    fun reset() = _state.update { PostState() }
}
