package mu.propertyfinder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** Everything personal, kept only on this phone: nothing is sent anywhere. */
@Serializable
data class Personal(
    val savedIds: Set<String> = emptySet(),
    val searches: List<SavedSearch> = emptyList(),
    val hiddenIds: Set<String> = emptySet(),
    /** Listings the user added from posts or screenshots. */
    val mine: List<Detail> = emptyList(),
    /** Price of each saved property when last seen, to notice drops and removals. */
    val lastSeenPrices: Map<String, Long> = emptyMap(),
)

class LocalStore(context: Context) {
    private val file = File(context.filesDir, "personal.json")
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Personal> = _state

    private fun read(): Personal =
        runCatching { json.decodeFromString<Personal>(file.readText()) }.getOrDefault(Personal())

    private suspend fun write(change: (Personal) -> Personal) = withContext(Dispatchers.IO) {
        _state.update(change)
        // Write to a temporary file first so a crash never leaves a half-written file.
        val tmp = File(file.parentFile, "personal.json.tmp")
        tmp.writeText(json.encodeToString(Personal.serializer(), _state.value))
        tmp.renameTo(file)
    }

    suspend fun toggleSaved(id: String, price: Long) = write {
        if (id in it.savedIds) it.copy(savedIds = it.savedIds - id, lastSeenPrices = it.lastSeenPrices - id)
        else it.copy(savedIds = it.savedIds + id, lastSeenPrices = it.lastSeenPrices + (id to price))
    }

    suspend fun addSearch(filters: SearchFilters) = write {
        if (it.searches.any { s -> s.filters == filters }) it
        else it.copy(searches = it.searches + SavedSearch(UUID.randomUUID().toString(), filters))
    }

    suspend fun deleteSearch(id: String) = write { it.copy(searches = it.searches.filterNot { s -> s.id == id }) }

    suspend fun hide(id: String) = write { it.copy(hiddenIds = it.hiddenIds + id) }

    suspend fun addMine(listing: Detail) = write { it.copy(mine = it.mine.filterNot { m -> m.id == listing.id } + listing) }

    suspend fun deleteMine(id: String) = write { it.copy(mine = it.mine.filterNot { m -> m.id == id }) }

    suspend fun setLastSeenPrices(prices: Map<String, Long>) = write { it.copy(lastSeenPrices = prices) }
}
