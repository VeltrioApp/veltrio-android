package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Which file of a torrent to play: the one named after the episode when a
 *  season and episode are given, else the biggest video — so a
 *  "Sample.mkv" or a pack's first episode isn't picked. */
internal object DebridFilePicker {
    private val videoExtensions = setOf("mkv", "mp4", "avi", "mov", "ts", "m4v", "webm")

    fun <T> pick(files: List<T>, path: (T) -> String, size: (T) -> Long, season: Int?, episode: Int?): T? {
        val videos = files.filter { path(it).substringAfterLast('.', "").lowercase() in videoExtensions }
        if (season != null && episode != null) {
            val patterns = listOf("S%02dE%02d".format(season, episode), "${season}x%02d".format(episode), "S${season}E$episode", " ${season}x$episode")
            videos.firstOrNull { f -> patterns.any { path(f).uppercase().contains(it) } }?.let { return it }
        }
        return videos.maxByOrNull(size)
    }
}

data class RdDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val interval: Int, val expiresIn: Int)

/** Real-Debrid: OAuth device flow with the public open-source client id,
 *  then magnet → file → unrestricted HTTPS link. */
internal object RealDebridClient {
    private const val API = "https://api.real-debrid.com/rest/1.0/"
    private const val OAUTH = "https://api.real-debrid.com/oauth/v2/"
    // The public client id open-source Stremio clients use; no secret.
    private const val CLIENT_ID = "X245A4XAIBGVM"

    suspend fun startDeviceAuth(): RdDeviceCode {
        val json = Http.json(Request.Builder().url("${OAUTH}device/code?client_id=$CLIENT_ID&new_credentials=yes").build(), "Real-Debrid")
        return RdDeviceCode(
            json.str("device_code")!!, json.str("user_code")!!,
            json.str("verification_url") ?: "https://real-debrid.com/device",
            maxOf(5, json.long("interval").toInt()), json.long("expires_in").toInt(),
        )
    }

    /** The access token once the user has entered the code, null while waiting. */
    suspend fun pollDeviceAuth(deviceCode: String): String? {
        val creds = try {
            Http.json(Request.Builder().url("${OAUTH}device/credentials?client_id=$CLIENT_ID&code=${enc(deviceCode)}").build(), "Real-Debrid")
        } catch (_: ServiceException) {
            return null // 403 until the code is entered
        }
        val id = creds.str("client_id") ?: return null
        val secret = creds.str("client_secret") ?: return null
        val token = Http.json(
            Request.Builder().url("${OAUTH}token").post(
                FormBody.Builder().add("client_id", id).add("client_secret", secret).add("code", deviceCode)
                    .add("grant_type", "http://oauth.net/grant_type/device/1.0").build(),
            ).build(),
            tr("Real-Debrid"),
        )
        return token.str("access_token") ?: throw ServiceException(tr("Real-Debrid a refusé l'autorisation."))
    }

    suspend fun user(token: String): Pair<String, Boolean> {
        val json = get("user", token)
        return (json.str("username") ?: "") to (json.str("type") == "premium")
    }

    /** Magnet → file → link. Real-Debrid no longer says what it has cached,
     *  so this adds the magnet and waits a few seconds for it to be
     *  "downloaded"; one it would have to fetch first is deleted again and
     *  null comes back (another service or the local engine takes over). */
    suspend fun resolve(infoHash: String, season: Int?, episode: Int?, token: String): String? {
        val added = post("torrents/addMagnet", token, mapOf("magnet" to "magnet:?xt=urn:btih:$infoHash"))
        val id = added.str("id") ?: throw ServiceException(tr("Real-Debrid n'a pas accepté le magnet."))
        try {
            var info = waitFor(id, token) { it.array("files").isNotEmpty() || it.str("status") in failed }
            val files = info.array("files")
            if (files.isEmpty()) {
                delete(id, token); return null
            }
            val pick = DebridFilePicker.pick(files, { it.str("path") ?: "" }, { it.long("bytes") }, season, episode)
                ?: run { delete(id, token); throw ServiceException(tr(tr("Aucune vidéo dans ce torrent."))) }
            if (info.str("status") == "waiting_files_selection") post("torrents/selectFiles/$id", token, mapOf("files" to pick.long("id").toString()))
            info = waitFor(id, token) { it.str("status") == "downloaded" || it.str("status") in failed }
            val link = info.array("links").firstOrNull()?.string
            if (info.str("status") != "downloaded" || link == null) {
                delete(id, token); return null
            }
            return post("unrestrict/link", token, mapOf("link" to link)).str("download")
        } catch (e: Exception) {
            delete(id, token)
            throw e
        }
    }

    private val failed = setOf("magnet_error", "error", "dead")

    private suspend fun waitFor(id: String, token: String, done: (JsonElement) -> Boolean): JsonElement {
        var info = get("torrents/info/$id", token)
        repeat(12) {
            if (done(info)) return info
            delay(700)
            info = get("torrents/info/$id", token)
        }
        return info
    }

    private suspend fun delete(id: String, token: String) {
        runCatching { Http.json(Request.Builder().url("${API}torrents/delete/$id").delete().header("Authorization", "Bearer $token").build(), "Real-Debrid") }
    }

    private suspend fun get(path: String, token: String) =
        Http.json(Request.Builder().url(API + path).header("Authorization", "Bearer $token").build(), "Real-Debrid")

    private suspend fun post(path: String, token: String, form: Map<String, String>) =
        Http.json(
            Request.Builder().url(API + path).header("Authorization", "Bearer $token")
                .post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()).build(),
            tr("Real-Debrid"),
        )
}

/** TorBox, with the API key from the account page. */
internal object TorBoxClient {
    private const val API = "https://api.torbox.app/v1/api/"

    suspend fun user(key: String): Pair<String?, Boolean> {
        val data = call("user/me", key)
        return data.str("email") to (data.long("plan") > 0)
    }

    suspend fun checkCached(hashes: List<String>, key: String): Map<String, Boolean> = buildMap {
        hashes.chunked(50).forEach { chunk ->
            val data = call("torrents/checkcached?format=object&hash=${chunk.joinToString(",")}", key)
            val found = (data as? JsonObject)?.keys?.map { it.lowercase() }?.toSet().orEmpty()
            chunk.forEach { put(it.lowercase(), it.lowercase() in found) }
        }
    }

    suspend fun resolve(infoHash: String, season: Int?, episode: Int?, key: String): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("magnet", "magnet:?xt=urn:btih:$infoHash").build()
        val torrentId = call("torrents/createtorrent", key, Request.Builder().post(form)).long("torrent_id")
        val torrent = call("torrents/mylist?id=$torrentId&bypass_cache=true", key)
        if (torrent.bool("download_present") != true) throw ServiceException(tr("Ce torrent n'est pas en cache sur TorBox."))
        val pick = DebridFilePicker.pick(torrent.array("files"), { it.str("name") ?: "" }, { it.long("size") }, season, episode)
            ?: throw ServiceException(tr(tr("Aucune vidéo dans ce torrent.")))
        return call("torrents/requestdl?token=${enc(key)}&torrent_id=$torrentId&file_id=${pick.long("id")}", key).string
            ?: throw ServiceException(tr("TorBox n'a pas renvoyé de lien."))
    }

    /** TorBox wraps answers in {success, detail, data}. */
    private suspend fun call(path: String, key: String, builder: Request.Builder = Request.Builder()): JsonElement? {
        val json = Http.json(builder.url(API + path).header("Authorization", "Bearer $key").build(), "TorBox")
        if (json.bool("success") == true && json["data"] != null) return json["data"]
        throw ServiceException(json.str("detail") ?: tr("TorBox a refusé la requête."))
    }
}

/** Premiumize, with the API key from the account page. */
internal object PremiumizeClient {
    private const val API = "https://www.premiumize.me/api/"

    suspend fun account(key: String): Boolean =
        call("account/info", key).long("premium_until") > System.currentTimeMillis() / 1000

    suspend fun checkCached(hashes: List<String>, key: String): Map<String, Boolean> = buildMap {
        hashes.chunked(50).forEach { chunk ->
            val answers = call("cache/check?" + chunk.joinToString("&") { "items[]=$it" }, key).array("response")
            chunk.zip(answers).forEach { (h, a) -> put(h.lowercase(), (a as? JsonPrimitive)?.content == "true") }
        }
    }

    /** transfer/directdl answers a cached torrent with its files' links. */
    suspend fun resolve(infoHash: String, season: Int?, episode: Int?, key: String): String {
        val json = call("transfer/directdl", key, FormBody.Builder().add("src", "magnet:?xt=urn:btih:$infoHash").build())
        val pick = DebridFilePicker.pick(json.array("content"), { it.str("path") ?: "" }, { it.long("size") }, season, episode)
        return pick?.str("link") ?: throw ServiceException(tr("Aucune vidéo dans ce torrent."))
    }

    private suspend fun call(path: String, key: String, form: FormBody? = null): JsonElement {
        val url = "$API$path${if ('?' in path) '&' else '?'}apikey=${enc(key)}"
        val json = Http.json(Request.Builder().url(url).apply { form?.let { post(it) } }.build(), "Premiumize")
        json.str("status")?.let { if (it != "success") throw ServiceException(json.str("message") ?: tr("Premiumize a refusé la requête.")) }
        return json
    }
}
