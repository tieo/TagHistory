package io.github.tieo.taghistory

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tieo.taghistory.data.model.UserSettings
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoScreen
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.history.HistoryScreen
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.information.InformationScreen
import io.github.tieo.taghistory.ui.login.AppleLoginViewModel
import io.github.tieo.taghistory.ui.login.LoginScreen
import io.github.tieo.taghistory.ui.map.MapScreen
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.nav.NavState
import io.github.tieo.taghistory.ui.nav.PlatformBackHandler
import io.github.tieo.taghistory.ui.nav.Screen
import io.github.tieo.taghistory.ui.settings.SettingsScreen
import io.github.tieo.taghistory.ui.settings.SettingsViewModel
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Navigation root. Map is the home screen with a floating settings
 * button top-right; every other screen (Settings, DeviceInfo, History,
 * Information) is pushed onto the nav stack and dismissed via back.
 */
data class AppHostFactories(
    val createLogin: () -> AppleLoginViewModel,
    val createMap: () -> MapViewModel?,
    val createSettings: () -> SettingsViewModel,
    val createDeviceInfo: (String) -> DeviceInfoViewModel,
    val createHistory: (String) -> HistoryViewModel,
    val appVersion: String,
    val openUrl: (String) -> Unit,
    /**
     * Fire a platform "navigate to coordinates" intent. On Android this
     * opens the system app-chooser (Google Maps, Waze, OsmAnd, …) via a
     * `geo:lat,lon?q=lat,lon(label)` URI. No-op on desktop/iOS stubs.
     */
    val routeTo: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    val settingsFlow: StateFlow<UserSettings> = MutableStateFlow(UserSettings()),
    val onImport: (suspend () -> String?)? = null,
    /**
     * One-shot refresh: fetch current reports, persist them, return a
     * user-facing status line (or `null` for no toast). Wired by Settings'
     * "Refresh now" button. Separate from the Map tab's periodic refresh
     * so the user can force a sync from any screen.
     */
    val onRefreshNow: (suspend () -> String?)? = null,
)

@Composable
fun App(factories: AppHostFactories) {
    val settings by factories.settingsFlow.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = settings.useDarkTheme ?: systemDark

    TagHistoryTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize().withTestTagsAsResourceId()) {
            var showLogin by remember { mutableStateOf(factories.createMap() == null) }
            if (showLogin) {
                val vm = remember { factories.createLogin() }
                LaunchedEffect(vm) {
                    vm.state.collect { s -> if (s.finished) showLogin = false }
                }
                LoginScreen(viewModel = vm)
            } else {
                AuthedNav(factories = factories, onSignedOut = { showLogin = true })
            }
        }
    }
}

@Composable
private fun AuthedNav(
    factories: AppHostFactories,
    onSignedOut: () -> Unit,
) {
    var nav by remember { mutableStateOf(NavState.rooted(Screen.Map)) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted so revisiting Map doesn't re-create the ViewModel (which
    // would re-run boot + refresh each time, costing DB+network work).
    val mapVm = remember { factories.createMap() }
    val settingsVm = remember { factories.createSettings() }
    // After a successful import, newly stored beacons must be picked up by
    // the already-running MapViewModel (boot() ran before the import).
    val onImport = remember(factories.onImport) {
        factories.onImport?.let { orig ->
            suspend {
                val result = orig()
                if (result != null) mapVm?.reboot()
                result
            }
        }
    }

    PlatformBackHandler(enabled = nav.canGoBack) { nav = nav.pop() }

    val current = nav.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) {
            when (val screen = current) {
                is Screen.Map -> {
                    if (mapVm == null) {
                        LaunchedEffect(Unit) { onSignedOut() }
                    } else {
                        LaunchedEffect(mapVm) {
                            mapVm.state.collect { s -> if (s.requireLogin) onSignedOut() }
                        }
                        // Re-read names/emoji from DB each time the map screen
                        // comes into view so renames made in DeviceInfo are
                        // immediately reflected in cards and markers.
                        LaunchedEffect(Unit) { mapVm.refreshNames() }
                        Box(modifier = Modifier.fillMaxSize()) {
                            MapScreen(
                                viewModel = mapVm,
                                onOpenDevice = { nav = nav.push(Screen.DeviceInfo(it)) },
                                onOpenHistory = { id, title ->
                                    nav = nav.push(Screen.History(id, title))
                                },
                                onRoute = factories.routeTo,
                                onImport = onImport,
                                snackbarHostState = snackbarHostState,
                            )
                            // Floating settings entry point, replaces the
                            // bottom nav bar. Top-right so it doesn't
                            // collide with the bottom tag pager.
                            FilledIconButton(
                                onClick = { nav = nav.push(Screen.Settings) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(12.dp)
                                    .testTag("btn_settings"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                        .copy(alpha = 0.92f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                }
                is Screen.Settings -> {
                    LaunchedEffect(settingsVm) {
                        settingsVm.state.collect { s -> if (s.signedOut) onSignedOut() }
                    }
                    SettingsScreen(
                        viewModel = settingsVm,
                        onOpenInformation = { nav = nav.push(Screen.Information) },
                        onImport = onImport,
                        onRefreshNow = factories.onRefreshNow,
                    )
                }
                is Screen.Information -> {
                    InformationScreen(
                        versionName = factories.appVersion,
                        onBack = { nav = nav.pop() },
                        onOpenUrl = factories.openUrl,
                    )
                }
                is Screen.DeviceInfo -> {
                    val vm = remember(screen.beaconId) { factories.createDeviceInfo(screen.beaconId) }
                    DeviceInfoScreen(
                        viewModel = vm,
                        onBack = { nav = nav.pop() },
                        onOpenHistory = { id ->
                            val title = vm.state.value.displayName
                            nav = nav.push(Screen.History(id, title))
                        },
                    )
                }
                is Screen.History -> {
                    val vm = remember(screen.beaconId) { factories.createHistory(screen.beaconId) }
                    HistoryScreen(
                        viewModel = vm,
                        title = screen.title,
                        onBack = { nav = nav.pop() },
                    )
                }
            }
        }
    }
}

/** Back-compat overload for tests/placeholder hosts that only need login. */
@Composable
fun App(viewModelFactory: () -> AppleLoginViewModel = ::placeholderViewModel) {
    TagHistoryTheme {
        Surface(modifier = Modifier.fillMaxSize().withTestTagsAsResourceId()) {
            val vm = remember { viewModelFactory() }
            LoginScreen(viewModel = vm)
        }
    }
}

private fun placeholderViewModel(): AppleLoginViewModel = AppleLoginViewModel(
    startLogin = { _, _ ->
        kotlinx.coroutines.delay(400)
        throw IllegalStateException("Platform host did not inject an AppleLoginService factory")
    },
    onLoggedIn = {},
)
