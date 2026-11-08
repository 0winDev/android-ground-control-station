package com.owindev.gcs

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's root. The `:core:domain` contracts are bound in `:data:vehicle` (`VehicleDataModule`) and the app's own
 * settings in `AppModule`.
 */
@HiltAndroidApp
class GcsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        logLastExit()
    }

    /** The Task Manager's Stop, force stop and an OEM's kill end the background link with no callback (REQ-046). */
    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val exit = getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 1)
            .firstOrNull() ?: return
        lastExitNote(exit.reason)?.let { Log.w(TAG, "Last stopped: $it at ${exit.timestamp}") }
    }
}

/** Only the stops that can end the background link while it is needed; the rest are ordinary. */
internal fun lastExitNote(reason: Int): String? = when (reason) {
    ApplicationExitInfo.REASON_USER_REQUESTED -> "by the user (Task Manager or force stop)"
    ApplicationExitInfo.REASON_OTHER -> "by the system (an OEM's battery manager or similar)"
    else -> null
}

private const val TAG = "GcsApplication"
