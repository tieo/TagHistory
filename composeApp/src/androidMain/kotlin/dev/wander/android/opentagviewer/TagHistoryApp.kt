package io.github.tieo.taghistory

import android.app.Application
import org.maplibre.android.MapLibre

class TagHistoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pre-init MapLibre native libs so the first MapView open doesn't pay
        // the cold native-load cost.
        MapLibre.getInstance(this)
    }
}
