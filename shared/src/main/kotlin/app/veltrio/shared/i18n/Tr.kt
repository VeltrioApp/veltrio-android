package app.veltrio.shared.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The app's text: written in French in code and looked up in the chosen
 *  language (fr, en, es, de, it) with English fallback for secondary labels. */
fun tr(text: String, vararg args: Any?): String {
    val lang = Lang.resolvedCode
    val pattern = when (lang) {
        "fr" -> text
        "en" -> English.strings[text] ?: text
        else -> Translations.forLang(lang)[text] ?: English.strings[text] ?: text
    }
    return if (args.isEmpty()) pattern.replace("%%", "%") else runCatching { pattern.format(*args) }.getOrDefault(pattern)
}

object Lang {
    /** "" = follow system, "fr" / "en" / "es" / "de" / "it". */
    var code by mutableStateOf("")
        private set

    val resolvedCode: String
        get() = when (code) {
            "fr", "en", "es", "de", "it" -> code
            else -> when (Locale.getDefault().language.lowercase()) {
                "fr" -> "fr"
                "es" -> "es"
                "de" -> "de"
                "it" -> "it"
                else -> "en"
            }
        }

    val french: Boolean get() = resolvedCode == "fr"

    fun tmdbLanguage(forCode: String = code): String = when (forCode) {
        "fr" -> "fr-FR"
        "en" -> "en-US"
        "es" -> "es-ES"
        "de" -> "de-DE"
        "it" -> "it-IT"
        else -> tmdbLanguage(resolvedCode)
    }

    fun set(newCode: String) {
        code = newCode
    }
}
