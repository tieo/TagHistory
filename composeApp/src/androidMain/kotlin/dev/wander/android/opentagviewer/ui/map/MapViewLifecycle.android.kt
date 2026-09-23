package io.github.tieo.taghistory.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

/**
 * Drives a MapLibre [MapView] from the surrounding lifecycle and destroys it
 * when it leaves composition. When the activity finishes, ON_DESTROY reaches
 * the observer and then the composition is disposed, so both paths ask for a
 * destroy; MapLibre fails on the second one (NPE or "Map has been destroyed"),
 * hence the once-only guard. Both the main map and the history map use this.
 */
@Composable
internal fun BindMapViewLifecycle(mapView: MapView) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        val destroyOnce = {
            if (!destroyed) {
                destroyed = true
                runCatching { mapView.onDestroy() }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> destroyOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyOnce()
        }
    }
}
