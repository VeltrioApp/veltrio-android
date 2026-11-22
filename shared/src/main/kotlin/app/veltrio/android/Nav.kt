package app.veltrio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.veltrio.shared.AddonInfo
import app.veltrio.shared.CatalogInfo
import app.veltrio.shared.PlayRequest
import java.util.concurrent.atomic.AtomicLong

/** What a title page is opened with. */
data class DetailRequest(val addon: AddonInfo, val type: String, val id: String, val name: String, val poster: String? = null, val background: String? = null)

/** What the "See all" grid is opened with. */
data class CatalogRequest(val addon: AddonInfo, val catalog: CatalogInfo)

/** App-wide navigation. Destinations carry objects, kept here by a key the
 *  route names, rather than serialized into the route. */
object Nav {
    lateinit var controller: NavController

    /** The section shown under the top bar: 0 Accueil … 3 Sources. */
    var tab by androidx.compose.runtime.mutableIntStateOf(0)

    /** The theme in use, changed live from Settings. */
    var theme by androidx.compose.runtime.mutableStateOf(app.veltrio.shared.AppPrefs.current.theme)

    /** Parental control (Kids Mode), toggled live from Settings. */
    var kidsMode by androidx.compose.runtime.mutableStateOf(app.veltrio.shared.AppPrefs.current.kidsMode)

    /** True while PlayerScreen is mounted (enables auto-PiP on home gesture). */
    var isInPlayer by androidx.compose.runtime.mutableStateOf(false)

    /** True while the Activity is in Android Picture-in-Picture mode. */
    var inPipMode by androidx.compose.runtime.mutableStateOf(false)

    /** Back to the top level, on one of the four sections. */
    fun showTab(index: Int) {
        tab = index
        controller.popBackStack("tabs", inclusive = false)
    }
    private val args = mutableMapOf<String, Any>()
    private val counter = AtomicLong()

    private fun keep(value: Any): String = counter.incrementAndGet().toString().also { args[it] = value }

    @Suppress("UNCHECKED_CAST")
    fun <T> arg(key: String?): T? = key?.let { args[it] as? T }

    fun openTitle(request: DetailRequest) = controller.navigate("detail/${keep(request)}")
    fun openCatalog(request: CatalogRequest) = controller.navigate("catalog/${keep(request)}")
    fun play(request: PlayRequest) = controller.navigate("player/${keep(request)}")
    fun openIptvSeries(playlist: app.veltrio.shared.iptv.PlaylistRef, series: app.veltrio.shared.iptv.ChannelItem) =
        controller.navigate("iptvseries/${keep(playlist to series)}")
    fun openServerSeries(server: app.veltrio.shared.MediaServer, item: app.veltrio.shared.MediaLibraryItem) =
        controller.navigate("serverseries/${keep(server to item)}")

    /** The Collection, on a media server's tab. */
    var libraryServer by androidx.compose.runtime.mutableStateOf<app.veltrio.shared.MediaServerKind?>(null)
    fun showLibrary(kind: app.veltrio.shared.MediaServerKind) {
        libraryServer = kind
        showTab(1)
    }
    fun addonCatalog() = controller.navigate("addoncatalog")
    fun pair() = controller.navigate("pair")
    fun search() = controller.navigate("search")
    fun settings() = controller.navigate("settings")
    fun back() = controller.popBackStack()

    /** Another channel or the next episode from inside the player: Back
     *  still returns to where the player was opened from. */
    fun zap(request: PlayRequest) {
        controller.popBackStack()
        play(request)
    }
}
