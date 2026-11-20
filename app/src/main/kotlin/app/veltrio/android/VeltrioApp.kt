package app.veltrio.android

import android.app.Application
import app.veltrio.shared.Storage

class VeltrioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
    }
}
