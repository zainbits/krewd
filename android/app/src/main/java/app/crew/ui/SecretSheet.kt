package app.crew.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import app.crew.CrewViewModel
import app.crew.data.SecretAsk

/** A sign-in request under Needs you: what the agent wants to sign in to, and a button to fill it. */
@Composable
fun SecretCard(ask: SecretAsk, engineName: String, shape: RoundedCornerShape, onFill: () -> Unit, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$engineName needs you to sign in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ask.site, style = MaterialTheme.typography.titleMedium)
            if (ask.reason.isNotBlank()) Text(ask.reason, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onFill) { Text("Fill in") }
            }
        }
    }
}

/**
 * The secure sheet for a sign-in request: real username/password fields (so a password manager can fill them, or
 * paste), no screenshots or screen recording, no keyboard learning. Send asks for the fingerprint, then Crew types
 * the values into the page and submits it; the agent only hears whether it worked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretSheet(ask: SecretAsk, viewModel: CrewViewModel, onDismiss: () -> Unit) {
    val values = remember(ask.id) { mutableStateMapOf<String, String>() }
    var shown by remember(ask.id) { mutableStateOf(false) }
    var sending by remember(ask.id) { mutableStateOf(false) }
    var error by remember(ask.id) { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            values.clear()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sign in on ${ask.site}", style = MaterialTheme.typography.titleLarge)
            if (ask.reason.isNotBlank()) Text(ask.reason, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Only this phone sees what you type. Crew types it into the page and submits it; the agent never sees it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ask.fields.forEach { field ->
                OutlinedTextField(
                    value = values[field.name].orEmpty(),
                    onValueChange = { values[field.name] = it },
                    label = { Text(field.label) },
                    singleLine = true,
                    enabled = !sending,
                    visualTransformation = if (field.kind == "password" && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.kind) {
                            "password" -> KeyboardType.Password
                            "otp" -> KeyboardType.NumberPassword
                            "email", "username" -> KeyboardType.Email
                            else -> KeyboardType.Text
                        },
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = if (field.kind == "password") {
                        { TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } }
                    } else null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentType = when (field.kind) {
                            "password" -> ContentType.Password
                            "otp" -> ContentType.SmsOtpCode
                            "email" -> ContentType.EmailAddress
                            "username" -> ContentType.Username
                            else -> ContentType.Username
                        }
                    },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { viewModel.cancelSecret(ask); onDismiss() }, enabled = !sending) { Text("Decline") }
                Button(
                    enabled = !sending && ask.fields.all { !values[it.name].isNullOrEmpty() },
                    onClick = {
                        sending = true
                        error = null
                        viewModel.fillSecret(ask, ask.fields.associate { it.name to values[it.name].orEmpty() }) { problem ->
                            sending = false
                            if (problem == null) onDismiss() else error = problem
                        }
                    },
                ) { Text(if (sending) "Sending…" else "Send") }
            }
        }
    }
}
