package mu.propertyfinder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "prefs")

/** Display preferences and the device's API token. */
data class DisplayPrefs(val currency: String = "MUR", val plotUnit: String = "m2")

class Prefs(private val context: Context) {
    private val token = stringPreferencesKey("token")
    private val phoneVerified = booleanPreferencesKey("phone_verified")
    private val currency = stringPreferencesKey("currency")
    private val plotUnit = stringPreferencesKey("plot_unit")
    private val lastSearch = stringPreferencesKey("last_search")
    private val welcomeSeen = booleanPreferencesKey("welcome_seen")

    val display: Flow<DisplayPrefs> = context.store.data.map {
        DisplayPrefs(currency = it[currency] ?: "MUR", plotUnit = it[plotUnit] ?: "m2")
    }

    suspend fun token(): String? = context.store.data.first()[token]
    suspend fun setToken(value: String) = context.store.edit { it[token] = value }

    suspend fun phoneVerified(): Boolean = context.store.data.first()[phoneVerified] ?: false
    suspend fun setPhoneVerified() = context.store.edit { it[phoneVerified] = true }

    suspend fun setCurrency(value: String) = context.store.edit { it[currency] = value }
    suspend fun setPlotUnit(value: String) = context.store.edit { it[plotUnit] = value }

    /** The Explore search as the user left it (JSON), restored on the next visit. */
    suspend fun lastSearch(): String? = context.store.data.first()[lastSearch]
    suspend fun setLastSearch(value: String) = context.store.edit { it[lastSearch] = value }

    suspend fun welcomeSeen(): Boolean = context.store.data.first()[welcomeSeen] ?: false
    suspend fun setWelcomeSeen() = context.store.edit { it[welcomeSeen] = true }
}
