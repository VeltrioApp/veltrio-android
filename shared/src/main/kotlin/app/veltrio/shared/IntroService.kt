package app.veltrio.shared

import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Automatic TV episode recap / intro / outro detection via IntroDB (api.introdb.app).
 *  Returns cached [Segments] per episode. */
object IntroService {
    data class Window(val startSec: Double, val endSec: Double)
    data class Segments(val recap: Window?, val intro: Window?, val outro: Window?) {
        val isEmpty: Boolean get() = recap == null && intro == null && outro == null
    }

    private val EMPTY = Segments(null, null, null)
    private val cache = ConcurrentHashMap<String, Segments>()

    private fun parseWindow(el: kotlinx.serialization.json.JsonElement?): Window? {
        if (el == null) return null
        val s = el.double("start_sec") ?: return null
        val e = el.double("end_sec") ?: return null
        return if (e > s + 2.0) Window(s, e) else null
    }

    suspend fun fetchSegments(imdbId: String, season: Int, episode: Int): Segments? {
        if (!imdbId.startsWith("tt") || season <= 0 || episode <= 0) return null
        val key = "$imdbId|$season|$episode"
        cache[key]?.let { return if (it.isEmpty) null else it }
        val segs = try {
            val req = Request.Builder()
                .url("https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$episode")
                .header("Accept", "application/json")
                .build()
            val json = Http.json(req, "IntroDB")
            val r = parseWindow(json["recap"])
            val i = parseWindow(json["intro"])
            val o = parseWindow(json["outro"])
            if (r != null || i != null || o != null) Segments(r, i, o) else null
        } catch (_: Exception) {
            null
        } ?: try {
            val req = Request.Builder()
                .url("https://api.introdb.app/intro?imdb_id=$imdbId&season=$season&episode=$episode")
                .header("Accept", "application/json")
                .build()
            val json = Http.json(req, "IntroDB")
            parseWindow(json)?.let { Segments(null, it, null) }
        } catch (_: Exception) {
            null
        }
        cache[key] = segs ?: EMPTY
        return segs
    }
}
