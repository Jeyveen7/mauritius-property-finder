package mu.propertyfinder.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import mu.propertyfinder.App
import mu.propertyfinder.MainActivity
import mu.propertyfinder.R
import mu.propertyfinder.data.Personal
import mu.propertyfinder.data.Snapshot
import mu.propertyfinder.domain.Converter
import mu.propertyfinder.domain.Search
import java.util.concurrent.TimeUnit

const val EXTRA_PROPERTY_ID = "property_id"
private const val CHANNEL = "alerts"

/** What changed for this user between two snapshots. */
data class Changes(val newMatches: List<String>, val priceDrops: List<String>, val gone: List<String>)

/** Compares the previous and new data against the user's saved searches and properties. */
fun changes(old: Snapshot, new: Snapshot, personal: Personal): Changes {
    val converter = Converter(new.meta.fx)
    val added = new.properties.filter { it.id !in old.byId }
    val newMatches = added.filter { p -> personal.searches.any { Search.matches(p, it.filters, converter) } }.map { it.id }
    val priceDrops = personal.savedIds.filter { id ->
        val now = new.byId[id] ?: return@filter false
        now.priceMurMinor < (personal.lastSeenPrices[id] ?: now.priceMurMinor)
    }
    val gone = personal.savedIds.filter { it in old.byId && it !in new.byId }
    return Changes(newMatches, priceDrops, gone)
}

/**
 * Refreshes the data a couple of times a day (only with a network) and turns changes into
 * notifications on the phone. Replaces server-side alerts and push: nothing leaves the phone.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching { refreshAndAlert(applicationContext) }.fold({ Result.success() }, { Result.retry() })

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        suspend fun refreshAndAlert(context: Context) {
            val app = App.from(context)
            app.snapshots.load()
            val old = app.snapshots.refresh() ?: return
            val new = app.snapshots.current.value ?: return
            val personal = app.local.state.value
            val c = changes(old, new, personal)
            app.local.setLastSeenPrices(personal.savedIds.mapNotNull { id -> new.byId[id]?.let { id to it.priceMurMinor } }.toMap())
            if (c.newMatches.isNotEmpty()) notify(context, 1, context.resources.getQuantityString(R.plurals.alert_new_matches, c.newMatches.size, c.newMatches.size), c.newMatches.singleOrNull())
            c.priceDrops.forEach { notify(context, it.hashCode(), context.getString(R.string.push_price_drop), it) }
            c.gone.forEach { notify(context, it.hashCode() + 1, context.getString(R.string.push_gone), null) }
        }

        private fun notify(context: Context, id: Int, text: String, propertyId: String?) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                android.os.Build.VERSION.SDK_INT >= 33) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            propertyId?.let { intent.putExtra(EXTRA_PROPERTY_ID, it) }
            val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(id, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }
}
