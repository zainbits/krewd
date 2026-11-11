package app.crew.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.R
import app.crew.data.ConfigChoice
import app.crew.data.ConfigOption
import app.crew.data.EngineInfo
import kotlin.math.roundToInt

/** One agent in a picker: who it is, how it looks, what it is doing, and a short note ("2 running"). */
data class CrewMember(val engine: EngineInfo, val look: CrewLook, val state: FaceState, val note: String)

/** Agents as tiles with their faces; the picked one is outlined. */
@Composable
fun CrewPicker(members: List<CrewMember>, selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val scroll = members.size > 3
    Row(
        modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        members.forEach { m ->
            val picked = m.engine.id == selected
            Surface(
                onClick = { onPick(m.engine.id) },
                shape = RoundedCornerShape(22.dp),
                color = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                border = if (picked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = if (scroll) Modifier.width(112.dp) else Modifier.weight(1f),
            ) {
                Box {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp, start = 6.dp, end = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CrewFace(m.look, m.state, 44.dp, description = "${m.engine.name}, ${m.state.label}")
                        Text(m.engine.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            m.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.state == FaceState.NeedsYou) app.crew.LocalCrewColors.current.needs else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (picked) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/** "Default" means the agent asks before acting; say that instead. */
private fun modeLabel(name: String?): String = when {
    name == null -> "Ask first"
    name.equals("default", ignoreCase = true) -> "Ask first"
    else -> name
}

/** Up to three labelled values for the settings line: model, effort and how approvals work. */
fun settingsSummary(options: List<ConfigOption>, selected: (ConfigOption) -> String?, autoApprove: Boolean, legacyMode: String? = null): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    options.firstOrNull { it.category == "model" }?.let { out += "Model" to (it.nameOf(selected(it)) ?: "?") }
    options.firstOrNull { it.category == "thought_level" }?.let { out += "Effort" to (it.nameOf(selected(it)) ?: "?") }
    val mode = options.firstOrNull { it.isMode }
    out += "Approvals" to if (autoApprove) "Auto" else modeLabel(mode?.nameOf(selected(mode)) ?: legacyMode)
    return out
}

/** The settings line: labelled values side by side, one tap opens the settings. */
@Composable
fun SettingsLine(values: List<Pair<String, String>>, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            values.forEachIndexed { i, (label, value) ->
                if (i > 0) Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Change settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * How the agent runs: on its sign-in account or on a gateway profile. Each choice is an engine; [choices] starts
 * with the agent itself (its sign-in account). Shows the picked one's problem (no key, not signed in) under it.
 */
@Composable
fun ProfilePicker(choices: List<EngineInfo>, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { i, engine ->
            SegmentedButton(
                selected = engine.id == selected,
                onClick = { if (engine.id != selected) onPick(engine.id) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                label = { Text(engine.profileName ?: "Sign-in account", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
    val picked = choices.firstOrNull { it.id == selected }
    (picked?.problem ?: picked?.note)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (picked?.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

@Composable
fun SheetLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 4.dp, end = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Every setting the agent advertises over ACP, each with the control that fits it: a list for models,
 * a stepped slider for effort, segmented buttons for a short list of modes. Plus Crew's own auto-approve.
 */
@Composable
fun ColumnScope.AgentOptions(
    options: List<ConfigOption>,
    selected: (ConfigOption) -> String?,
    enabled: Boolean,
    onPick: (ConfigOption, String) -> Unit,
    autoApprove: Boolean,
    onAutoApprove: (Boolean) -> Unit,
) {
    ConfigOption.ordered(options).forEach { option ->
        val toggle = toggleOf(option)
        if (toggle != null) {
            val (on, off) = toggle
            ToggleRow(option.name, option.description, selected(option) == on.value, enabled) { onPick(option, if (it) on.value else off.value) }
            return@forEach
        }
        val label = when (option.category) {
            "model" -> "Model"
            "thought_level" -> "Effort"
            "mode" -> "Approvals"
            else -> option.name
        }
        SheetLabel(label)
        val value = selected(option)
        when {
            option.category == "thought_level" && option.choices.size in 2..7 -> StepChoice(option, value, enabled) { onPick(option, it) }
            option.isMode && option.choices.size <= 4 && option.choices.all { it.name.length <= 14 } -> SegmentChoice(option, value, enabled) { onPick(option, it) }
            else -> ListChoice(option.choices, value, enabled) { onPick(option, it) }
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Row(Modifier.clickable { onAutoApprove(!autoApprove) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-approve", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (autoApprove) "Crew answers the agent's prompts for you" else "You OK each prompt from the Crew tab",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = autoApprove, onCheckedChange = onAutoApprove)
        }
    }
}

private val ON_NAMES = setOf("on", "enabled", "yes", "true")
private val OFF_NAMES = setOf("off", "disabled", "no", "false")

/** A setting with exactly an on and an off choice, as (on, off); null for anything else. */
private fun toggleOf(option: ConfigOption): Pair<ConfigChoice, ConfigChoice>? {
    if (option.choices.size != 2) return null
    val on = option.choices.firstOrNull { it.name.lowercase() in ON_NAMES || it.value.lowercase() in ON_NAMES } ?: return null
    val off = option.choices.firstOrNull { it.name.lowercase() in OFF_NAMES || it.value.lowercase() in OFF_NAMES } ?: return null
    return on to off
}

/** An on/off setting as one row with a switch. */
@Composable
private fun ToggleRow(title: String, description: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Row(Modifier.clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!description.isNullOrBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

/** Choices as a connected list: rounded at the ends, tight in the middle. */
@Composable
fun ListChoice(choices: List<ConfigChoice>, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        choices.forEachIndexed { i, choice ->
            val top = if (i == 0) 20.dp else 6.dp
            val bottom = if (i == choices.lastIndex) 20.dp else 6.dp
            Surface(
                onClick = { if (choice.value != selected) onPick(choice.value) },
                enabled = enabled,
                shape = RoundedCornerShape(top, top, bottom, bottom),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice.value == selected, onClick = null, enabled = enabled, modifier = Modifier.padding(6.dp))
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(choice.name, style = MaterialTheme.typography.titleSmall)
                        if (!choice.description.isNullOrBlank() && choice.description != choice.name) {
                            Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** An ordered scale (effort): a stepped slider with the choice names under it. */
@Composable
private fun StepChoice(option: ConfigOption, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    val n = option.choices.size
    val index = option.choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    var drag by remember(index) { mutableFloatStateOf(index.toFloat()) }
    Column(Modifier.padding(horizontal = 4.dp)) {
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val i = drag.roundToInt().coerceIn(0, n - 1)
                if (i != index) onPick(option.choices[i].value)
            },
            valueRange = 0f..(n - 1).toFloat(),
            steps = (n - 2).coerceAtLeast(0),
            enabled = enabled,
            colors = SliderDefaults.colors(),
        )
        Row(Modifier.fillMaxWidth()) {
            option.choices.forEachIndexed { i, c ->
                Text(
                    c.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (i == drag.roundToInt()) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == drag.roundToInt()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (i) { 0 -> TextAlign.Start; n - 1 -> TextAlign.End; else -> TextAlign.Center },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        option.choices.getOrNull(drag.roundToInt())?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A short list of modes as segmented buttons. */
@Composable
private fun SegmentChoice(option: ConfigOption, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        option.choices.forEachIndexed { i, c ->
            SegmentedButton(
                selected = c.value == selected,
                onClick = { if (c.value != selected) onPick(c.value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, option.choices.size),
                label = { Text(modeLabel(c.name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.widthIn(min = 0.dp),
            )
        }
    }
    option.choices.firstOrNull { it.value == selected }?.description?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}
