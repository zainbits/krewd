package app.krewd.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.KrewdViewModel
import app.krewd.R
import app.krewd.data.BrowserLocationState
import app.krewd.data.BrowserPlace
import app.krewd.data.KrewdApi
import app.krewd.data.applyBrowserLocation
import app.krewd.data.browserLocation
import app.krewd.data.deleteBrowserLocation
import app.krewd.data.parseCoordinates
import app.krewd.data.reapplyBrowserLocation
import app.krewd.data.renameFavoriteLocation
import app.krewd.data.saveFavoriteLocation
import kotlin.math.roundToInt

/** A location found or typed in, not applied yet. */
private data class Draft(val lat: Double, val lon: Double, val accuracy: Double, val source: String)

/**
 * The agents' Brave location, set by hand: pages there see the location applied here, and it changes only when
 * the owner taps Apply. "Use this phone's location" reads the phone's GPS once, then waits for Apply too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(viewModel: KrewdViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<BrowserLocationState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var locating by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Draft?>(null) } // save as favorite
    var renaming by remember { mutableStateOf<BrowserPlace?>(null) }

    fun run(block: (KrewdApi) -> BrowserLocationState, after: () -> Unit = {}) {
        busy = true
        viewModel.request(block) { result, problem ->
            busy = false
            if (result != null) {
                state = result
                error = null
                after()
            } else error = problem
        }
    }
    fun apply(lat: Double, lon: Double, accuracy: Double, source: String, name: String? = null) =
        run({ it.applyBrowserLocation(lat, lon, accuracy, source, name) }) { draft = null; typing = false }

    LaunchedEffect(Unit) { run({ it.browserLocation() }) }

    fun locate() {
        locating = true
        error = null
        readPhoneLocation(context) { location, problem ->
            locating = false
            if (location != null) {
                draft = Draft(location.latitude, location.longitude, location.accuracy.toDouble().coerceAtLeast(1.0), "phone")
            } else error = problem
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else error = "Krewd needs the location permission to read this phone's location"
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Browser location", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (busy || locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                "Websites in the agents' Brave see this location. It changes only when you tap Apply.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            val s = state
            SectionCard {
                Text("Current", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val current = s?.current
                if (current == null) {
                    Text(if (s == null) "Loading…" else "None set. Pages that ask for the location wait until you apply one.")
                } else {
                    PlaceText(current)
                    Text(
                        when (s.browser) {
                            "applied" -> "Applied in the agents' Brave"
                            "waiting" -> s.detail ?: "Brave is not running; it gets this location when it starts"
                            "error" -> "Not applied: ${s.detail ?: "unknown problem"}. Try Reapply."
                            else -> "Not checked yet. Reapply sends it to Brave again."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.browser == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { run({ it.reapplyBrowserLocation() }) }, enabled = !busy) { Text("Reapply") }
                        if (s.favorites.none { it.lat == current.lat && it.lon == current.lon }) {
                            TextButton(onClick = { naming = Draft(current.lat, current.lon, current.accuracy, "manual") }) { Text("Save as favorite") }
                        }
                    }
                }
            }

            draft?.let { d ->
                SectionCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(if (d.source == "phone") "This phone's location" else "New location", style = MaterialTheme.typography.labelLarge)
                    Text(String.format(java.util.Locale.US, "%.5f, %.5f", d.lat, d.lon), style = MaterialTheme.typography.titleMedium)
                    Text("Accurate to about ${d.accuracy.roundToInt()} m. Not applied yet.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { apply(d.lat, d.lon, d.accuracy, d.source) }, enabled = !busy) { Text("Apply") }
                        TextButton(onClick = { naming = d }) { Text("Save as favorite") }
                        TextButton(onClick = { draft = null }) { Text("Discard") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) locate()
                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    enabled = !locating,
                    modifier = Modifier.weight(1f),
                ) { Text(if (locating) "Finding…" else "Use this phone's location") }
                OutlinedButton(onClick = { typing = !typing }) { Text("Type it") }
            }
            if (typing) CoordinatesForm(onReady = { lat, lon, acc -> draft = Draft(lat, lon, acc, "manual"); typing = false })

            if (s != null && s.favorites.isNotEmpty()) {
                Text("Favorites", style = MaterialTheme.typography.titleSmall)
                s.favorites.forEach { place ->
                    PlaceRow(place, busy,
                        onApply = { apply(place.lat, place.lon, place.accuracy, "favorite", place.name) },
                        menu = listOf(
                            "Rename" to { renaming = place },
                            "Delete" to { place.id?.let { id -> run({ it.deleteBrowserLocation(id) }) } },
                        ))
                }
            }
            if (s != null && s.recent.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                s.recent.forEach { place ->
                    PlaceRow(place, busy,
                        onApply = { apply(place.lat, place.lon, place.accuracy, "recent", place.name) },
                        menu = listOf(
                            "Save as favorite" to { naming = Draft(place.lat, place.lon, place.accuracy, "manual") },
                            "Remove" to { place.id?.let { id -> run({ it.deleteBrowserLocation(id) }) } },
                        ))
                }
            }
            Text(
                "The location only changes what pages are told. Brave still goes online from this server's own connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    naming?.let { d ->
        NameDialog("Save as favorite", "", onDismiss = { naming = null }) { name ->
            naming = null
            run({ it.saveFavoriteLocation(name, d.lat, d.lon, d.accuracy) })
        }
    }
    renaming?.let { place ->
        NameDialog("Rename favorite", place.name.orEmpty(), onDismiss = { renaming = null }) { name ->
            renaming = null
            place.id?.let { id -> run({ it.renameFavoriteLocation(id, name) }) }
        }
    }
}

@Composable
private fun PlaceText(place: BrowserPlace) {
    Text(place.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        listOfNotNull(place.coords.takeIf { place.name != null }, "±${place.accuracy.roundToInt()} m", place.sourceLabel,
            place.at?.let { shortAge(it) }).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlaceRow(place: BrowserPlace, busy: Boolean, onApply: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { PlaceText(place) }
        TextButton(onClick = onApply, enabled = !busy) { Text("Apply") }
        Box {
            IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_24), contentDescription = "More") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun CoordinatesForm(onReady: (Double, Double, Double) -> Unit) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var accuracy by remember { mutableStateOf("50") }
    val parsed = parseCoordinates(lat, lon)
    val acc = accuracy.toDoubleOrNull()?.takeIf { it in 1.0..100_000.0 }
    SectionCard {
        OutlinedTextField(
            value = lat, onValueChange = { lat = it },
            label = { Text("Latitude, or paste \"lat, lon\"") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
        )
        if (!lat.contains(',')) OutlinedTextField(
            value = lon, onValueChange = { lon = it },
            label = { Text("Longitude") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            value = accuracy, onValueChange = { accuracy = it },
            label = { Text("Accuracy (metres)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(
            when {
                lat.isBlank() -> "Tip: in a maps app, long-press a place and copy its coordinates."
                parsed == null -> "Latitude is -90 to 90 and longitude -180 to 180, in decimal degrees."
                acc == null -> "Accuracy is 1 to 100000 metres."
                else -> "Next: check it, then Apply."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { parsed?.let { (a, b) -> onReady(a, b, acc ?: 50.0) } }, enabled = parsed != null && acc != null) { Text("Use these") }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 80) name = it },
                label = { Text("Name, like Office or Home") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One reading of this phone's location (no tracking): the fused provider where there is one, else GPS, else network. */
@SuppressLint("MissingPermission")
private fun readPhoneLocation(context: Context, done: (Location?, String?) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java)
    if (manager == null || !manager.isLocationEnabled) return done(null, "Location is off on this phone. Turn it on, then try again.")
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) }
        ?: return done(null, "No location source is on on this phone")
    val request = LocationRequest.Builder(0L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setDurationMillis(30_000).build()
    runCatching {
        manager.getCurrentLocation(provider, request, CancellationSignal(), context.mainExecutor) { location ->
            done(location, if (location == null) "Could not get a fix in 30 s. Try again near a window or outdoors." else null)
        }
    }.onFailure { done(null, it.message ?: "Could not read the location") }
}
