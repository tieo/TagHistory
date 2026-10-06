package io.github.tieo.taghistory.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import app.softwork.routingcompose.Router
import dev.kilua.core.IComponent
import dev.kilua.html.IA
import dev.kilua.html.ITag
import dev.kilua.html.a
import dev.kilua.html.button
import dev.kilua.html.div
import dev.kilua.html.h1
import dev.kilua.html.h2
import dev.kilua.html.label
import dev.kilua.html.span
import dev.kilua.form.text.text
import dev.kilua.routing.global
import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import web.mouse.MAIN
import web.mouse.MouseButton
import web.mouse.MouseEvent

// Building blocks every page shares: links that navigate inside the app,
// icon buttons, the page frame with its back link, cards and buttons.

/** Moves to [path] inside the app. */
fun navigate(path: String) = Router.global.navigate(path)

/**
 * A real link to [href] that navigates inside the app on a plain click.
 * Modifier clicks and middle clicks keep the browser's behaviour (new tab,
 * new window).
 */
@Composable
fun IComponent.navLink(href: String, className: String? = null, content: @Composable IA.() -> Unit) {
    a(href, className = className) {
        onClick { e: MouseEvent ->
            if (e.button == MouseButton.MAIN && !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey) {
                e.preventDefault()
                navigate(href)
            }
        }
        content()
    }
}

/** A Lucide icon. */
@Composable
fun IComponent.icon(name: String, className: String = "") {
    span("icon-$name leading-none $className") { attribute("aria-hidden", "true") }
}

const val ICON_BUTTON =
    "inline-flex h-10 w-10 items-center justify-center rounded-full text-lg text-zinc-600 transition " +
        "hover:bg-zinc-900/5 hover:text-zinc-900 focus-visible:outline-2 focus-visible:outline-violet-500 " +
        "disabled:opacity-40 dark:text-zinc-300 dark:hover:bg-white/10 dark:hover:text-white"

/** A round icon-only button with a tooltip and accessible name. */
@Composable
fun IComponent.iconButton(
    icon: String,
    label: String,
    disabled: Boolean = false,
    testId: String? = null,
    className: String = "",
    onClick: () -> Unit,
) {
    button(className = "$ICON_BUTTON $className") {
        attribute("type", "button")
        title(label)
        ariaLabel(label)
        disabled(disabled)
        testId?.let { attribute("data-testid", it) }
        onClick { onClick() }
        icon(icon)
    }
}

/** An icon-only link, styled like [iconButton]. */
@Composable
fun IComponent.iconLink(icon: String, label: String, href: String, testId: String? = null) {
    navLink(href, ICON_BUTTON) {
        title(label)
        ariaLabel(label)
        testId?.let { attribute("data-testid", it) }
        icon(icon)
    }
}

const val BUTTON_PRIMARY =
    "inline-flex h-10 items-center justify-center gap-2 rounded-full bg-violet-600 px-5 text-sm font-semibold " +
        "text-white shadow-sm transition hover:bg-violet-500 focus-visible:outline-2 focus-visible:outline-offset-2 " +
        "focus-visible:outline-violet-500 disabled:cursor-default disabled:opacity-50"
const val BUTTON_SECONDARY =
    "inline-flex h-10 items-center justify-center gap-2 rounded-full px-5 text-sm font-semibold text-violet-700 " +
        "ring-1 ring-zinc-300 transition hover:bg-violet-50 focus-visible:outline-2 focus-visible:outline-violet-500 " +
        "disabled:cursor-default disabled:opacity-50 dark:text-violet-300 dark:ring-zinc-700 dark:hover:bg-white/5"
const val BUTTON_DANGER =
    "inline-flex h-10 items-center justify-center gap-2 rounded-full px-5 text-sm font-semibold text-red-700 " +
        "ring-1 ring-red-200 transition hover:bg-red-50 focus-visible:outline-2 focus-visible:outline-red-500 " +
        "disabled:opacity-50 dark:text-red-300 dark:ring-red-900 dark:hover:bg-red-950/40"

/** A labelled button; [style] is one of the BUTTON_ constants. */
@Composable
fun IComponent.textButton(
    label: String,
    style: String = BUTTON_SECONDARY,
    icon: String? = null,
    disabled: Boolean = false,
    testId: String? = null,
    className: String = "",
    onClick: () -> Unit,
) {
    button(className = "$style $className") {
        attribute("type", "button")
        disabled(disabled)
        testId?.let { attribute("data-testid", it) }
        onClick { onClick() }
        icon?.let { icon(it) }
        span { +label }
    }
}

/**
 * The frame of every page but the map: a header with a back link and the
 * title, and a centered column for the content.
 */
@Composable
fun IComponent.page(title: String, backHref: String = "/", actions: @Composable IComponent.() -> Unit = {}, content: @Composable IComponent.() -> Unit) {
    div("min-h-screen bg-zinc-50 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100") {
        div("sticky top-0 z-10 border-b border-zinc-200 bg-zinc-50/85 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/85") {
            div("mx-auto flex h-14 max-w-3xl items-center gap-2 px-3") {
                iconLink("arrow-left", "Back", backHref, testId = "btn_back")
                h1("min-w-0 flex-1 truncate text-lg font-semibold") { +title }
                actions()
            }
        }
        div("mx-auto flex max-w-3xl flex-col gap-6 px-4 py-6") { content() }
    }
}

/** A titled group of related content. */
@Composable
fun IComponent.section(title: String?, content: @Composable ITag<*>.() -> Unit) {
    div("flex flex-col gap-2") {
        title?.let { h2("px-1 text-sm font-semibold text-violet-700 dark:text-violet-300") { +it } }
        div("flex flex-col gap-4 rounded-2xl bg-white p-5 shadow-sm ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800") {
            content()
        }
    }
}

/** A label and its value on one line. */
@Composable
fun IComponent.field(label: String, value: String, monospace: Boolean = false) {
    div("flex items-baseline justify-between gap-4") {
        span("text-sm text-zinc-500 dark:text-zinc-400") { +label }
        span("min-w-0 truncate text-right text-sm ${if (monospace) "font-mono" else "font-medium"}") {
            title(value)
            +value
        }
    }
}

/** The round glyph for a tag: its emoji, or its initial. */
@Composable
fun IComponent.tagGlyph(emoji: String?, name: String, size: String = "h-10 w-10 text-xl") {
    div("flex shrink-0 items-center justify-center rounded-full bg-violet-100 font-semibold text-violet-800 dark:bg-violet-950 dark:text-violet-200 $size") {
        +(emoji ?: name.firstOrNull()?.uppercase() ?: "?")
    }
}

/**
 * A modal dialog over the page: Escape and a click on the backdrop dismiss
 * it, and [actions] sit at its bottom right.
 */
@Composable
fun IComponent.modal(
    title: String,
    onDismiss: () -> Unit,
    testId: String? = null,
    actions: @Composable IComponent.() -> Unit,
    content: @Composable IComponent.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { if ((it as? KeyboardEvent)?.key == "Escape") dismiss() }
        window.addEventListener("keydown", listener)
        onDispose { window.removeEventListener("keydown", listener) }
    }
    div("fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-4 sm:items-center") {
        onClick { if (it.target == it.currentTarget) dismiss() }
        div("flex w-full max-w-md flex-col gap-4 rounded-2xl bg-white p-6 shadow-2xl dark:bg-zinc-900") {
            attribute("role", "dialog")
            attribute("aria-modal", "true")
            ariaLabel(title)
            testId?.let { attribute("data-testid", it) }
            h2("text-lg font-semibold") { +title }
            content()
            div("flex flex-wrap justify-end gap-2") { actions() }
        }
    }
}

const val INPUT =
    "h-11 w-full rounded-lg border border-zinc-300 bg-transparent px-3 text-sm outline-none transition " +
        "placeholder:text-zinc-400 focus:border-violet-500 focus:ring-2 focus:ring-violet-500/30 dark:border-zinc-700"

/** A labelled single-line text field. */
@Composable
fun IComponent.textField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testId: String? = null,
    maxLength: Int? = null,
    autofocus: Boolean = false,
) {
    label(className = "flex flex-col gap-1.5 text-sm font-medium") {
        span { +label }
        text(value, maxlength = maxLength, className = INPUT) {
            testId?.let { attribute("data-testid", it) }
            if (autofocus) autofocus(true)
            onInput { onValueChange(this.value ?: "") }
        }
    }
}
