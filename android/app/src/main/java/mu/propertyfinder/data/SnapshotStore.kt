package mu.propertyfinder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mu.propertyfinder.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** The data the app works from: one snapshot of every property, loaded into memory. */
class Snapshot(val meta: SnapMeta, val properties: List<Detail>, val localities: List<Locality>) {
    val byId: Map<String, Detail> = properties.associateBy { it.id }
    val localityByName: Map<String, Locality> = localities.associateBy { it.name }
}

/**
 * Keeps the latest snapshot on the phone. On start it loads the copy on disk (or the one
 * bundled in the app on first launch), then [refresh] checks the small meta.json online
 * and downloads the data only when its version changed. Works offline with what it has.
 */
class SnapshotStore(private val context: Context) {
    private val dir = File(context.filesDir, "snapshot").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val _current = MutableStateFlow<Snapshot?>(null)
    val current: StateFlow<Snapshot?> = _current

    /** Where the published snapshot lives (GitHub Pages); empty means bundled data only. */
    val remoteBase: String = BuildConfig.DATA_BASE_URL.trimEnd('/')

    suspend fun load(): Snapshot? = lock.withLock {
        _current.value ?: withContext(Dispatchers.IO) {
            runCatching { read({ File(dir, it).inputStream() }) }.getOrNull()
                ?: runCatching { read({ context.assets.open("snapshot/$it") }) }.getOrNull()
        }?.also { _current.value = it }
    }

    /** Returns the previous snapshot if a newer one was installed, null if nothing changed. */
    suspend fun refresh(): Snapshot? = lock.withLock {
        if (remoteBase.isBlank()) return@withLock null
        withContext(Dispatchers.IO) {
            val metaText = get("$remoteBase/meta.json")?.use { it.bufferedReader().readText() } ?: return@withContext null
            val meta = json.decodeFromString<SnapMeta>(metaText)
            val old = _current.value
            if (meta.format != 1 || meta.version == old?.meta?.version) return@withContext null
            // Download to temporary files, check they parse, then swap them in.
            val tmp = File(dir, "incoming").apply { deleteRecursively(); mkdirs() }
            for (name in listOf("properties.json.gz", "localities.json.gz")) {
                get("$remoteBase/$name")?.use { input -> File(tmp, name).outputStream().use { input.copyTo(it) } }
                    ?: return@withContext null
            }
            File(tmp, "meta.json").writeText(metaText)
            val fresh = read({ File(tmp, it).inputStream() })
            tmp.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            tmp.deleteRecursively()
            _current.value = fresh
            old
        }
    }

    private fun get(url: String): InputStream? {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) { resp.close(); return null }
        return resp.body?.byteStream()
    }

    private fun read(open: (String) -> InputStream): Snapshot {
        fun gz(name: String) = GZIPInputStream(open(name)).bufferedReader().use { it.readText() }
        val meta = json.decodeFromString<SnapMeta>(open("meta.json").bufferedReader().use { it.readText() })
        return Snapshot(meta, json.decodeFromString(gz("properties.json.gz")), json.decodeFromString(gz("localities.json.gz")))
    }
}
