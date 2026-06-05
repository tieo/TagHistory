package io.github.tieo.taghistory.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tieo.taghistory.ui.map.TagCardUi
import kotlinx.coroutines.launch

/**
 * Full-screen tag manager. Sectioned card layout instead of the previous
 * input-form sprawl: each row reads as a tag card with emoji + name, a
 * subtle selection state, and edit/delete affordances tucked at the
 * bottom. Bottom action bar always shows Import + Export side by side;
 * Export is enabled iff at least one row is checked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTagsScreen(
    cards: List<TagCardUi>,
    onBack: () -> Unit,
    onRename: (beaconId: String, name: String, emoji: String?) -> Unit,
    onRemove: (beaconId: String) -> Unit,
    onImport: (suspend () -> String?)?,
    onExportSelected: (suspend (beaconIds: List<String>) -> String)?,
    modifier: Modifier = Modifier,
) {
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }
    val selectedCount = selectedIds.count { it.value }
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<TagCardUi?>(null) }
    var pendingBulkRemove by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tags", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${cards.size} tag${if (cards.size == 1) "" else "s"}" +
                                if (selectedCount > 0) " · $selectedCount selected" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedCount > 0) {
                        // Bulk-remove: only surfaces when at least one
                        // row is checked. Two-step confirm via the
                        // existing pending-remove dialog by setting
                        // pendingBulk = true.
                        IconButton(onClick = { pendingBulkRemove = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Remove selected",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = {
                            cards.forEach { selectedIds[it.beaconId] = false }
                        }) { Text("Clear") }
                    } else if (cards.isNotEmpty()) {
                        TextButton(onClick = {
                            cards.forEach { selectedIds[it.beaconId] = true }
                        }) { Text("Select all") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            ActionBar(
                importing = importing,
                selectedCount = selectedCount,
                hasImport = onImport != null,
                hasExport = onExportSelected != null,
                statusMessage = statusMessage,
                onImport = {
                    if (onImport == null) return@ActionBar
                    importing = true
                    scope.launch {
                        val result = try { onImport.invoke() } catch (e: Exception) {
                            "Import failed: ${e.message ?: e::class.simpleName}"
                        }
                        importing = false
                        statusMessage = result ?: "Cancelled"
                    }
                },
                onExport = {
                    if (onExportSelected == null) return@ActionBar
                    val ids = selectedIds.filter { it.value }.keys.toList()
                    if (ids.isEmpty()) return@ActionBar
                    scope.launch {
                        statusMessage = try {
                            onExportSelected.invoke(ids)
                        } catch (e: Exception) {
                            "Export failed: ${e.message ?: e::class.simpleName}"
                        }
                    }
                },
                onMessageExpire = { statusMessage = null },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cards, key = { it.beaconId }) { card ->
                TagManagementCard(
                    card = card,
                    selected = selectedIds[card.beaconId] == true,
                    isEditing = editingId == card.beaconId,
                    onToggleSelect = {
                        selectedIds[card.beaconId] = !(selectedIds[card.beaconId] ?: false)
                    },
                    onBeginEdit = { editingId = card.beaconId },
                    onEndEdit = { editingId = null },
                    onSave = { name, emoji ->
                        onRename(card.beaconId, name, emoji)
                        editingId = null
                    },
                    onRequestRemove = { pendingRemove = card },
                )
            }
        }
    }

    pendingRemove?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove tag?") },
            text = {
                Text(
                    "\"${card.displayName}\" will be hidden from the app. " +
                        "The tag is not unpaired from iCloud.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(card.beaconId)
                    selectedIds.remove(card.beaconId)
                    pendingRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }

    if (pendingBulkRemove) {
        val ids = selectedIds.filter { it.value }.keys.toList()
        AlertDialog(
            onDismissRequest = { pendingBulkRemove = false },
            title = { Text("Remove ${ids.size} tag${if (ids.size == 1) "" else "s"}?") },
            text = {
                Text(
                    "Selected tags will be hidden from the app. They stay " +
                        "paired in iCloud.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ids.forEach { id ->
                        onRemove(id)
                        selectedIds.remove(id)
                    }
                    pendingBulkRemove = false
                }) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkRemove = false }) { Text("Cancel") }
            },
        )
    }
}

// ---- Row ----

@Composable
private fun TagManagementCard(
    card: TagCardUi,
    selected: Boolean,
    isEditing: Boolean,
    onToggleSelect: () -> Unit,
    onBeginEdit: () -> Unit,
    onEndEdit: () -> Unit,
    onSave: (name: String, emoji: String?) -> Unit,
    onRequestRemove: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = if (selected) {
        androidx.compose.foundation.BorderStroke(
            1.4.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
    } else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = !isEditing) { onToggleSelect() },
        color = container,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = if (selected) 4.dp else 1.dp,
        shadowElevation = 1.dp,
        border = border,
    ) {
        // Local editor drafts live here so the avatar in edit mode can
        // ALSO double as the emoji input (no second emoji surface).
        var emojiDraft by remember(isEditing, card.beaconId) {
            mutableStateOf(card.emoji.orEmpty())
        }
        var nameDraft by remember(isEditing, card.beaconId) {
            mutableStateOf(card.displayName)
        }
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The avatar IS the emoji editor when isEditing. View-mode it
            // renders the glyph statically; edit-mode swaps to a
            // BasicTextField in the same 48 dp circle so there's exactly
            // one place the emoji shows up.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    )
                    .clickable(enabled = !isEditing) { onBeginEdit() },
                contentAlignment = Alignment.Center,
            ) {
                if (isEditing) {
                    BasicTextField(
                        value = emojiDraft,
                        onValueChange = { emojiDraft = it.take(4) },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (emojiDraft.isEmpty()) {
                                Text(
                                    "🏷",
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            inner()
                        },
                    )
                } else {
                    val glyph = card.emoji ?: card.displayName.firstOrNull()?.uppercase() ?: "●"
                    Text(glyph, fontSize = 24.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            // Title or inline name editor. Subtitle (address) only when
            // not editing — keeps the in-place editor compact.
            if (isEditing) {
                Column(modifier = Modifier.weight(1f)) {
                    BorderlessTextField(
                        value = nameDraft,
                        onChange = { nameDraft = it },
                        placeholder = "Tag name",
                    )
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        card.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = card.addressLine?.takeIf { it.isNotBlank() }
                        ?: "ID " + card.beaconId.take(8)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Action column: same three slots in both modes. In edit
            // mode the edit pencil becomes a Save check (same position,
            // different glyph) so the user's finger doesn't have to
            // hunt.
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionDot(selected)
                if (isEditing) {
                    ActionIconButton(
                        icon = Icons.Filled.Check,
                        contentDescription = "Save",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = {
                            val n = nameDraft.trim()
                            if (n.isNotEmpty()) {
                                onSave(n, emojiDraft.trim().ifEmpty { null })
                            } else {
                                onEndEdit()
                            }
                        },
                    )
                    ActionIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onEndEdit,
                    )
                } else {
                    ActionIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onBeginEdit,
                    )
                    ActionIconButton(
                        icon = Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onRequestRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) color else Color.Transparent)
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}


@Composable
private fun BorderlessTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- Bottom action bar ----

@Composable
private fun ActionBar(
    importing: Boolean,
    selectedCount: Int,
    hasImport: Boolean,
    hasExport: Boolean,
    statusMessage: String?,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onMessageExpire: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (statusMessage != null) {
                LaunchedEffect(statusMessage) {
                    kotlinx.coroutines.delay(8000)
                    onMessageExpire()
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onMessageExpire,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasImport) {
                    PillButton(
                        text = if (importing) "Importing…" else "Import zip",
                        icon = Icons.Filled.FileUpload,
                        primary = true,
                        enabled = !importing,
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (hasExport) {
                    PillButton(
                        text = if (selectedCount == 0) "Export" else "Export ($selectedCount)",
                        icon = Icons.Filled.FileDownload,
                        primary = false,
                        enabled = selectedCount > 0,
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.outline
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled) { onClick() },
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

