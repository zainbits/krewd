package app.krewd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.R
import app.krewd.UiState
import app.krewd.data.SessionInfo

/**
 * What the chat's title opens into: the agent's models as cards that scroll sideways, its effort as a
 * stepped slider, and the way to every other setting. Colours come from [LocalContentColor], so on
 * glass they follow what is behind it.
 */
@Composable
fun ChatQuickPanel(state: UiState, viewModel: KrewdViewModel, session: SessionInfo, onAllSettings: () -> Unit) {
    val online = state.connection is Connection.Online
    val ink = LocalContentColor.current
    val model = session.config.firstOrNull { it.category == "model" }
    val effort = session.config.firstOrNull { it.category == "thought_level" }?.takeIf { it.choices.size in 2..7 }
    // Taps between the controls stay in the panel (they do not fold it).
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(bottom = 10.dp),
    ) {
        if (model != null) {
            PanelLabel("Model")
            val picked = model.choices.indexOfFirst { it.value == model.current }.coerceAtLeast(0)
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (picked - 1).coerceAtLeast(0))
            LazyRow(
                state = list,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(model.choices, key = { _, c -> c.value }) { _, choice ->
                    val selected = choice.value == model.current
                    Surface(
                        onClick = { if (!selected) viewModel.setConfig(session.id, model.id, choice.value) },
                        enabled = online,
                        shape = RoundedCornerShape(18.dp),
                        color = ink.copy(alpha = if (selected) 0.16f else 0.06f),
                        contentColor = ink,
                        border = if (selected) BorderStroke(1.5.dp, ink.copy(alpha = 0.55f)) else null,
                        modifier = Modifier.width(140.dp).heightIn(min = 64.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                choice.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            choice.description?.takeIf { it.isNotBlank() && it != choice.name }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ink.copy(alpha = 0.66f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (effort != null) {
            PanelLabel("Effort")
            Column(Modifier.padding(horizontal = 12.dp)) {
                StepChoice(effort, effort.current, online) { viewModel.setConfig(session.id, effort.id, it) }
            }
        }
        if (model == null && effort == null) {
            Text(
                "This agent has no model or effort to pick.",
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.66f),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        Surface(
            onClick = onAllSettings,
            shape = RoundedCornerShape(16.dp),
            color = ink.copy(alpha = 0.06f),
            contentColor = ink,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_tune_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "All chat settings",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = null, tint = ink.copy(alpha = 0.66f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalContentColor.current.copy(alpha = 0.66f),
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}
