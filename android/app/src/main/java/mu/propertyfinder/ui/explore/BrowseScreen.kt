package mu.propertyfinder.ui.explore

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mu.propertyfinder.R
import mu.propertyfinder.domain.Elsewhere

/**
 * The listing sites, browsed inside the app: their own pages, unchanged, with their branding
 * and contact buttons. Nothing is fetched in the background or copied from them. "Save to my
 * map" reads only the page the user is looking at, when they tap it, and the user checks the
 * facts before the listing is kept on their phone (Add a post).
 */
@Composable
fun BrowseScreen(start: Int, onClose: () -> Unit, onSave: (String) -> Unit) {
    val vm = exploreViewModel()
    val links = remember { vm.elsewhere() }
    SiteBrowser(links, start, onSave, onBackOut = onClose, modifier = Modifier.fillMaxSize()) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.back)) }
    }
}

/**
 * Tabs for the in-app sites, their page, and the Save bar. Used full screen and in the
 * results panel under the search. When [links] change (a new town or filter), the current
 * site reloads with the new search.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteBrowser(
    links: List<Elsewhere.Link>,
    start: Int,
    onSave: (String) -> Unit,
    onBackOut: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val sites = links.withIndex().filter { it.value.inApp }
    var current by remember { mutableIntStateOf(start) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    val nothingFound = stringResource(R.string.browse_nothing)

    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true  // the sites need it for their own pages
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "http" || url.scheme == "https") return false
                    // Phone, WhatsApp and e-mail buttons on a listing go to the right app.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
                }
            }
        }
    }
    val currentUrl = links.getOrNull(current)?.url
    LaunchedEffect(currentUrl) { currentUrl?.let(web::loadUrl) }

    BackHandler(enabled = onBackOut != null) { if (web.canGoBack()) web.goBack() else onBackOut?.invoke() }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading()
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sites.forEach { (i, site) ->
                    FilterChip(selected = i == current, label = { Text(site.site) }, onClick = {
                        if (i == current) web.loadUrl(site.url) else current = i
                    })
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else androidx.compose.foundation.layout.Spacer(Modifier.padding(2.dp))
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRelease = { it.destroy() })
        Surface(shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { web.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
                IconButton(onClick = {
                    web.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }) { Icon(Icons.Filled.OpenInBrowser, stringResource(R.string.open_in_browser)) }
                Button(onClick = {
                    web.evaluateJavascript(READ_PAGE) { result ->
                        val text = runCatching { Json.parseToJsonElement(result) as JsonPrimitive }.getOrNull()?.content.orEmpty()
                        if (text.isBlank()) Toast.makeText(context, nothingFound, Toast.LENGTH_LONG).show()
                        else onSave("${web.url}\n$text")
                    }
                }, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Icon(Icons.Filled.BookmarkAdd, null)
                    Text(stringResource(R.string.save_to_map), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * The listing's own headline and summary first (so the reader finds this listing's price and
 * size, not a neighbour's), then the start of the main content.
 */
private const val READ_PAGE = """(() => {
  const meta = (n) => document.querySelector('meta[property="' + n + '"],meta[name="' + n + '"]')?.content || '';
  const main = document.querySelector('main, article, [role=main]') || document.body;
  return [document.querySelector('h1')?.innerText || document.title, meta('og:title'), meta('og:description') || meta('description'),
          main.innerText.slice(0, 3000)].filter(Boolean).join('\n');
})()"""
