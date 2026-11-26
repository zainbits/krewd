package app.krewd.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.krewd.LocalKrewdColors
import app.krewd.R
import app.krewd.data.AlertLevel
import app.krewd.data.NeedItem
import app.krewd.data.PushState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The Needs you cards on the Krewd tab (server alerts.py: `krewd needs add`), and the one-time alert setup.

/** "due in 1 h 20 min", "due in 12 min", "overdue 5 min", "due Tue 19:00"; null without a due time. */
fun dueText(due: Instant?, now: Instant): String? {
    due ?: return null
    val left = Duration.between(now, due)
    val minutes = left.toMinutes()
    return when {
        minutes < -60 * 24 -> "overdue since " + whenShort(due)
        minutes < -60 -> "overdue ${-minutes / 60} h ${-minutes % 60} min"
        minutes < 0 -> "overdue ${-minutes} min"
        minutes < 1 -> "due now"
        minutes < 60 -> "due in $minutes min"
        minutes < 24 * 60 -> "due in ${minutes / 60} h" + if (minutes % 60 != 0L) " ${minutes % 60} min" else ""
        else -> "due " + whenShort(due)
    }
}

private fun whenShort(at: Instant): String {
    val local = at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (local.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> local.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return day + " " + local.format(DateTimeFormatter.ofPattern("HH:mm"))
}

/** One Needs you card: what, where from, the countdown, and Done, Snooze, Dismiss and Add to calendar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeedItemCard(
    item: NeedItem,
    now: Instant,
    shape: RoundedCornerShape,
    flash: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onSnooze: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val krewd = LocalKrewdColors.current
    val context = LocalContext.current
    val due = dueText(item.dueAt, now)
    val late = item.dueAt?.isBefore(now) == true
    val soon = item.dueAt?.let { Duration.between(now, it).toMinutes() in 0..60 } == true
    val accent = when {
        item.level == AlertLevel.CRITICAL || late -> colors.error
        item.level == AlertLevel.URGENT || soon -> krewd.needs
        else -> colors.onSurfaceVariant
    }
    val fill by animateColorAsState(if (flash) krewd.needsContainer else colors.surfaceContainer, label = "needs-flash")
    var snoozeMenu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = shape,
        color = fill,
        border = if (item.level == AlertLevel.CRITICAL || item.level == AlertLevel.URGENT) BorderStroke(1.dp, accent.copy(alpha = 0.6f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_schedule_24), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    listOfNotNull(levelWord(item.level), item.source.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val snoozed = item.snoozedUntil?.takeIf { it.isAfter(now) }
                (if (snoozed != null) "snoozed until " + whenShort(snoozed).removePrefix("today ") else due)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = if (snoozed != null) colors.outline else accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { addToCalendar(context, item) }) {
                    Icon(painterResource(R.drawable.ic_add_24), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add to calendar")
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Box {
                    TextButton(onClick = { snoozeMenu = true }) { Text("Snooze") }
                    DropdownMenu(expanded = snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                        snoozeChoices(now, item.dueAt).forEach { (label, until) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { snoozeMenu = false; onSnooze(until) })
                        }
                    }
                }
                Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = krewd.needs, contentColor = krewd.onNeeds)) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun levelWord(level: String): String? = when (level) {
    AlertLevel.CRITICAL -> "Alarm"
    AlertLevel.URGENT -> "Urgent"
    else -> null
}

/** 15 minutes, an hour, 3 hours, this evening (18:00) and tomorrow 09:00; never past a due time an hour away. */
private fun snoozeChoices(now: Instant, due: Instant?): List<Pair<String, Instant>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val evening = today.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
    val tomorrow = today.plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()
    return buildList {
        add("15 minutes" to now.plusSeconds(15 * 60))
        add("1 hour" to now.plusSeconds(3600))
        add("3 hours" to now.plusSeconds(3 * 3600))
        if (evening.isAfter(now.plusSeconds(3600))) add("This evening, 18:00" to evening)
        add("Tomorrow, 09:00" to tomorrow)
    }.filter { (_, until) -> due == null || !until.isAfter(due) || due.isBefore(now) }.ifEmpty { listOf("15 minutes" to now.plusSeconds(15 * 60)) }
}

/**
 * Opens the phone's calendar app with a new event filled in (the card's title, due time, source and link); the owner
 * saves it there or not. Krewd never writes to a calendar itself.
 */
fun addToCalendar(context: Context, item: NeedItem) {
    val start = item.dueAt ?: Instant.now().plusSeconds(3600).let { Instant.ofEpochSecond(it.epochSecond / 3600 * 3600) }
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, item.title)
        .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(item.source.ifBlank { null }, item.link?.takeUnless { it.startsWith("krewd:") }).joinToString("\n"))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli() - 30 * 60_000)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.toEpochMilli())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        android.widget.Toast.makeText(context, "No calendar app to add it to", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** What the phone allows for Krewd's alerts, checked again whenever the app comes back to the front. */
data class AlertPermissions(val notifications: Boolean, val fullScreen: Boolean) {
    val complete: Boolean get() = notifications && fullScreen

    companion object {
        fun of(context: Context): AlertPermissions {
            val manager = context.getSystemService(NotificationManager::class.java)
            return AlertPermissions(
                notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                    manager.areNotificationsEnabled(),
                fullScreen = manager.canUseFullScreenIntent(),
            )
        }
    }
}

@Composable
fun rememberAlertPermissions(): AlertPermissions {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return remember(tick) { AlertPermissions.of(context) }
}

fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

fun openFullScreenSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openNotificationSettings(context) }
}

/**
 * The one-time alert setup, step by step: notifications on, then full-screen alarms (for Critical alerts over the lock
 * screen), then whether push reaches this phone, and test alerts for each level. Do Not Disturb is left to the
 * owner: alarms wait while it is on.
 */
@Composable
fun AlertSetup(permissions: AlertPermissions, push: PushState, online: Boolean, onTest: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SetupStep(
            1, "Notifications", permissions.notifications,
            if (permissions.notifications) "On" else "Off: Krewd cannot tell you anything while it is closed",
            "Turn on",
        ) { openNotificationSettings(context) }
        SetupStep(
            2, "Full-screen alarms", permissions.fullScreen,
            if (permissions.fullScreen) "On: a Critical alert opens over the lock screen" else "Off: a Critical alert only rings as a notification",
            "Allow",
        ) { openFullScreenSettings(context) }
        val pushText = when {
            !push.built -> "This build has no push (${push.provider}): alerts come only while Krewd is open"
            push.active -> "On: ${push.provider}" + (push.project?.let { " ($it)" } ?: "")
            push.registered && push.serverKey == false -> "Bottle has no FCM key yet: alerts come only while Krewd is open"
            push.error != null -> "Not working: ${push.error}"
            else -> "Connecting…"
        }
        SetupStep(3, "Push from Bottle", push.active, pushText, null) {}
        Text(
            "Do Not Disturb is up to you: alarms and urgent alerts stay silent while it is on, and wait in the notifications.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text("Send a test", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AlertLevel.ACTION to "Action", AlertLevel.URGENT to "Urgent", AlertLevel.CRITICAL to "Critical").forEach { (level, label) ->
                OutlinedButton(onClick = { onTest(level) }, enabled = online) { Text(label) }
            }
        }
        Text(
            "Critical alarms ring only for routines you mark \"Alarm if a run fails\" (a routine's page). Agents can ask for at most Urgent.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupStep(n: Int, title: String, done: Boolean, detail: String, action: String?, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val krewd = LocalKrewdColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(50), color = if (done) krewd.ok.copy(alpha = 0.18f) else colors.surfaceContainerHighest, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (done) Icon(painterResource(R.drawable.ic_check_24), contentDescription = "Done", tint = krewd.ok, modifier = Modifier.size(16.dp))
                else Text(n.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (!done && action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

/** On the Krewd tab until the setup is done: one line that opens it. */
@Composable
fun AlertSetupBanner(onOpen: () -> Unit) {
    val krewd = LocalKrewdColors.current
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = krewd.needsContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_notification), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Set up alerts", style = MaterialTheme.typography.titleSmall)
                Text("Allow notifications and full-screen alarms, so nothing that needs you is missed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** At the top of the Krewd tab while a Critical alarm rings (or waits out Do Not Disturb): what it is, and Got it,
 *  the only thing that stops it. Open chat looks at its chat and leaves it ringing. */
@Composable
fun RingingAlarmCard(alarm: app.krewd.data.AlertInfo, quiet: Boolean, onGotIt: () -> Unit, onOpenChat: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.errorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_notification), contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                Text(if (quiet) "Alarm (held by Do Not Disturb)" else "Alarm ringing", style = MaterialTheme.typography.labelLarge, color = colors.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
            Text(alarm.title, style = MaterialTheme.typography.titleMedium, color = colors.onErrorContainer)
            if (alarm.body.isNotBlank()) Text(alarm.body, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer.copy(alpha = 0.85f))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                onOpenChat?.let { TextButton(onClick = it) { Text("Open chat", color = colors.onErrorContainer) } }
                Button(onClick = onGotIt, colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)) {
                    Text("Got it", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
