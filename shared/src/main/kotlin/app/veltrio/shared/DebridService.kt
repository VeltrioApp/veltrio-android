package app.veltrio.shared

enum class DebridProvider(val label: String, val short: String) {
    RealDebrid("Real-Debrid", "RD"),
    AllDebrid("AllDebrid", "AD"),
    Premiumize("Premiumize", "PM"),
    TorBox("TorBox", "TB"),
}

/** Debrid orchestration, as on macOS: provider sign-in, a cache map per
 *  provider for the ⚡ badges, resolving a torrent through the first
 *  service that can serve it. (Accounts come with the debrid lot.) */
object DebridService {
    private val cached = mutableMapOf<Pair<DebridProvider, String>, Boolean>()

    val anyConnected: Boolean get() = false

    fun cachedOn(infoHash: String): DebridProvider? =
        DebridProvider.entries.firstOrNull { cached[it to infoHash.lowercase()] == true }

    suspend fun resolve(infoHash: String, season: Int?, episode: Int?): String? = null
}
