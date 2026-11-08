package com.owindev.gcs.link

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.owindev.gcs.core.domain.background.BackgroundLinkRepository
import com.owindev.gcs.core.domain.background.BackgroundLinkStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The background link's state (REQ-046): whether the service runs, what the device lets it do, and whether the operator
 * disconnected it. The activity starts the service from it and reads the device again on every start.
 */
@Singleton
class BackgroundLinkState @Inject constructor() : BackgroundLinkRepository {

    private val mutableStatus = MutableStateFlow(BackgroundLinkStatus())

    override val status: StateFlow<BackgroundLinkStatus> = mutableStatus.asStateFlow()

    /** Set by an accepted Disconnect; cleared once a live vehicle is armed, which always overrides it. */
    val disconnectedByUser = MutableStateFlow(false)

    /** `POST_NOTIFICATIONS` is asked once per process, when the service first starts. */
    var notificationsAsked = false

    fun setRunning(running: Boolean) = mutableStatus.update { it.copy(running = running) }

    /** The operator changes these in the system's settings, so they are read again on every start of the activity. */
    fun refresh(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        refresh(
            notificationHidden = !NotificationManagerCompat.from(context).areNotificationsEnabled(),
            batteryOptimized = !power.isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activity.isBackgroundRestricted,
        )
    }

    fun refresh(notificationHidden: Boolean, batteryOptimized: Boolean, backgroundRestricted: Boolean) =
        mutableStatus.update {
            it.copy(
                notificationHidden = notificationHidden,
                batteryOptimized = batteryOptimized,
                backgroundRestricted = backgroundRestricted,
            )
        }
}
