package app.krewd.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Where the agents' Brave says it is. It changes only when the owner applies a location in the app
 * (see the server's locations.py): typed in, read once from this phone's GPS, a favorite or a recent one.
 */
data class BrowserPlace(
    val id: String?,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val source: String,
    val at: String?,
) {
    val coords: String get() = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    val title: String get() = name ?: coords
    val sourceLabel: String
        get() = when (source) {
            "phone" -> "from this phone"
            "favorite" -> "favorite"
            "config" -> "from Krewd's config"
            else -> "typed in"
        }

    companion object {
        fun from(o: JSONObject) = BrowserPlace(
            id = o.optString("id").takeIf { it.isNotBlank() && it != "null" },
            name = o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() },
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            accuracy = o.optDouble("accuracy", 50.0),
            source = o.optString("source", "manual"),
            at = (o.optString("applied_at").takeIf { !o.isNull("applied_at") && it.isNotBlank() }
                ?: o.optString("created_at").takeIf { !o.isNull("created_at") && it.isNotBlank() }),
        )
    }
}

/** [browser]: applied (Brave holds it), waiting (Brave is not running), error, or unknown. */
data class BrowserLocationState(
    val current: BrowserPlace?,
    val browser: String,
    val detail: String?,
    val recent: List<BrowserPlace>,
    val favorites: List<BrowserPlace>,
) {
    companion object {
        fun from(o: JSONObject) = BrowserLocationState(
            current = o.optJSONObject("current")?.let(BrowserPlace::from),
            browser = o.optString("browser", "unknown"),
            detail = o.optString("detail").takeIf { !o.isNull("detail") && it.isNotBlank() },
            recent = o.optJSONArray("recent").places(),
            favorites = o.optJSONArray("favorites").places(),
        )

        private fun JSONArray?.places(): List<BrowserPlace> =
            if (this == null) emptyList() else (0 until length()).map { BrowserPlace.from(getJSONObject(it)) }
    }
}

private fun place(lat: Double, lon: Double, accuracy: Double) =
    JSONObject().put("lat", lat).put("lon", lon).put("accuracy", accuracy)

fun KrewdApi.browserLocation(): BrowserLocationState =
    BrowserLocationState.from(JSONObject(rawCall("GET", "/api/browser/location")))

/** [source]: phone, manual, favorite or recent; [name]: the favorite it comes from, shown in recents. */
fun KrewdApi.applyBrowserLocation(lat: Double, lon: Double, accuracy: Double, source: String, name: String? = null): BrowserLocationState =
    BrowserLocationState.from(JSONObject(rawCall("POST", "/api/browser/location",
        place(lat, lon, accuracy).put("source", source).apply { if (name != null) put("name", name) })))

fun KrewdApi.reapplyBrowserLocation(): BrowserLocationState =
    BrowserLocationState.from(JSONObject(rawCall("POST", "/api/browser/location/reapply")))

fun KrewdApi.saveFavoriteLocation(name: String, lat: Double, lon: Double, accuracy: Double): BrowserLocationState =
    BrowserLocationState.from(JSONObject(rawCall("POST", "/api/browser/location/favorites", place(lat, lon, accuracy).put("name", name))))

fun KrewdApi.renameFavoriteLocation(id: String, name: String): BrowserLocationState =
    BrowserLocationState.from(JSONObject(rawCall("PATCH", "/api/browser/location/favorites/$id", JSONObject().put("name", name))))

fun KrewdApi.deleteBrowserLocation(id: String): BrowserLocationState =
    BrowserLocationState.from(JSONObject(rawCall("DELETE", "/api/browser/location/$id")))

/**
 * Reads "19.17266, 73.11192" (as maps apps copy it), or a latitude and a longitude typed apart.
 * Returns null unless both are in range.
 */
fun parseCoordinates(latText: String, lonText: String = ""): Pair<Double, Double>? {
    val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll("$latText $lonText").map { it.value.toDouble() }.toList()
    if (numbers.size != 2) return null
    val (lat, lon) = numbers
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}
