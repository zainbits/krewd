package app.crew.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewStructure
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The website a sign-in sheet is for, as password managers read it: the page's host and scheme.
 * Null when the origin cannot be read, and then the fields carry only their kind.
 */
data class SiteAddress(val host: String, val scheme: String) {
    companion object {
        fun of(origin: String): SiteAddress? = runCatching { Uri.parse(origin) }.getOrNull()
            ?.takeIf { !it.host.isNullOrBlank() }
            ?.let { SiteAddress(it.host!!, it.scheme?.takeIf { s -> s == "http" || s == "https" } ?: "https") }
    }
}

/**
 * A text field that password managers treat as a field of the web page itself. Compose fields cannot say which
 * website they belong to, so this is a plain Android field that reports, through Android's autofill framework:
 * the page's web domain and scheme (Bitwarden, Keepass2Android and most others match saved logins on these),
 * an autofill hint (username, password, email, one-time code) and an HTML `input` description with type, name and
 * autocomplete, the way a browser reports a login form. Keyboard learning and suggestions are off.
 */
@Composable
fun SiteField(
    label: String,
    kind: String,
    name: String,
    site: SiteAddress?,
    enabled: Boolean,
    shown: Boolean,
    onValueChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onValueChange)
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val dimColor = colors.onSurfaceVariant.toArgb()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (focused) colors.primary else colors.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline, RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { context ->
                    SiteEditText(context).apply {
                        setup(kind, name, label, site)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        background = null
                        setPadding(0, 0, 0, 0)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: Editable?) { change(s?.toString().orEmpty()) }
                        })
                        setOnFocusChangeListener { _, has -> focused = has }
                    }
                },
                update = { field ->
                    field.setTextColor(textColor)
                    field.setHintTextColor(dimColor)
                    field.isEnabled = enabled
                    if (kind == "password") {
                        val method = if (shown) null else PasswordTransformationMethod.getInstance()
                        if (field.transformationMethod !== method) {
                            val at = field.selectionEnd
                            field.transformationMethod = method
                            field.setSelection(at.coerceIn(0, field.length()))
                        }
                    }
                },
            )
            trailing?.invoke()
        }
    }
}

@SuppressLint("AppCompatCustomView")
private class SiteEditText(context: Context) : EditText(context) {
    private var site: SiteAddress? = null
    private var htmlType = "text"
    private var htmlName = ""
    private var autocomplete = ""

    fun setup(kind: String, name: String, label: String, site: SiteAddress?) {
        this.site = site
        htmlName = name
        val noLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        when (kind) {
            "password" -> {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                htmlType = "password"; autocomplete = "current-password"
            }
            "email" -> {
                inputType = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS, View.AUTOFILL_HINT_USERNAME)
                htmlType = "email"; autocomplete = "username"
            }
            "username" -> {
                inputType = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setAutofillHints(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS)
                htmlType = "text"; autocomplete = "username"
            }
            "otp" -> {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setAutofillHints(OTP_HINT)
                htmlType = "text"; autocomplete = "one-time-code"
            }
            else -> {
                inputType = text
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        if (kind != "text") importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        imeOptions = noLearning or EditorInfo.IME_ACTION_NEXT
        isSingleLine = true
        contentDescription = label
    }

    override fun onProvideAutofillStructure(structure: ViewStructure, flags: Int) {
        super.onProvideAutofillStructure(structure, flags)
        // Android takes "scheme://host" here and reports the host and scheme separately to the password manager.
        site?.let { structure.setWebDomain("${it.scheme}://${it.host}") }
        val html = structure.newHtmlInfoBuilder("input").addAttribute("type", htmlType)
        if (htmlName.isNotEmpty()) html.addAttribute("name", htmlName).addAttribute("id", htmlName)
        if (autocomplete.isNotEmpty()) html.addAttribute("autocomplete", autocomplete)
        structure.setHtmlInfo(html.build())
    }

    private companion object {
        /** androidx.autofill's HintConstants.AUTOFILL_HINT_SMS_OTP, without the extra library. */
        const val OTP_HINT = "smsOTPCode"
    }
}
