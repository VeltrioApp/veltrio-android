package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request

/** One addon in the catalogue. */
data class CatalogAddon(
    val id: String,
    val name: String,
    val slug: String = "",
    val description: String? = null,
    val logo: String? = null,
    val version: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    /** The manifest to install. Community entries get it from their detail
     *  page, on demand. */
    var transportUrl: String? = null,
) {
    /** "CATALOGUES · FLUX · SOUS-TITRES", what it brings. */
    val capabilities get() = resources.mapNotNull {
        when (it) {
            "catalog" -> "CATALOGUES"; "stream" -> "FLUX"; "meta" -> "FICHES"; "subtitles" -> "SOUS-TITRES"; "addon_catalog" -> "ADDONS"
            else -> null
        }
    }.distinct().joinToString(" · ")

    val typesLabel get() = types.joinToString(" · ") { Core.typeLabel(it, plural = true) }
}

/** The addon catalogue, from two places as on macOS: Stremio's official
 *  collection (a JSON list with manifest URLs) and the community site
 *  stremio-addons.net (a Next.js app whose list pages embed manifests in
 *  their React Server Components payload, scraped here). */
object AddonCatalogService {
    const val COMMUNITY_PAGE_COUNT = 70
    private const val UA = "Mozilla/5.0 Veltrio-Android/0.1"
    private var official: List<CatalogAddon>? = null
    private val _community = mutableListOf<CatalogAddon>()
    val community: List<CatalogAddon> get() = synchronized(_community) { _community.toList() }
    var communityPagesLoaded = 0
        private set

    private fun get(url: String) = Request.Builder().url(url).header("User-Agent", UA).build()

    suspend fun official(): List<CatalogAddon> {
        official?.let { return it }
        return Http.json(get("https://api.strem.io/addonscollection.json"), tr("Stremio")).items
            .mapNotNull { e -> e["manifest"]?.let { fromManifest(it, "", e.str("transportUrl")) } }
            .also { official = it }
    }

    /** Fetches the next community pages (10 addons each). */
    suspend fun loadMoreCommunity(pages: Int = 3) {
        val start = communityPagesLoaded + 1
        for (page in start until start + pages) {
            if (page > COMMUNITY_PAGE_COUNT) break
            val html = Http.text(get(if (page == 1) "https://stremio-addons.net/addons" else "https://stremio-addons.net/addons?page=$page"), "stremio-addons.net")
            val fresh = synchronized(_community) {
                val known = _community.map { it.id }.toMutableSet()
                parseCommunityPage(html).filter { known.add(it.id) }.also { _community += it }
            }
            communityPagesLoaded = page
            if (fresh.isEmpty()) break
        }
    }

    /** A community addon's manifest URL: the first mirror its detail page lists. */
    suspend fun resolveTransport(addon: CatalogAddon): String? {
        addon.transportUrl?.let { return it }
        if (addon.slug.isEmpty()) return null
        val html = Http.text(get("https://stremio-addons.net/addons/${addon.slug}"), "stremio-addons.net")
        return Regex("""https://[A-Za-z0-9._-]+[A-Za-z0-9._/%-]*manifest\.json""").find(html)?.value.also { addon.transportUrl = it }
    }

    // -------------------- parsing --------------------

    private fun fromManifest(m: JsonElement, slug: String, transport: String?): CatalogAddon? {
        if (m !is JsonObject) return null
        val id = m.str("id") ?: return null
        val name = m.str("name") ?: return null
        // `resources` mixes plain names and {name, types…} objects.
        val resources = m.array("resources").mapNotNull { (it as? JsonPrimitive)?.content ?: it.str("name") }
        return CatalogAddon(
            id, name, slug, m.str("description"), m.str("logo"), m.str("version"),
            m.array("types").mapNotNull { it.string }, resources, transport,
        )
    }

    /** Each addon in a list page is a `"href":"/addons/slug"` followed by its
     *  `"manifest":{…}`, inside the payload strings the page pushes with
     *  `self.__next_f.push([1,"…"])`. */
    internal fun parseCommunityPage(html: String): List<CatalogAddon> {
        val result = mutableListOf<CatalogAddon>()
        val hrefKey = "\"href\":\"/addons/"
        val manifestKey = "\"manifest\":"
        for (body in payloadBodies(html)) {
            var from = 0
            while (true) {
                from = body.indexOf(hrefKey, from).takeIf { it >= 0 } ?: break
                val slugStart = from + hrefKey.length
                var slugEnd = slugStart
                while (slugEnd < body.length && (body[slugEnd].isLetterOrDigit() || body[slugEnd] in ".-_")) slugEnd++
                val slug = body.substring(slugStart, slugEnd)
                val key = body.indexOf(manifestKey, slugEnd)
                from = slugEnd
                if (slug.isEmpty() || key < 0) continue
                val objStart = key + manifestKey.length
                val objEnd = balancedObjectEnd(body, objStart).takeIf { it >= 0 } ?: continue
                // Not a manifest object after all: skipped.
                runCatching { fromManifest(Storage.json.parseToJsonElement(body.substring(objStart, objEnd)), slug, null) }.getOrNull()?.let { result += it }
                from = objEnd
            }
        }
        return result
    }

    /** Unescaped JS string literals of the page's payload pushes, kept apart
     *  so a quote in one can't throw off the next. */
    private fun payloadBodies(html: String): List<String> {
        val marker = "self.__next_f.push([1,\""
        val bodies = mutableListOf<String>()
        var i = 0
        while (true) {
            i = html.indexOf(marker, i).takeIf { it >= 0 } ?: break
            i += marker.length
            val sb = StringBuilder()
            while (i < html.length && html[i] != '"') {
                if (html[i] == '\\' && i + 1 < html.length) {
                    val esc = html[i + 1]
                    if (esc == 'u' && i + 5 < html.length) {
                        html.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()); i += 6; continue }
                    }
                    sb.append(when (esc) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> esc })
                    i += 2
                    continue
                }
                sb.append(html[i++])
            }
            bodies += sb.toString()
        }
        return bodies
    }

    /** Index just past the `{…}` object starting at [start], or -1. */
    private fun balancedObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
            } else if (ch == '"') inString = true
            else if (ch == '{') depth++
            else if (ch == '}' && --depth == 0) return i + 1
        }
        return -1
    }
}
