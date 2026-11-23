package app.krewd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.krewd.KrewdViewModel
import app.krewd.UiState
import app.krewd.data.SessionInfo
import io.github.zainbits.cohesion.glassPullExempt
import io.github.zainbits.vitreous.LocalGlassPull

/**
 * What the chat's title opens into: every chat setting ([ChatSettingsContent]: agent, model cards, the effort
 * slider, approvals, actions), scrolling inside the title's glass. The caller caps its height so it ends above
 * the go-to-latest button. The panel keeps its own touches (scrolling, the cards, the slider): the glass does not
 * bounce under them, and a tap on it does not fold it (only the title row does).
 */
@Composable
fun ChatQuickPanel(
    state: UiState,
    viewModel: KrewdViewModel,
    session: SessionInfo,
    onClose: () -> Unit,
    onSideSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pull = LocalGlassPull.current
    CompositionLocalProvider(LocalOnGlass provides LocalKrewdGlass.current.on) {
        Column(
            modifier
                .fillMaxWidth()
                .glassPullExempt(pull)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        ) {
            ChatSettingsContent(state, viewModel, session, onClose = onClose, onSideSession = onSideSession)
        }
    }
}
