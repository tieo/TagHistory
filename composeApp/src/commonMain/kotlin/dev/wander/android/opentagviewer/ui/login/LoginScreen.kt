package io.github.tieo.taghistory.ui.login

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import io.github.tieo.taghistory.ui.util.AlwaysSpinningIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tieo.taghistory.apple.account.TwoFactorChallenge

/**
 * Three-panel onboarding — LOGIN → CHOOSE_2FA → ENTER_2FA_CODE.
 * Driven by [AppleLoginViewModel]'s `StateFlow<LoginUiState>`.
 */
@Composable
fun LoginScreen(
    viewModel: AppleLoginViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        when (state.page) {
            LoginUiState.Page.LOGIN -> LoginForm(
                state = state,
                onEmailChange = viewModel::setEmail,
                onPasswordChange = viewModel::setPassword,
                onSubmit = viewModel::submitLogin,
            )
            LoginUiState.Page.CHOOSE_2FA -> TwoFactorMethodChooser(
                state = state,
                onChoose = { method ->
                    viewModel.chooseTwoFactorMethod(method)
                    viewModel.requestTwoFactorChallenge()
                },
            )
            LoginUiState.Page.ENTER_2FA_CODE -> TwoFactorCodeEntry(
                state = state,
                onCodeChange = viewModel::setTwoFactorCode,
                onSubmit = viewModel::submitTwoFactorCode,
                onBack = viewModel::reset,
            )
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Hero(icon = Icons.Filled.Lock, title = "Welcome to TagHistory")
        Text(
            "Sign in with your Apple ID to fetch AirTag locations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text("Apple ID email or phone") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("field_email"),
            enabled = !state.isLoggingIn,
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("field_password"),
            enabled = !state.isLoggingIn,
        )
        val loginError = state.loginError
        if (loginError != null) {
            Text(
                loginError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onSubmit,
            enabled = !state.isLoggingIn && state.isEmailValid && state.isPasswordValid,
            modifier = Modifier.fillMaxWidth().testTag("btn_login"),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            if (state.isLoggingIn) {
                AlwaysSpinningIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Log in", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TwoFactorMethodChooser(
    state: LoginUiState,
    onChoose: (TwoFactorChallenge) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Hero(icon = Icons.Filled.Shield, title = "Verify your identity")
        Text(
            "Pick how Apple should send your 6-digit code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        for (method in state.twoFactorMethods) {
            MethodCard(method = method, onClick = { onChoose(method) })
        }
    }
}

@Composable
private fun MethodCard(method: TwoFactorChallenge, onClick: () -> Unit) {
    val icon = when (method) {
        is TwoFactorChallenge.TrustedDevice -> Icons.Filled.Smartphone
        is TwoFactorChallenge.Sms -> Icons.Filled.Sms
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                method.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun TwoFactorCodeEntry(
    state: LoginUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Auto-submit once the code is 6 digits and we're not already submitting.
    LaunchedEffect(state.twoFactorCode, state.isSubmittingTwoFactor) {
        if (state.twoFactorCode.length == 6 && !state.isSubmittingTwoFactor) {
            onSubmit()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Hero(icon = Icons.Filled.Shield, title = "Enter verification code")
            val method = state.chosenTwoFactorMethod
            if (method != null) {
                Text(
                    "Sent via ${method.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))

            OtpSlots(
                code = state.twoFactorCode,
                onChange = { raw ->
                    onCodeChange(raw.filter { it.isDigit() }.take(6))
                },
                enabled = !state.isSubmittingTwoFactor,
                focusRequester = focus,
            )
            OutlinedButton(
                onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    onCodeChange(pasted.filter { it.isDigit() }.take(6))
                },
                enabled = !state.isSubmittingTwoFactor,
                modifier = Modifier.testTag("btn_paste_code"),
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste from clipboard")
            }

            val twoFactorError = state.twoFactorError
            if (twoFactorError != null) {
                Text(
                    twoFactorError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.isSubmittingTwoFactor) {
                AlwaysSpinningIndicator(
                    modifier = Modifier.size(24.dp).testTag("twofactor_submitting"),
                    strokeWidth = 2.dp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.padding(top = 24.dp).testTag("btn_start_over"),
        ) {
            Text("Start over")
        }
    }
}

/**
 * Six-slot OTP input. Invisible `BasicTextField` is the real focus target
 * (so the system keyboard pops + paste handlers work); six `Box`es are
 * the visual slots driven off the TextField's current value.
 */
@Composable
private fun OtpSlots(
    code: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
) {
    Box {
        // Invisible input that captures keystrokes + paste for the whole
        // 6-char code. Zero-size + alpha=0 keeps it interactive but
        // hidden; visible slots below render the characters.
        BasicTextField(
            value = code,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .testTag("field_otp_code")
                .clickable(enabled = enabled) { focusRequester.requestFocus() },
        ) {
            repeat(6) { i ->
                OtpSlot(char = code.getOrNull(i), filled = i < code.length)
            }
        }
    }
}

@Composable
private fun OtpSlot(char: Char?, filled: Boolean) {
    val border = if (filled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 56.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .border(
                width = if (filled) 2.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = char?.toString() ?: "",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
        )
    }
}

@Composable
private fun Hero(icon: ImageVector, title: String) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(30.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}
