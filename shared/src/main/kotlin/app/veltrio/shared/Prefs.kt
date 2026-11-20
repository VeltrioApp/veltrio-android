package app.veltrio.shared

import kotlinx.serialization.Serializable

enum class AppTheme(val label: String) { System("Système"), Dark("Sombre"), Light("Clair") }

/** App-wide choices (app-prefs.json). */
@Serializable
data class AppPrefs(val theme: AppTheme = AppTheme.Dark) {
    companion object {
        private const val FILE = "app-prefs.json"
        var current: AppPrefs = AppPrefs()
            private set

        internal fun load() {
            current = Storage.load(FILE, serializer(), AppPrefs())
        }

        fun update(change: (AppPrefs) -> AppPrefs) {
            current = change(current)
            Storage.save(FILE, serializer(), current)
        }
    }
}

/** Player choices that stick between sessions (player-prefs.json), as on
 *  macOS and Windows. */
@Serializable
data class PlayerPrefs(
    val autoNextEpisode: Boolean = true,
    /** ISO 639-2 codes; empty = no preference (the file's default track). */
    val audioLang: String = "fre",
    val subtitleLang: String = "fre",
    /** mpv's hwdec: mediacodec-copy (safe), mediacodec (faster, no filters) or no. */
    val hwdec: String = "mediacodec-copy",
    /** Read-ahead buffer for network streams, in MiB. */
    val cacheMiB: Int = 64,
) {
    companion object {
        private const val FILE = "player-prefs.json"
        var current: PlayerPrefs = PlayerPrefs()
            private set

        internal fun load() {
            current = Storage.load(FILE, serializer(), PlayerPrefs())
        }

        fun update(change: (PlayerPrefs) -> PlayerPrefs) {
            current = change(current)
            Storage.save(FILE, serializer(), current)
        }

        /** Languages offered in Settings, ISO 639-2 as mpv and the servers report them. */
        val languages = listOf(
            "" to "Automatique", "fre" to "Français", "eng" to "Anglais", "spa" to "Espagnol", "ger" to "Allemand",
            "ita" to "Italien", "por" to "Portugais", "rus" to "Russe", "jpn" to "Japonais", "kor" to "Coréen",
            "chi" to "Chinois", "ara" to "Arabe", "dut" to "Néerlandais", "swe" to "Suédois", "pol" to "Polonais",
            "tur" to "Turc", "hin" to "Hindi",
        )
        val decoders = listOf("mediacodec-copy" to "Matériel (sûr)", "mediacodec" to "Matériel (direct)", "no" to "Logiciel")
        val cacheSizes = listOf(32, 64, 150, 300)

        /** The codes a language goes by: ISO 639-1, -2/B and -2/T. */
        fun synonyms(code: String): Set<String> {
            val c = code.lowercase()
            groups.firstOrNull { c in it }?.let { return it }
            return setOf(c)
        }

        private val groups = listOf(
            setOf("fr", "fre", "fra"), setOf("en", "eng"), setOf("es", "spa"), setOf("de", "ger", "deu"),
            setOf("it", "ita"), setOf("pt", "por"), setOf("ru", "rus"), setOf("ja", "jpn"), setOf("ko", "kor"),
            setOf("zh", "chi", "zho"), setOf("ar", "ara"), setOf("nl", "dut", "nld"), setOf("sv", "swe"),
            setOf("pl", "pol"), setOf("tr", "tur"), setOf("hi", "hin"),
        )
    }
}
