package app.veltrio.shared

import android.content.Context
import android.content.pm.PackageManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.allowHardware

/** What kind of device the app runs on, and the settings that follow. */
object Device {
    /** An Android TV box or TV (leanback), as opposed to a phone or tablet. */
    var isTv = false
        private set

    fun init(context: Context) {
        isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        // TV boxes have little graphics memory: posters and backdrops as
        // hardware bitmaps filled it on a Freebox (Amlogic, 32-bit) until
        // images drew black and the UI renderer aborted ("GL error: Out of
        // memory"). Ordinary bitmaps live in the app's memory instead.
        if (isTv) {
            SingletonImageLoader.setSafe { ctx -> ImageLoader.Builder(ctx).allowHardware(false).build() }
        }
    }
}
