package app.veltrio.shared.i18n

import java.util.Locale

/** The app's text, as macOS does it: written out in the code (in French,
 *  this app's first language) and looked up in [English] when the device
 *  isn't in French. Placeholders are `%s` / `%d`, filled from [args], so
 *  the key doesn't change with the values. A missing entry shows the
 *  French text. */
fun tr(text: String, vararg args: Any?): String {
    val pattern = if (Lang.french) text else English.strings[text] ?: text
    return if (args.isEmpty()) pattern.replace("%%", "%") else runCatching { pattern.format(*args) }.getOrDefault(pattern)
}

object Lang {
    /** The device's language decides, as on macOS. */
    val french: Boolean get() = Locale.getDefault().language == "fr"
}
