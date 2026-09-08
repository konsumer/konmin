package com.konsumer.konmin.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the app list live when packages come and go.
 *
 * ACTION_PACKAGE_ADDED/REMOVED/REPLACED are still deliverable to
 * manifest-registered receivers (they're on the implicit-broadcast
 * exemption list), but only when the filter carries the
 * `<data android:scheme="package"/>` entry — see AndroidManifest.xml.
 *
 * ACTION_PACKAGE_REMOVED also fires for the uninstall half of an app
 * update, where EXTRA_REPLACING is true; we skip those, since the matching
 * ACTION_PACKAGE_ADDED/REPLACED will re-sync a moment later anyway.
 */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
            intent.action == Intent.ACTION_PACKAGE_REMOVED
        ) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppSync.sync(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
