package app.crew.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The phone's keyboard wired straight to the page, the way a terminal app does it: no text box in between.
 * Each key reaches the focused field as it is pressed: letters as text, Backspace, Enter and arrows as keys,
 * and sliding on the space bar moves the page's own cursor. Nothing typed is kept on the phone.
 */
private class KeyCatcher(context: Context) : View(context) {
    var onKey: (String, List<String>) -> Unit = { _, _ -> }
    var onText: (String) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL: the keyboard sends plain keys (no suggestions, no learning: it may be a password).
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        return Connection()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handle(event) || super.onKeyDown(keyCode, event)

    /** Keyboards that still compose words (some do even here): the page gets each change as it happens. */
    private inner class Connection : BaseInputConnection(this, false) {
        private var composing = ""

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replace(composing, text.toString())
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val was = composing
            composing = ""
            if (was.isNotEmpty()) replace(was, text.toString()) else type(text.toString())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { onKey("Backspace", emptyList()) }
            repeat(afterLength) { onKey("Delete", emptyList()) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) handle(event)
            return true
        }

        private fun replace(old: String, new: String) {
            val common = old.commonPrefixWith(new).length
            repeat(old.length - common) { onKey("Backspace", emptyList()) }
            type(new.substring(common))
        }
    }

    private fun type(text: String) {
        if (text.isEmpty()) return
        // Enter is a key press (it submits forms); the rest is typed text.
        text.split('\n').forEachIndexed { i, part ->
            if (i > 0) onKey("Enter", emptyList())
            if (part.isNotEmpty()) onText(part)
        }
    }

    private fun handle(event: KeyEvent): Boolean {
        val mods = buildList {
            if (event.isCtrlPressed) add("ctrl")
            if (event.isAltPressed) add("alt")
            if (event.isShiftPressed) add("shift")
        }
        val named = NAMED[event.keyCode]
        if (named != null) {
            onKey(named, mods)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && mods.isEmpty()) {
            onText(" ")
            return true
        }
        val char = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv())
        if (char == 0) return false
        if ("ctrl" in mods || "alt" in mods) {
            if (Character.isLetterOrDigit(char)) onKey(Character.toString(char).lowercase(), mods - "shift")
            return true
        }
        onText(Character.toString(char))
        return true
    }

    companion object {
        val NAMED = mapOf(
            KeyEvent.KEYCODE_DEL to "Backspace",
            KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
            KeyEvent.KEYCODE_ENTER to "Enter",
            KeyEvent.KEYCODE_NUMPAD_ENTER to "Enter",
            KeyEvent.KEYCODE_TAB to "Tab",
            KeyEvent.KEYCODE_ESCAPE to "Escape",
            KeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft",
            KeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
            KeyEvent.KEYCODE_DPAD_UP to "ArrowUp",
            KeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
            KeyEvent.KEYCODE_MOVE_HOME to "Home",
            KeyEvent.KEYCODE_MOVE_END to "End",
            KeyEvent.KEYCODE_PAGE_UP to "PageUp",
            KeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
        )
    }
}

/**
 * Opens the phone keyboard typing straight into the page. Extra keys (Ctrl, Alt, arrows) come from the
 * keyboard itself; ZnKeyboard sends them as real key presses here. Closing the keyboard ends it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectKeyboard(onKey: (String, List<String>) -> Unit, onText: (String) -> Unit, onDone: () -> Unit) {
    val key by rememberUpdatedState(onKey)
    val text by rememberUpdatedState(onText)
    var catcher by remember { mutableStateOf<KeyCatcher?>(null) }
    AndroidView(
        factory = { context ->
            KeyCatcher(context).also { view ->
                view.onKey = { k, m -> key(k, m) }
                view.onText = { t -> text(t) }
                catcher = view
            }
        },
        modifier = Modifier.size(1.dp),
    )
    DisposableEffect(catcher) {
        val view = catcher
        view?.post {
            view.requestFocus()
            view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0)
        }
        onDispose {
            view?.context?.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    // Closing the keyboard (its own key or the back gesture) ends typing.
    val imeVisible = WindowInsets.isImeVisible
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) seen = true else if (seen) onDone()
    }
    Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
}
