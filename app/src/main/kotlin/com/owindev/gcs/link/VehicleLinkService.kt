package com.owindev.gcs.link

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the live link running in the background while the active vehicle is connected (REQ-046, ADR 0009): it
 * observes the vehicles under a `connectedDevice` foreground service holding a wake lock. Every decision is
 * [LinkServiceController]'s; this class only applies it.
 */
@AndroidEntryPoint
class VehicleLinkService : Service() {

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    @Inject
    lateinit var rule: BackgroundLinkRule

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: LinkServiceController
    private var observing: Job? = null
    private var shown: LinkNotice? = null

    override fun onCreate() {
        super.onCreate()
        controller = LinkServiceController(vehicleRepository, rule, WakeHold(this))
        LinkNotification.createChannel(this)
    }

    // A sticky restart would come with no vehicle: a killed process falls back on the vehicle's own GCS failsafe.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        start()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        apply(LinkRequest.TASK_REMOVED)
    }

    override fun onDestroy() {
        scope.cancel()
        controller.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Each start through startForegroundService must be answered with startForeground, running or not. */
    private fun start() {
        ServiceCompat.startForeground(
            this,
            LinkNotification.ID,
            LinkNotification.build(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (observing != null) return
        controller.started()
        observing = scope.launch {
            controller.notices.collect(::show)
            stopSelf()
        }
    }

    /** An action of a notification left from a service already stopped only stops this one again. */
    private fun apply(request: LinkRequest) {
        if (observing == null) return stopSelf()
        val action = controller.actionFor(request)
        if (action == LinkAction.STOP) return stopSelf()
        if (action == LinkAction.REFUSE) Log.i(TAG, "$request refused: the vehicle is armed")
        shown?.let(::show)
    }

    private fun show(notice: LinkNotice) {
        shown = notice
        // Without POST_NOTIFICATIONS the notification is hidden, but the service keeps running.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(LinkNotification.ID, LinkNotification.build(this))
        }
    }
}

private const val TAG = "VehicleLinkService"
