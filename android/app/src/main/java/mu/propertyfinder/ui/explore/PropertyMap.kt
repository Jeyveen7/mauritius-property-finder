package mu.propertyfinder.ui.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import mu.propertyfinder.BuildConfig
import mu.propertyfinder.data.MapItem
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val SOURCE = "items"
private const val GROUP_LAYER = "groups"
private const val PIN_LAYER = "pins"
private val ISLAND = LatLng(-20.25, 57.55)

/**
 * The map: groups (clusters and stacks) as teal circles with a count, single
 * properties as price labels. The server does the grouping; this only draws.
 */
@Composable
fun PropertyMap(
    items: List<MapItem>,
    label: (MapItem) -> String,
    onViewport: (bbox: String, zoom: Double) -> Unit,
    onPin: (String) -> Unit,
    onStack: (List<String>) -> Unit,
    focus: Pair<Double, Double>? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnViewport by rememberUpdatedState(onViewport)
    val currentOnPin by rememberUpdatedState(onPin)
    val currentOnStack by rememberUpdatedState(onStack)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = {
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(ISLAND).zoom(9.0).build()
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL)) { s ->
                s.addSource(GeoJsonSource(SOURCE))
                s.addLayer(
                    CircleLayer(GROUP_LAYER, SOURCE)
                        .withFilter(Expression.neq(Expression.get("kind"), Expression.literal("pin")))
                        .withProperties(
                            PropertyFactory.circleRadius(18f),
                            PropertyFactory.circleColor("#0E6B66"),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f),
                        ),
                )
                s.addLayer(
                    SymbolLayer(PIN_LAYER, SOURCE).withProperties(
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textSize(13f),
                        // Must be a font the style's glyph server provides.
                        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                        PropertyFactory.textColor(
                            Expression.match(Expression.get("kind"), Expression.literal("pin"),
                                             Expression.color(android.graphics.Color.parseColor("#12201F")),
                                             Expression.color(android.graphics.Color.WHITE)),
                        ),
                        PropertyFactory.textHaloColor(
                            Expression.match(Expression.get("kind"), Expression.literal("pin"),
                                             Expression.color(android.graphics.Color.WHITE),
                                             Expression.color(android.graphics.Color.TRANSPARENT)),
                        ),
                        PropertyFactory.textHaloWidth(3f),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    ),
                )
                style = s
                m.addOnCameraIdleListener {
                    val b = m.projection.visibleRegion.latLngBounds
                    currentOnViewport(
                        "${b.longitudeWest},${b.latitudeSouth},${b.longitudeEast},${b.latitudeNorth}",
                        m.cameraPosition.zoom,
                    )
                }
                m.addOnMapClickListener { point ->
                    val screen = m.projection.toScreenLocation(point)
                    val hit = m.queryRenderedFeatures(screen, GROUP_LAYER, PIN_LAYER).firstOrNull()
                        ?: return@addOnMapClickListener false
                    when (hit.getStringProperty("kind")) {
                        "pin" -> currentOnPin(hit.getStringProperty("id"))
                        "stack" -> currentOnStack(hit.getStringProperty("ids").split(","))
                        else -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(point, m.cameraPosition.zoom + 2))
                    }
                    true
                }
                // Report the first viewport once the style is ready.
                val b = m.projection.visibleRegion.latLngBounds
                currentOnViewport("${b.longitudeWest},${b.latitudeSouth},${b.longitudeEast},${b.latitudeNorth}",
                                  m.cameraPosition.zoom)
            }
        }
        mapView
    }, modifier = modifier)

    LaunchedEffect(focus, style) {
        val (lat, lon) = focus ?: return@LaunchedEffect
        map?.takeIf { style != null }?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 13.0))
    }

    LaunchedEffect(items, style) {
        val s = style ?: return@LaunchedEffect
        val features = items.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.lon, item.lat)).apply {
                addStringProperty("kind", item.kind)
                addStringProperty("label", if (item.kind == "pin") label(item) else item.count.toString())
                addStringProperty("id", item.id.orEmpty())
                addStringProperty("ids", item.ids?.joinToString(",").orEmpty())
            }
        }
        s.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}
