package ro.safetyplease.app.platform.map

import android.annotation.SuppressLint
import android.view.MotionEvent
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import ro.safetyplease.core.venue.GeoPoint

/**
 * The MapLibre view of [pack], kept in step with the screen's lifecycle. [onReady] gets the engine once the map
 * exists; the style follows [dark]. A finger on the map moves the map, not the list around it.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PingMap(
    pack: MapPack,
    dark: Boolean,
    palette: MapPalette,
    modifier: Modifier = Modifier,
    onTap: (GeoPoint, String?) -> Unit,
    onReady: (PingMapEngine) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentPalette by rememberUpdatedState(palette)
    val currentTap by rememberUpdatedState(onTap)
    val currentReady by rememberUpdatedState(onReady)
    var engine by remember { mutableStateOf<MapLibreEngine?>(null) }
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
            onCreate(null)
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    DisposableEffect(owner, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            val state = owner.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(view) {
        view.getMapAsync { map ->
            pack.manifest.bbox.takeIf { it.size == 4 }?.let { (west, south, east, north) ->
                map.setLatLngBoundsForCameraTarget(LatLngBounds.from(north, east, south, west))
            }
            val created = MapLibreEngine(map, { currentPalette }) { point, key -> currentTap(point, key) }
            engine = created
            currentReady(created)
        }
    }

    LaunchedEffect(engine, dark) {
        val ready = engine ?: return@LaunchedEffect
        val style = withContext(Dispatchers.IO) { pack.style(dark) }
        ready.setStyle(style)
    }

    AndroidView(factory = { view }, modifier = modifier)
}
