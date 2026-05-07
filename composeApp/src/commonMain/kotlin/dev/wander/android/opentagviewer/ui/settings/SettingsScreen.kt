package io.github.tieo.taghistory.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenInformation: () -> Unit,
    onImport: (suspend () -> String?)? = null,
    onRefreshNow: (suspend () -> String?)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var refreshInFlight by remember { mutableStateOf(false) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // ---------- Appearance ----------
        SettingsSection("Appearance") {
            Text(
                "Theme",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ThemeModeSelector(
                current = state.current.useDarkTheme, // null = system
                onChange = viewModel::setThemeMode,
            )
        }

        // ---------- Background sync ----------
        SettingsSection("Background sync") {
            SwitchRow(
                label = "Auto-refresh when in background",
                subtitle = "Sync beacon reports without opening the app",
                checked = state.current.backgroundSyncEnabled == true,
                onChange = viewModel::setBackgroundSyncEnabled,
                tag = "toggle_background_sync",
            )
            if (state.current.backgroundSyncEnabled == true) {
                HorizontalDivider()
                SyncIntervalSlider(
                    current = state.current.backgroundSyncIntervalMinutes ?: DEFAULT_INTERVAL_MIN,
                    onChange = viewModel::setBackgroundSyncIntervalMinutes,
                )
            }
        }

        // ---------- Advanced ----------
        SettingsSection("Advanced") {
            SwitchRow(
                label = "Show debug data",
                subtitle = "Include diagnostic fields and raw payloads in the UI",
                checked = state.current.enableDebugData == true,
                onChange = viewModel::setEnableDebugData,
            )
        }

        // ---------- Data ----------
        SettingsSection("Data") {
            if (onRefreshNow != null) {
                Button(
                    onClick = {
                        if (refreshInFlight) return@Button
                        refreshInFlight = true
                        scope.launch {
                            val msg = try {
                                onRefreshNow.invoke() ?: "Refresh complete"
                            } catch (e: Exception) {
                                "Refresh failed: ${e.message ?: "unknown error"}"
                            }
                            refreshMessage = msg
                            refreshInFlight = false
                        }
                    },
                    enabled = !refreshInFlight,
                    modifier = Modifier.fillMaxWidth().testTag("btn_refresh_now"),
                ) {
                    if (refreshInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Refreshing…")
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(" Refresh now")
                    }
                }
            }
            val refreshMsg = refreshMessage
            if (refreshMsg != null) {
                LaunchedEffect(refreshMsg) {
                    delay(4000)
                    refreshMessage = null
                }
                Text(
                    refreshMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onImport != null) {
                if (onRefreshNow != null) HorizontalDivider()
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val msg = onImport.invoke()
                            if (msg != null) importMessage = msg
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(" Import tags from FindMy export…")
                }
            }
            val msg = importMessage
            if (msg != null) {
                LaunchedEffect(msg) {
                    delay(4000)
                    importMessage = null
                }
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- Account ----------
        SettingsSection("Account") {
            FilledTonalButton(
                onClick = { confirmingSignOut = true },
                modifier = Modifier.fillMaxWidth().testTag("btn_sign_out"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(" Sign out of Apple ID")
            }
        }

        // ---------- About ----------
        SettingsSection("About") {
            OutlinedButton(onClick = onOpenInformation, modifier = Modifier.fillMaxWidth().testTag("btn_about")) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(" About TagHistory")
            }
        }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Your Apple account credentials will be removed from this " +
                        "device. Background sync will stop until you sign in again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    viewModel.signOut()
                }, modifier = Modifier.testTag("btn_sign_out_confirm")) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }, modifier = Modifier.testTag("btn_sign_out_cancel")) { Text("Cancel") }
            },
        )
    }
}

/**
 * Background-sync interval is exposed as a discrete slider snapped to the
 * [INTERVAL_STEPS] presets — the Java app used a free-text field and users
 * routinely entered "1" (crushing battery) or "600" (silently capped by
 * WorkManager). Presets make the tradeoff legible.
 */
private val INTERVAL_STEPS: IntArray = intArrayOf(15, 30, 60, 120, 240, 480, 720, 1440)
private const val DEFAULT_INTERVAL_MIN: Int = 60

private fun snapToStep(value: Int): Int =
    INTERVAL_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_INTERVAL_MIN

private fun formatInterval(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

@Composable
private fun SyncIntervalSlider(
    current: Int,
    onChange: (Int) -> Unit,
) {
    val snapped = snapToStep(current)
    val idx = INTERVAL_STEPS.indexOf(snapped).let { if (it < 0) 2 else it }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Interval", style = MaterialTheme.typography.labelLarge)
            Text(formatInterval(snapped), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = idx.toFloat(),
            onValueChange = { v ->
                val clamped = v.toInt().coerceIn(0, INTERVAL_STEPS.lastIndex)
                onChange(INTERVAL_STEPS[clamped])
            },
            valueRange = 0f..INTERVAL_STEPS.lastIndex.toFloat(),
            steps = INTERVAL_STEPS.size - 2, // endpoints are not counted
        )
        Text(
            "Shorter intervals use more battery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

/**
 * Tri-state theme selector: null = follow system, false = light, true = dark.
 * Compose MP's Material3 1.10-alpha doesn't ship SegmentedButton in the
 * offline cache, so this rolls its own three-button row with an
 * indicator on the current selection.
 */
@Composable
private fun ThemeModeSelector(
    current: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeChoice(
            label = "System",
            selected = current == null,
            onClick = { onChange(null) },
            modifier = Modifier.weight(1f).testTag("btn_theme_system"),
        )
        ThemeChoice(
            label = "Light",
            selected = current == false,
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f).testTag("btn_theme_light"),
        )
        ThemeChoice(
            label = "Dark",
            selected = current == true,
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f).testTag("btn_theme_dark"),
        )
    }
}

@Composable
private fun ThemeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .let { if (tag != null) it.testTag(tag) else it }
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.padding(start = 12.dp)) {
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
