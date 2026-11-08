package com.owindev.gcs.link

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.owindev.gcs.MainActivity
import com.owindev.gcs.R
import com.owindev.gcs.core.domain.vehicle.FlightMode

/**
 * The background link's notification (REQ-046): the vehicle, its link, armed state and mode; a tap opens the app.
 * Disconnect is offered only while the rule allows it, and dismissing it posts it again (Android 14 lets an ongoing
 * notification be dismissed).
 */
internal object LinkNotification {

    const val ID = 1
    const val ACTION_DISCONNECT = "com.owindev.gcs.link.DISCONNECT"
    const val ACTION_POST_AGAIN = "com.owindev.gcs.link.POST_AGAIN"

    const val CHANNEL = "vehicle_link"
    private const val OPEN_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.link_channel))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun build(context: Context, notice: LinkNotice?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_vehicle_link)
            .setContentTitle(notice?.let { title(context, it) } ?: context.getString(R.string.link_keeping))
            .setContentText(notice?.let { text(context, it) })
            .setContentIntent(activity(context))
            .setDeleteIntent(service(context, ACTION_POST_AGAIN))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (notice?.disconnectOffered == true) {
            builder.addAction(0, context.getString(R.string.link_disconnect), service(context, ACTION_DISCONNECT))
        }
        return builder.build()
    }

    private fun title(context: Context, notice: LinkNotice): String {
        val link = context.getString(if (notice.connected) R.string.link_connected else R.string.link_lost)
        return context.getString(R.string.link_title, notice.state.systemId.value, link)
    }

    private fun text(context: Context, notice: LinkNotice): String {
        val armed = context.getString(if (notice.state.armed) R.string.link_armed else R.string.link_disarmed)
        val mode = when (val mode = notice.state.flightMode) {
            is FlightMode.Named -> mode.name
            is FlightMode.Unknown -> mode.number.toString()
        }
        return context.getString(
            if (notice.connected) R.string.link_text else R.string.link_text_last_known,
            armed,
            mode,
        )
    }

    private fun activity(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(OPEN_FLAGS),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun service(context: Context, action: String): PendingIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, VehicleLinkService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
