package mu.propertyfinder

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.propertyfinder.alerts.RefreshWorker
import mu.propertyfinder.data.LocalStore
import mu.propertyfinder.data.Prefs
import mu.propertyfinder.data.SnapshotStore
import org.maplibre.android.MapLibre

/** Holds the app's single instances; screens get them through [App.from]. No server is involved. */
class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var snapshots: SnapshotStore
        private set
    lateinit var local: LocalStore
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        snapshots = SnapshotStore(this)
        local = LocalStore(this)
        MapLibre.getInstance(this)
        scope.launch {
            snapshots.load()
            // Fetch newer data in the background; the app works meanwhile with what it has.
            runCatching { RefreshWorker.refreshAndAlert(this@App) }
        }
        RefreshWorker.schedule(this)
    }

    companion object {
        fun from(context: android.content.Context): App = context.applicationContext as App
    }
}
