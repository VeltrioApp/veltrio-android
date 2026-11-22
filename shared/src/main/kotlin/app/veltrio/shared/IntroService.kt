package app.veltrio.shared

import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Automatic TV episode intro detection via IntroDB (api.introdb.app).
 *  Returns `(startSec, endSec)` when known; cached in memory per episode. */
object IntroService {
    data class Window(val startSec: Double, val endSec: Double)

    private val cache = ConcurrentHashMap<String, Window?>()

    suspend fun fetchIntro(imdbId: String, season: Int, episode: Int): Window? {
        if (!imdbId.startsWith("tt") || season <= 0 || episode <= 0) return null
        val key = "$imdbId|$season|$episode"
        if (cache.containsKey(key)) return cache[key]
        return try {
            val req = Request.Builder()
                .url("https://api.introdb.app/intro?imdb_id=$imdbId&season=$season&episode=$episode")
                .header("Accept", "application/json")
                .build()
            val json = Http.json(req, "IntroDB")
            val start = json.double("start_sec")
            val end = json.double("end_sec")
            val w = if (start != null && end != null && end > start + 2.0) Window(start, end) else null
            cache[key] = w
            w
        } catch (_: Exception) {
            cache[key] = null
            null
        }
    }
}
