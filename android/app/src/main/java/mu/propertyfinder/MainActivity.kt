package mu.propertyfinder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mu.propertyfinder.alerts.EXTRA_PROPERTY_ID
import mu.propertyfinder.ui.AppNav
import mu.propertyfinder.ui.PropertyFinderTheme

/**
 * Something shared into the app from another app's Share menu, or saved from the in-app
 * browser ([read]: go straight to checking the facts found in it).
 */
data class SharedContent(val text: String?, val image: Uri?, val read: Boolean = false)

class MainActivity : ComponentActivity() {
    private val shared = MutableStateFlow<SharedContent?>(null)
    val sharedContent: StateFlow<SharedContent?> = shared
    /** Set when the user taps an alert notification. */
    private val openProperty = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        // Start the browser engine while the user looks at the map, so the listing sites open faster later.
        android.os.Looper.myQueue().addIdleHandler {
            runCatching { android.webkit.WebView(this).destroy() }
            false
        }
        setContent {
            PropertyFinderTheme {
                AppNav(sharedContent = sharedContent, onSharedConsumed = { shared.value = null },
                       onShare = { shared.value = it },
                       openProperty = openProperty, onOpenConsumed = { openProperty.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_PROPERTY_ID)?.let { openProperty.value = it }
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val image = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (text != null || image != null) shared.value = SharedContent(text, image)
    }
}
