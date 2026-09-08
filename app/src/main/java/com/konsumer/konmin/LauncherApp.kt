package com.konsumer.konmin

import android.app.Application
import com.konsumer.konmin.worker.TickScheduler
import com.whl.quickjs.android.QuickJSLoader

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads libquickjs-android-wrapper.so. Must happen before any
        // QuickJSContext.create(), including the manifest probe the plugin
        // repository runs while seeding.
        QuickJSLoader.init()
        TickScheduler.ensureScheduled(this)
    }
}
