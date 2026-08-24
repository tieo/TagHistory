package io.github.tieo.taghistory.ui.nav

sealed class Screen {
    data object Map : Screen()
    data object Settings : Screen()
    data object Information : Screen()
    data class DeviceInfo(val beaconId: String) : Screen()
    data class History(val beaconId: String, val title: String) : Screen()
    data object Nearby : Screen()
    data object ManageTags : Screen()
    data object SyncActivity : Screen()
}
