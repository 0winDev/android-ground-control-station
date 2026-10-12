package com.owindev.gcs.data.vehicle.link

import android.util.Log
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.transport.SendResult
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Announces this app as a ground station (REQ-021): ArduPilot only treats a peer as a GCS when it
 * receives that peer's HEARTBEAT, and its GCS failsafe (`FS_GCS_ENABLE`) watches exactly that.
 *
 * Once per [PERIOD] it asks where to send; it sends only when given an address, so whoever starts it
 * decides when the vehicle may consider this GCS present. The sequence number belongs to this GCS's
 * component, not to HEARTBEAT, so [GcsMessageSender] owns it for every message the component sends.
 *
 * Anything thrown other than an expected I/O failure stops the loop and crashes the app (the application
 * scope has no handler). For the vehicle that is the safe direction: it simply stops hearing this GCS.
 */
@Singleton
internal class GcsHeartbeatSender @Inject constructor(private val sender: GcsMessageSender) {

    private val running = AtomicBoolean(false)

    /** Only [run] touches it, from one coroutine. */
    private var failing = false

    /**
     * Runs until cancelled; [destination] is read once per period, `null` meaning "send nothing now".
     *
     * @throws IllegalStateException if it is already running: two loops would double the rate.
     */
    suspend fun run(destination: () -> InetSocketAddress?) {
        check(running.compareAndSet(false, true)) { "The GCS HEARTBEAT sender is already running" }
        try {
            while (true) {
                destination()?.let { peer -> sendTo(peer) }
                delay(PERIOD)
            }
        } finally {
            running.set(false)
        }
    }

    private suspend fun sendTo(peer: InetSocketAddress) {
        when (val result = sender.send(GCS_HEARTBEAT, peer)) {
            SendResult.Sent -> failing = false

            // The link closed meanwhile; the next period asks again.
            SendResult.NotOpen -> Unit

            is SendResult.Failed -> {
                // Logged once per run of failures, without the peer's address, to diagnose without
                // flooding the log once per second.
                if (!failing) Log.w(TAG, "Sending the GCS HEARTBEAT failed", result.cause)
                failing = true
            }
        }
    }

    companion object {
        /** ArduPilot sends its HEARTBEAT at 1 Hz, and so do QGroundControl and Mission Planner. */
        val PERIOD: Duration = 1.seconds

        private const val MAV_TYPE_GCS = 6
        private const val MAV_AUTOPILOT_INVALID = 8
        private const val MAV_STATE_UNINIT = 0
        private const val MAVLINK_VERSION = 3
        private const val TAG = "GcsHeartbeatSender"

        /**
         * The same fields as Mission Planner's HEARTBEAT (`gcs/heartbeat-mission-planner.bin`): a ground
         * station, no autopilot (`minimal.xml`), no mode flags, MAVLink 2.
         */
        val GCS_HEARTBEAT = Heartbeat(
            type = MAV_TYPE_GCS,
            autopilot = MAV_AUTOPILOT_INVALID,
            baseMode = 0,
            customMode = 0,
            systemStatus = MAV_STATE_UNINIT,
            mavlinkVersion = MAVLINK_VERSION,
        )
    }
}
