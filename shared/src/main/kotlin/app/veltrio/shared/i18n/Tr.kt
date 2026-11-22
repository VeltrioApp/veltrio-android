package app.veltrio.shared.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The app's text, as macOS does it: written out in the code (in French,
 *  this app's first language) and looked up in [English] when the app is
 *  in English. Placeholders are `%s` / `%d`, filled from [args], so the
 *  key doesn't change with the values. A missing entry shows the French text. */
fun tr(text: String, vararg args: Any?): String {
    val pattern = if (Lang.french) text else English.strings[text] ?: text
    return if (args.isEmpty()) pattern.replace("%%", "%") else runCatching { pattern.format(*args) }.getOrDefault(pattern)
}

object Lang {
    /** "" = follow system, "fr" = French, "en" = English. Compose state so
     *  switching language in Settings or Onboarding recomposes live. */
    var code by mutableStateOf("")
        private set

    val french: Boolean
        get() = when (code) {
            "fr" -> true
            "en" -> false
            else -> Locale.getDefault().language == "fr"
        }

    val resolvedCode: String get() = if (french) "fr" else "en"

    fun set(newCode: String) {
        code = newCode
    }
}
