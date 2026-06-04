package io.github.tieo.taghistory.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tieo.taghistory.ui.map.TagCardUi
import kotlinx.coroutines.launch

/**
 * Full-screen tag management. Lives outside MapScreen so the user can
 * lay out / rename / delete / import / export without the map widget
 * fighting for the same vertical space. Replaces the previous tiny
 * AlertDialog edit flow.
 *
 * Export only fires when at least one row is checked. Import is the
 * same callback the map's "No AirTags yet" + Settings buttons use, so
 * the picker + parse pipeline already in place is reused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTagsScreen(
    cards: List<TagCardUi>,
    onBack: () -> Unit,
    onRename: (beaconId: String, name: String, emoji: String?) -> Unit,
    onRemove: (beaconId: String) -> Unit,
    onImport: (suspend () -> String?)?,
    onExportSelected: ((beaconIds: List<String>) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Per-id draft state. Reset whenever the underlying card identity
    // set changes (an import or remove can change the list).
    val nameDrafts = remember(cards.map { it.beaconId }) {
        mutableStateMapOf<String, String>().apply {
            cards.forEach { put(it.beaconId, it.displayName) }
        }
    }
    val emojiDrafts = remember(cards.map { it.beaconId }) {
        mutableStateMapOf<String, String>().apply {
            cards.forEach { put(it.beaconId, it.emoji.orEmpty()) }
        }
    }
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }
    val selectedCount = selectedIds.count { it.value }

    var pendingRemove by remember { mutableStateOf<TagCardUi?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Manage tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val msg = importMessage
                    if (msg != null) {
                        LaunchedEffect(msg) {
                            kotlinx.coroutines.delay(8000)
                            importMessage = null
                        }
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onImport != null) {
                            Button(
                                onClick = {
                                    if (importing) return@Button
                                    importing = true
                                    scope.launch {
                                        val result = try {
                                            onImport.invoke()
                                        } catch (e: Exception) {
                                            "Import failed: ${e.message ?: e::class.simpleName}"
                                        }
                                        importing = false
                                        importMessage = result ?: "Cancelled"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !importing,
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (importing) "Importing…" else "Import zip")
                            }
                        }
                        if (onExportSelected != null) {
                            Button(
                                onClick = {
                                    val ids = selectedIds.filter { it.value }.keys.toList()
                                    if (ids.isNotEmpty()) onExportSelected(ids)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = selectedCount > 0,
                                colors = ButtonDefaults.outlinedButtonColors(),
                            ) {
                                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (selectedCount == 0) "Export…" else "Export ($selectedCount)")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cards, key = { it.beaconId }) { card ->
                ManageTagRow(
                    card = card,
                    selected = selectedIds[card.beaconId] == true,
                    name = nameDrafts[card.beaconId] ?: card.displayName,
                    emoji = emojiDrafts[card.beaconId] ?: card.emoji.orEmpty(),
                    onToggleSelect = { selectedIds[card.beaconId] = !(selectedIds[card.beaconId] ?: false) },
                    onNameChange = { nameDrafts[card.beaconId] = it },
                    onEmojiChange = { emojiDrafts[card.beaconId] = it.take(4) },
                    onConfirmEdit = {
                        val n = nameDrafts[card.beaconId]?.trim().orEmpty()
                        val e = emojiDrafts[card.beaconId]?.trim().orEmpty().ifEmpty { null }
                        if (n.isNotEmpty()) onRename(card.beaconId, n, e)
                    },
                    onRequestRemove = { pendingRemove = card },
                )
            }
        }
    }

    val toRemove = pendingRemove
    if (toRemove != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove tag?") },
            text = {
                Text(
                    "\"${toRemove.displayName}\" will be hidden from the app. " +
                        "The tag is not unpaired from iCloud.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(toRemove.beaconId)
                    selectedIds.remove(toRemove.beaconId)
                    pendingRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ManageTagRow(
    card: TagCardUi,
    selected: Boolean,
    name: String,
    emoji: String,
    onToggleSelect: () -> Unit,
    onNameChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onConfirmEdit: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val dirty = name.trim() != card.displayName.trim() ||
        emoji.trim().ifEmpty { null } != card.emoji
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tap target for selection — circle filled when selected,
                // empty otherwise. Lets the row double as both an editor
                // and a multi-select source for export.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onToggleSelect() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = emoji,
                    onValueChange = onEmojiChange,
                    singleLine = true,
                    label = { Text("Emoji", fontSize = 11.sp) },
                    modifier = Modifier.width(86.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    card.beaconId.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                if (dirty) {
                    TextButton(onClick = onConfirmEdit) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                }
                IconButton(onClick = onRequestRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
