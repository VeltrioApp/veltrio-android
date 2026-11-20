package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A service refused or failed a request; the message is meant for the user. */
class ServiceException(message: String) : Exception(message)

/** The app's own HTTP (debrid, accounts, media servers, IPTV, Chromecast
 *  helpers): one client, JSON answers read loosely. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", "Veltrio-Android/0.1").build() else request)
        }
        .build()

    /** Sends and parses; a non-2xx answer throws with the service's own
     *  error message when it gives one. */
    suspend fun json(request: Request, service: String): JsonElement = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ServiceException(tr("%s injoignable : %s", service, e.message))
        }
        response.use {
            val text = it.body.string()
            val json = if (text.isEmpty()) JsonNull else runCatching { Storage.json.parseToJsonElement(text) }.getOrDefault(JsonNull)
            if (!it.isSuccessful) {
                val detail = json.str("error") ?: json.str("detail") ?: json.str("message")
                throw ServiceException(tr("%s a répondu %s%s", service, it.code, detail?.let { d -> " : $d" } ?: ""))
            }
            json
        }
    }

    suspend fun text(request: Request, service: String): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ServiceException(tr("%s injoignable : %s", service, e.message))
        }
        response.use {
            if (!it.isSuccessful) throw ServiceException(tr("%s a répondu %s", service, it.code))
            it.body.string()
        }
    }
}

// Loose JSON access: absent, null or the wrong type all read as null.
operator fun JsonElement?.get(name: String): JsonElement? = (this as? JsonObject)?.get(name)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
fun JsonElement?.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement?.long(name: String): Long = (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0
fun JsonElement?.double(name: String): Double? = (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
fun JsonElement?.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
fun JsonElement?.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()
val JsonElement?.items: List<JsonElement> get() = (this as? JsonArray).orEmpty()
val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
