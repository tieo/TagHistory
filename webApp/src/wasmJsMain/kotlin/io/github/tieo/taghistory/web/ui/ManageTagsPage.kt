package io.github.tieo.taghistory.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.kilua.core.IComponent
import dev.kilua.form.check.checkBox
import dev.kilua.form.text.text
import dev.kilua.html.div
import dev.kilua.html.li
import dev.kilua.html.p
import dev.kilua.html.span
import dev.kilua.html.ul
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.map.TagCardUi
import kotlinx.coroutines.launch

/** Every tag with rename and remove, selection for removing several, and importing an export. */
@Composable
fun IComponent.manageTagsPage(vm: MapViewModel, onImport: suspend () -> String?) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<List<TagCardUi>?>(null) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    page("Tags", actions = {
        if (selected.isNotEmpty()) {
            iconButton("trash-2", "Remove selected", testId = "btn_remove_selected") {
                removing = state.cards.filter { it.beaconId in selected }
            }
        }
        textButton(if (importing) "Importing…" else "Import", BUTTON_PRIMARY, icon = "upload", disabled = importing, testId = "btn_import") {
            importing = true
            message = null
            scope.launch {
                message = runCatching { onImport() ?: "Import cancelled" }.getOrElse { "Import failed: ${it.message ?: it::class.simpleName}" }
                importing = false
            }
        }
    }) {
        message?.let {
            div("rounded-xl bg-violet-50 px-4 py-3 text-sm text-violet-900 dark:bg-violet-950/50 dark:text-violet-200") {
                attribute("role", "status")
                attribute("data-testid", "import_message")
                +it
            }
        }
        if (state.cards.isEmpty()) {
            section(null) { +"No tags yet. Import an OpenTagViewer export (.zip) to add them." }
            return@page
        }
        div("flex items-center justify-between px-1 text-sm text-zinc-500 dark:text-zinc-400") {
            span { +"${state.cards.size} tags${if (selected.isEmpty()) "" else " · ${selected.size} selected"}" }
            if (selected.isEmpty()) {
                textButton("Select all", className = "h-8 px-3") { selected = state.cards.map { it.beaconId }.toSet() }
            } else {
                textButton("Clear", className = "h-8 px-3") { selected = emptySet() }
            }
        }
        ul("flex flex-col divide-y divide-zinc-200 overflow-hidden rounded-2xl bg-white ring-1 ring-zinc-200 dark:divide-zinc-800 dark:bg-zinc-900 dark:ring-zinc-800") {
            for (card in state.cards) {
                li("flex items-center gap-3 px-3 py-2") {
                    attribute("data-testid", "manage_row_${card.beaconId}")
                    checkBox(card.beaconId in selected, className = "h-4 w-4 accent-violet-600") {
                        ariaLabel("Select ${card.displayName}")
                        onClick {
                            selected = if (card.beaconId in selected) selected - card.beaconId else selected + card.beaconId
                        }
                    }
                    if (editing == card.beaconId) {
                        editRow(card, onSave = { name, emoji ->
                            vm.renameBeacon(card.beaconId, name, emoji)
                            editing = null
                        }, onCancel = { editing = null })
                    } else {
                        tagGlyph(card.emoji, card.displayName)
                        div("min-w-0 flex-1") {
                            div("truncate text-sm font-semibold") { +card.displayName }
                            div("truncate text-xs text-zinc-500 dark:text-zinc-400") {
                                +(card.addressLine ?: "ID ${card.beaconId.take(8)}")
                            }
                        }
                        iconButton("pencil", "Rename ${card.displayName}", testId = "btn_edit_${card.beaconId}") { editing = card.beaconId }
                        iconButton("trash-2", "Remove ${card.displayName}", testId = "btn_delete_${card.beaconId}", className = "text-red-600 dark:text-red-400") {
                            removing = listOf(card)
                        }
                    }
                }
            }
        }
    }

    removing?.let { cards ->
        val title = if (cards.size == 1) "Remove device?" else "Remove ${cards.size} tags?"
        modal(title, onDismiss = { removing = null }, actions = {
            textButton("Cancel") { removing = null }
            textButton(if (cards.size == 1) "Remove" else "Remove all", BUTTON_DANGER, testId = "btn_remove_confirm") {
                cards.forEach { vm.removeBeacon(it.beaconId) }
                selected = selected - cards.map { it.beaconId }.toSet()
                removing = null
            }
        }) {
            p("text-sm text-zinc-600 dark:text-zinc-300") {
                +if (cards.size == 1) {
                    "\"${cards[0].displayName}\" will be hidden from the app. The tag is not unpaired from iCloud."
                } else {
                    "These tags will be hidden from the app. They are not unpaired from iCloud."
                }
            }
        }
    }
}

@Composable
private fun IComponent.editRow(card: TagCardUi, onSave: (String, String?) -> Unit, onCancel: () -> Unit) {
    var name by remember(card.beaconId) { mutableStateOf(card.displayName) }
    var emoji by remember(card.beaconId) { mutableStateOf(card.emoji.orEmpty()) }
    text(emoji, maxlength = 4, className = "$INPUT w-14 text-center text-xl") {
        ariaLabel("Emoji")
        attribute("data-testid", "field_emoji_${card.beaconId}")
        onInput { emoji = value ?: "" }
    }
    text(name, className = "$INPUT min-w-0 flex-1") {
        ariaLabel("Name")
        autofocus(true)
        attribute("data-testid", "field_name_${card.beaconId}")
        onInput { name = value ?: "" }
    }
    iconButton("check", "Save", disabled = name.isBlank(), testId = "btn_save_${card.beaconId}") {
        onSave(name.trim(), emoji.trim().ifBlank { null })
    }
    iconButton("x", "Cancel", onClick = onCancel)
}
