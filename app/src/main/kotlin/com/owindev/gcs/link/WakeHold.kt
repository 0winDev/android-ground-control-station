package com.owindev.gcs.link

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/**
 * One partial wake lock, so the GCS HEARTBEAT and the watchdog's timers never pause with the screen off (ADR 0009);
 * not reference counted, so one release undoes any number of acquires.
 */
internal class WakeHold(context: Context) {

    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gcs:vehicle-link")
        .apply { setReferenceCounted(false) }

    // Held for exactly the service's life, which BackgroundLinkRule bounds: a timeout would let an armed vehicle go.
    @SuppressLint("WakelockTimeout")
    fun acquire() = lock.acquire()

    fun release() {
        if (lock.isHeld) lock.release()
    }
}
