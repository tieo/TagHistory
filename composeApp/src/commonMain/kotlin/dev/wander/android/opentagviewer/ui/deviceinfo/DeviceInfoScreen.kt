package io.github.tieo.taghistory.ui.deviceinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.tieo.taghistory.data.model.BeaconInformation
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.ui.nav.PushedScreenScaffold
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Composable
fun DeviceInfoScreen(
    viewModel: DeviceInfoViewModel,
    onBack: () -> Unit,
    onOpenHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }

    var renaming by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }

    PushedScreenScaffold(
        title = state.displayName,
        onBack = onBack,
        modifier = modifier,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmojiHero(emoji = state.emoji, name = state.displayName)

            state.lastLocation?.let { LastSeenCard(it) }
            state.info?.let { HardwareCard(it) }
            state.info?.let { IdentifiersCard(it, state.beaconId) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOpenHistory(state.beaconId) },
                    modifier = Modifier.fillMaxWidth().testTag("btn_view_history"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(" View history")
                }

                OutlinedButton(
                    onClick = { renaming = true },
                    modifier = Modifier.fillMaxWidth().testTag("btn_rename"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(" Rename")
                }

                OutlinedButton(
                    onClick = { confirmingRemove = true },
                    modifier = Modifier.fillMaxWidth().testTag("btn_remove"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(" Remove device")
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(
            initialName = state.displayName,
            initialEmoji = state.emoji.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name, emoji ->
                viewModel.rename(name, emoji.ifBlank { null })
                renaming = false
            },
        )
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove device?") },
            text = { Text("\"${state.displayName}\" will be hidden from the app. The tag is not unpaired from iCloud.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    viewModel.remove()
                }, modifier = Modifier.testTag("btn_remove_confirm")) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }, modifier = Modifier.testTag("btn_remove_cancel")) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmojiHero(emoji: String?, name: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(84.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                val glyph = emoji ?: name.firstOrNull()?.uppercase() ?: "•"
                Text(glyph, style = MaterialTheme.typography.displaySmall)
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun LastSeenCard(loc: BeaconLocationReport) {
    DetailSection("Last seen") {
        FieldRow("When", relativeTime(loc.timestamp))
        FieldRow("Accuracy", "±${loc.horizontalAccuracy} m")
        if (loc.confidence > 0) FieldRow("Confidence", loc.confidence.toString())
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun HardwareCard(info: BeaconInformation) {
    val pairs = buildList<Pair<String, String>> {
        info.model?.takeIf { it.isNotBlank() }?.let { add("Model" to it) }
        info.systemVersion?.takeIf { it.isNotBlank() }?.let { add("Firmware" to it) }
        info.productId?.let { add("Product ID" to it.toString()) }
        info.vendorId?.let { add("Vendor ID" to it.toString()) }
        info.pairingDate?.let { add("Paired" to absoluteDate(it)) }
        add("Private key" to if (info.hasPrivateKey) "stored" else "missing")
    }
    if (pairs.isEmpty()) return
    DetailSection("Hardware") {
        pairs.forEachIndexed { idx, (k, v) ->
            if (idx > 0) HorizontalDivider()
            FieldRow(k, v)
        }
    }
}

@Composable
private fun IdentifiersCard(info: BeaconInformation, beaconId: String) {
    DetailSection("Identifiers") {
        FieldRow("Beacon ID", beaconId, monospace = true)
        info.stableIdentifier?.let {
            HorizontalDivider()
            FieldRow("Stable ID", it, monospace = true)
        }
        info.namingRecordId?.let {
            HorizontalDivider()
            FieldRow("Naming record", it, monospace = true)
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.62f),
        )
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    initialEmoji: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("field_rename_name"),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(4) },
                    label = { Text("Emoji (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, emoji) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("btn_rename_save"),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn_rename_cancel")) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalTime::class)
private fun relativeTime(ms: Long): String {
    val delta = Clock.System.now().toEpochMilliseconds() - ms
    if (delta < 0) return absoluteDate(ms)
    val s = delta / 1_000
    return when {
        s < 60 -> "just now"
        s < 3_600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3_600} h ago"
        s < 86_400 * 7 -> "${s / 86_400} d ago"
        else -> absoluteDate(ms)
    }
}

@OptIn(ExperimentalTime::class)
private fun absoluteDate(ms: Long): String {
    // Plain ISO-8601 — good enough until we wire kotlinx-datetime for locale-aware output.
    return Instant.fromEpochMilliseconds(ms).toString().substringBefore('T').take(10)
}

