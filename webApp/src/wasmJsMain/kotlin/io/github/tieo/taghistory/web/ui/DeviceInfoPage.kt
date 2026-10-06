package io.github.tieo.taghistory.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kilua.core.IComponent
import dev.kilua.html.div
import dev.kilua.html.p
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.util.absoluteDate
import io.github.tieo.taghistory.ui.util.relativeTime
import io.github.tieo.taghistory.web.host.nowMs

/** One tag's details: where and when it was last seen, its hardware, and rename and remove. */
@Composable
fun IComponent.deviceInfoPage(vm: DeviceInfoViewModel, onRemoved: () -> Unit) {
    val state by vm.state.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.removed) {
        if (state.removed) {
            onRemoved()
            navigate("/")
        }
    }

    page(state.displayName.ifEmpty { "Tag" }) {
        if (state.notFound) {
            section(null) { +"Tag not found." }
            return@page
        }
        div("flex flex-col items-center gap-3 py-2") {
            tagGlyph(state.emoji, state.displayName, size = "h-20 w-20 text-4xl")
            div("text-xl font-semibold") { +state.displayName }
        }
        div("flex flex-wrap justify-center gap-2") {
            navLink("/tags/${state.beaconId}/history", BUTTON_PRIMARY) {
                attribute("data-testid", "btn_view_history")
                icon("history")
                +"View history"
            }
            textButton("Rename", icon = "pencil", testId = "btn_rename") { renaming = true }
            textButton("Remove", BUTTON_DANGER, icon = "trash-2", testId = "btn_remove") { removing = true }
        }
        state.lastLocation?.let { loc ->
            section("Last seen") {
                field("When", relativeTime(loc.timestamp, nowMs()))
                // 0 means unknown (fixes recovered without one), not exact.
                if (loc.horizontalAccuracy > 0) field("Accuracy", "±${loc.horizontalAccuracy} m")
                if (loc.confidence > 0) field("Confidence", loc.confidence.toString())
            }
        }
        state.info?.let { info ->
            val hardware = buildList {
                info.model?.takeIf { it.isNotBlank() }?.let { add("Model" to it) }
                info.systemVersion?.takeIf { it.isNotBlank() }?.let { add("Firmware" to it) }
                info.productId?.let { add("Product ID" to it.toString()) }
                info.vendorId?.let { add("Vendor ID" to it.toString()) }
                info.pairingDate?.let { add("Paired" to absoluteDate(it)) }
                add("Private key" to if (info.hasPrivateKey) "stored" else "missing")
            }
            section("Hardware") { for ((k, v) in hardware) field(k, v) }
            section("Identifiers") {
                field("Beacon ID", state.beaconId, monospace = true)
                info.stableIdentifier?.let { field("Stable ID", it, monospace = true) }
                info.namingRecordId?.let { field("Naming record", it, monospace = true) }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(state.displayName) }
        var emoji by remember { mutableStateOf(state.emoji.orEmpty()) }
        modal("Rename tag", onDismiss = { renaming = false }, testId = "dialog_rename", actions = {
            textButton("Cancel", testId = "btn_rename_cancel") { renaming = false }
            textButton("Save", BUTTON_PRIMARY, disabled = name.isBlank(), testId = "btn_rename_save") {
                vm.rename(name.trim(), emoji.trim().ifBlank { null })
                renaming = false
            }
        }) {
            textField("Name", name, { name = it }, testId = "field_rename_name", autofocus = true)
            textField("Emoji", emoji, { emoji = it }, testId = "field_rename_emoji", maxLength = 4)
        }
    }

    if (removing) {
        modal("Remove device?", onDismiss = { removing = false }, testId = "dialog_remove", actions = {
            textButton("Cancel", testId = "btn_remove_cancel") { removing = false }
            textButton("Remove", BUTTON_DANGER, testId = "btn_remove_confirm") {
                removing = false
                vm.remove()
            }
        }) {
            p("text-sm text-zinc-600 dark:text-zinc-300") {
                +"\"${state.displayName}\" will be hidden from the app. The tag is not unpaired from iCloud."
            }
        }
    }

    state.editError?.let { message ->
        modal("Couldn't save the change", onDismiss = vm::dismissEditError, actions = {
            textButton("OK", BUTTON_PRIMARY) { vm.dismissEditError() }
        }) {
            p("text-sm text-zinc-600 dark:text-zinc-300") { +message }
        }
    }
}
