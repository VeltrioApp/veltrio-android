package app.veltrio.shared

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Updates from the repository's GitHub releases, as on Windows (there
 *  through Velopack): the newest release, betas included, its signed APK
 *  downloaded and handed to Android's installer, which asks the user. A
 *  development build is signed differently and can't be updated so: it
 *  says so. */
object Updater {
    enum class Step { Idle, Checking, UpToDate, Downloading, Ready, Failed, Development }

    data class State(val step: Step = Step.Idle, val newVersion: String? = null, val progress: Float = 0f, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var apk: File? = null
    private var didLaunchCheck = false

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun repository(context: Context) = if (context.packageName.endsWith(".tv")) "veltrio-androidtv" else "veltrio-android"

    /** Silently checks for a newer release once per launch (throttled to 12 h)
     *  in release builds when auto-check is enabled. */
    suspend fun checkOnLaunchIfNeeded(context: Context) {
        if (didLaunchCheck || !AppPrefs.current.autoCheckUpdates) return
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        didLaunchCheck = true
        val prefs = context.getSharedPreferences("veltrio_updater", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check_ms", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 12L * 3600L * 1000L) return
        prefs.edit().putLong("last_check_ms", now).apply()
        check(context, silent = true)
    }

    /** Looks for a newer release and downloads its APK. */
    suspend fun check(context: Context, silent: Boolean = false) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (!silent) _state.value = State(Step.Development)
            return
        }
        if (_state.value.step in setOf(Step.Checking, Step.Downloading)) return
        if (!silent) _state.value = State(Step.Checking)
        try {
            val releases = Http.json(Request.Builder().url("https://api.github.com/repos/VeltrioApp/${repository(context)}/releases?per_page=10").build(), "GitHub")
            val newest = releases.items.filter { it.bool("draft") != true }.maxWithOrNull { a, b -> compare(a.str("tag_name") ?: "", b.str("tag_name") ?: "") }
            val tag = newest?.str("tag_name")
            if (newest == null || tag == null || compare(tag, currentVersion(context)) <= 0) {
                if (!silent) _state.value = State(Step.UpToDate)
                return
            }
            val asset = newest.array("assets").firstOrNull { it.str("name")?.endsWith("-release.apk") == true }
                ?: throw ServiceException(tr("La version %s n'a pas d'APK.", tag))
            _state.value = State(Step.Downloading, tag)
            val target = File(context.cacheDir, "updates/veltrio-$tag.apk").apply { parentFile?.mkdirs() }
            withContext(Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url(asset.str("browser_download_url")!!).build()).execute().use { r ->
                    if (!r.isSuccessful) throw ServiceException(tr("GitHub a répondu %s.", r.code))
                    val total = r.body.contentLength().toFloat()
                    var done = 0L
                    target.outputStream().use { out ->
                        val input = r.body.byteStream()
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) _state.value = _state.value.copy(progress = done / total)
                        }
                    }
                }
            }
            apk = target
            _state.value = State(Step.Ready, tag, 1f)
        } catch (e: Exception) {
            if (!silent) _state.value = State(Step.Failed, error = e.message ?: Core.humanize(e))
        }
    }

    /** Hands the downloaded APK to Android's installer. */
    fun install(context: Context) {
        val file = apk ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** "v0.2.0-beta.1" against "0.1.0": numbers first, a pre-release before its release. */
    internal fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").split('-', limit = 2).let { it[0].split('.').map { n -> n.toIntOrNull() ?: 0 } to it.getOrNull(1) }
        val (na, pa) = parts(a)
        val (nb, pb) = parts(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val d = (na.getOrNull(i) ?: 0) - (nb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return when {
            pa == pb -> 0
            pa == null -> 1
            pb == null -> -1
            else -> pa.compareTo(pb)
        }
    }
}
