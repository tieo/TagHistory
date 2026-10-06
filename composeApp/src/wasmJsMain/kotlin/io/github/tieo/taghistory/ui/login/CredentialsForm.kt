package io.github.tieo.taghistory.ui.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLStyleElement

/**
 * The Apple sign-in fields as a real HTML form above the canvas, so the
 * browser's and extensions' password managers recognise, fill and save them
 * (`autocomplete` username and current-password inside a `<form>`).
 *
 * Values are read again when the form is submitted, because Chrome hides an
 * autofilled password from script until the user has interacted with the
 * page; the button stays enabled for the same reason, and the view model
 * checks the values on submit.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CredentialsForm(state: CredentialsFormState) {
    val current = rememberUpdatedState(state)
    val colors = MaterialTheme.colorScheme
    // State, so a theme switch re-runs the update block below.
    val css = rememberUpdatedState(credentialsCss(
        text = colors.onSurface,
        label = colors.onSurfaceVariant,
        outline = colors.outline,
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        disabled = colors.onSurface,
    ))
    WebElementView(
        factory = {
            val form = document.createElement("form") as HTMLFormElement
            form.className = "th-credentials"
            form.setAttribute("autocomplete", "on")
            form.innerHTML = """
                <style></style>
                <input name="username" type="email" autocomplete="username" placeholder="Apple ID email or phone"
                       autocapitalize="off" spellcheck="false" data-testid="field_email">
                <input name="password" type="password" autocomplete="current-password" placeholder="Password"
                       data-testid="field_password">
                <button type="submit" data-testid="btn_login">Log in →</button>
            """.trimIndent()
            val email = form.querySelector("input[name=username]") as HTMLInputElement
            val password = form.querySelector("input[name=password]") as HTMLInputElement
            email.value = current.value.email
            password.value = current.value.password
            // Typing stays in the DOM: a Compose state change re-inserts the
            // interop element, which blurs the focused input mid-typing. The
            // values reach the view model on submit.
            form.onsubmit = { event ->
                event.preventDefault()
                current.value.onEmailChange(email.value)
                current.value.onPasswordChange(password.value)
                current.value.onSubmit()
                null
            }
            form
        },
        // Two 56dp fields and a 48dp button with 16dp between them.
        modifier = Modifier.fillMaxWidth().height(192.dp),
        update = { form ->
            (form.querySelector("style") as HTMLStyleElement).textContent = css.value
            val busy = current.value.isLoggingIn
            form.querySelectorAll("input").let { inputs ->
                for (i in 0 until inputs.length) (inputs.item(i) as HTMLInputElement).disabled = busy
            }
            (form.querySelector("button") as HTMLButtonElement).apply {
                disabled = busy
                textContent = if (busy) "Logging in…" else "Log in →"
            }
        },
    )
}

private fun Color.css(alpha: Float = this.alpha): String {
    val argb = copy(alpha = alpha).toArgb()
    return "rgba(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, ${(argb ushr 24) / 255.0})"
}

/** Material 3 outlined fields and a filled button, in the current theme's colors. */
private fun credentialsCss(
    text: Color,
    label: Color,
    outline: Color,
    primary: Color,
    onPrimary: Color,
    disabled: Color,
) = """
    .th-credentials { display: flex; flex-direction: column; gap: 16px; margin: 0; width: 100%; height: 100%;
        font-family: Roboto, system-ui, sans-serif; }
    .th-credentials input { box-sizing: border-box; height: 56px; width: 100%; padding: 0 16px;
        border: 1px solid ${outline.css()}; border-radius: 4px; background: transparent;
        color: ${text.css()}; font: inherit; font-size: 16px; outline: none; }
    .th-credentials input::placeholder { color: ${label.css()}; }
    .th-credentials input:focus { border: 2px solid ${primary.css()}; padding: 0 15px; }
    .th-credentials input:disabled { color: ${disabled.css(0.38f)}; border-color: ${disabled.css(0.12f)}; }
    .th-credentials button { height: 48px; border: none; border-radius: 24px; cursor: pointer;
        background: ${primary.css()}; color: ${onPrimary.css()}; font: inherit; font-size: 15px; font-weight: 600; }
    .th-credentials button:disabled { cursor: default; background: ${disabled.css(0.12f)}; color: ${disabled.css(0.38f)}; }
""".trimIndent()
