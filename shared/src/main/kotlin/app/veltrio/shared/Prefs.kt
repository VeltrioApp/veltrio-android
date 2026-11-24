package app.veltrio.shared

import app.veltrio.shared.i18n.Lang
import app.veltrio.shared.i18n.tr
import kotlinx.serialization.Serializable

enum class AppTheme(private val labelKey: String) {
    System("Système"), Dark("Sombre"), Light("Clair");
    val label: String get() = tr(labelKey)
}

enum class PlaybackProfile(private val labelKey: String, private val blurbKey: String) {
    Balanced("Équilibré", "Réglages sûrs, bons sur tout appareil."),
    Quality("Qualité maximale", "Meilleure image, plus de calcul ; tone mapping HDR soigné."),
    Performance("Performances", "Peu de calcul, peu d'énergie ; filtres d'image simples."),
    Audiophile("Audiophile", "Son en bitstream (DTS, AC3…) vers l'ampli ; tampon audio plus grand.");
    val label: String get() = tr(labelKey)
    val blurb: String get() = tr(blurbKey)
}

/** App-wide choices (app-prefs.json). */
@Serializable
data class AppPrefs(
    val theme: AppTheme = AppTheme.Dark,
    val onboardingDone: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    /** "" = system default, "fr" = French, "en" = English. */
    val appLanguage: String = "",
    /** Parental control: filters TMDB to family-friendly titles & hides adult IPTV groups. */
    val kidsMode: Boolean = false,
    /** Optional 4-digit PIN required to disable Kids Mode. */
    val kidsPin: String = "",
) {
    companion object {
        private const val FILE = "app-prefs.json"
        var current: AppPrefs = AppPrefs()
            private set

        private val adultGroupKeywords = listOf(
            "adult", "xxx", "18+", "+18", "porn", "erotic", "érotique", "erotique",
            "charme", "nsfw", "x-rated", "for adults", "adults only",
        )

        fun isAdultIptvGroup(group: String): Boolean {
            val g = group.lowercase()
            return adultGroupKeywords.any { it in g }
        }

        val appLanguages: List<Pair<String, String>>
            get() = listOf(
                "" to tr("Automatique"),
                "fr" to "Français",
                "en" to "English",
                "es" to "Español",
                "de" to "Deutsch",
                "it" to "Italiano",
            )

        internal fun load() {
            current = Storage.load(FILE, serializer(), AppPrefs())
            Lang.set(current.appLanguage)
        }

        fun update(change: (AppPrefs) -> AppPrefs) {
            current = change(current)
            Lang.set(current.appLanguage)
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
    /** mpv's hwdec: auto (direct on a TV, copy on a phone), mediacodec-copy
     *  (safe, subtitles drawn), mediacodec (direct: smooth on a weak TV box,
     *  no subtitles) or no. */
    val hwdec: String = "auto",
    /** Read-ahead buffer for network streams, in MiB. */
    val cacheMiB: Int = 64,
    val profile: PlaybackProfile = PlaybackProfile.Balanced,
    /** mpv's tone-mapping for HDR on an SDR screen. */
    val toneMapping: String = "auto",
    /** Subtitle size multiplier (mpv sub-scale). */
    val subtitleScale: Float = 1.0f,
    /** Subtitle text color (mpv sub-color, #AARRGGBB or #RRGGBB). */
    val subtitleColor: String = "#FFFFFFFF",
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
        val languages get() = listOf(
            "" to tr("Automatique"), "fre" to tr("Français"), "eng" to tr("Anglais"), "spa" to tr("Espagnol"), "ger" to tr("Allemand"),
            "ita" to tr("Italien"), "por" to tr("Portugais"), "rus" to tr("Russe"), "jpn" to tr("Japonais"), "kor" to tr("Coréen"),
            "chi" to tr("Chinois"), "ara" to tr("Arabe"), "dut" to tr("Néerlandais"), "swe" to tr("Suédois"), "pol" to tr("Polonais"),
            "tur" to tr("Turc"), "hin" to tr("Hindi"),
        )
        val decoders get() = listOf("auto" to tr("Automatique"), "mediacodec-copy" to tr("Matériel (sûr)"), "mediacodec" to tr("Matériel (direct, sans sous-titres)"), "no" to tr("Logiciel"))
        val cacheSizes = listOf(32, 64, 150, 300)
        val toneMappings get() = listOf(
            "auto" to tr("Automatique"), "bt.2446a" to tr("BT.2446a (équilibré)"), "bt.2390" to tr("BT.2390 (cinéma)"),
            "hable" to tr("Hable"), "reinhard" to tr("Reinhard"), "clip" to tr("Aucun (écrêter)"),
        )
        val subtitleScales get() = listOf(
            0.75f to tr("Petit"),
            1.0f to tr("Normal"),
            1.25f to tr("Grand"),
            1.5f to tr("Très grand"),
        )
        val subtitleColors get() = listOf(
            "#FFFFFFFF" to tr("Blanc"),
            "#FFFFE066" to tr("Jaune"),
            "#FFD0D0D0" to tr("Gris clair"),
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
