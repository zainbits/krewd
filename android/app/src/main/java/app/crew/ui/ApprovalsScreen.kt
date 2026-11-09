package app.crew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.CrewViewModel
import app.crew.UiState
import app.crew.data.Approval

@Composable
fun ApprovalsScreen(state: UiState, viewModel: CrewViewModel, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.approvals.isEmpty()) {
            item {
                Text(
                    "Nothing is waiting for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(state.approvals, key = { it.id }) { approval ->
            val task = state.sessions.firstOrNull { it.id == approval.sessionId }
            ApprovalCard(
                approval = approval,
                taskTitle = task?.title,
                onDecide = { viewModel.decide(approval, it) },
                onOpenTask = { viewModel.openSession(approval.sessionId) },
            )
        }
    }
}

/** An agent's permission prompt: what it wants to run, and the agent's own answer choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(approval: Approval, taskTitle: String?, onDecide: (String) -> Unit, onOpenTask: (() -> Unit)? = null) {
    SectionCard(color = if (approval.pending) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            if (approval.pending) "Needs your OK" else approvalOutcome(approval),
            style = MaterialTheme.typography.labelLarge,
            color = if (approval.pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (taskTitle != null) {
            Text(taskTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(approval.title, style = MaterialTheme.typography.titleSmall)
        approval.input?.let { MonoBlock(it, maxLines = 12) }
        if (approval.pending) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                approval.options.forEach { option ->
                    if (option.allows) {
                        Button(onClick = { onDecide(option.id) }) { Text(option.name) }
                    } else {
                        OutlinedButton(onClick = { onDecide(option.id) }) { Text(option.name) }
                    }
                }
            }
        }
        if (onOpenTask != null) {
            TextButton(onClick = onOpenTask) { Text("Open task") }
        }
    }
}

private fun approvalOutcome(approval: Approval): String = when (approval.status) {
    "approved" -> if (approval.decidedBy == "auto") "Approved automatically" else "Approved"
    "denied" -> "Denied"
    "cancelled" -> "Cancelled"
    "expired" -> "Expired"
    else -> approval.status
}
