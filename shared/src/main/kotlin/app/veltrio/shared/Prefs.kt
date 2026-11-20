package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.serialization.Serializable

enum class AppTheme(val label: String) { System(tr("Système")), Dark(tr("Sombre")), Light(tr("Clair")) }

enum class PlaybackProfile(val label: String, val blurb: String) {
    Balanced(tr("Équilibré"), tr("Réglages sûrs, bons sur tout appareil.")),
    Quality(tr("Qualité maximale"), tr("Meilleure image, plus de calcul ; tone mapping HDR soigné.")),
    Performance(tr("Performances"), tr("Peu de calcul, peu d'énergie ; filtres d'image simples.")),
    Audiophile(tr("Audiophile"), tr("Son en bitstream (DTS, AC3…) vers l'ampli ; tampon audio plus grand.")),
}

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
    val profile: PlaybackProfile = PlaybackProfile.Balanced,
    /** mpv's tone-mapping for HDR on an SDR screen. */
    val toneMapping: String = "auto",
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
            "" to tr("Automatique"), "fre" to tr("Français"), "eng" to tr("Anglais"), "spa" to tr("Espagnol"), "ger" to tr("Allemand"),
            "ita" to tr("Italien"), "por" to tr("Portugais"), "rus" to tr("Russe"), "jpn" to tr("Japonais"), "kor" to tr("Coréen"),
            "chi" to tr("Chinois"), "ara" to tr("Arabe"), "dut" to tr("Néerlandais"), "swe" to tr("Suédois"), "pol" to tr("Polonais"),
            "tur" to tr("Turc"), "hin" to tr("Hindi"),
        )
        val decoders = listOf("mediacodec-copy" to tr("Matériel (sûr)"), "mediacodec" to tr("Matériel (direct)"), "no" to tr("Logiciel"))
        val cacheSizes = listOf(32, 64, 150, 300)
        val toneMappings = listOf(
            "auto" to tr("Automatique"), "bt.2446a" to tr("BT.2446a (équilibré)"), "bt.2390" to tr("BT.2390 (cinéma)"),
            "hable" to tr("Hable"), "reinhard" to tr("Reinhard"), "clip" to tr("Aucun (écrêter)"),
        )

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
