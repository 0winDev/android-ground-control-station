package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.mavlink.MavlinkEncoder
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends every message of this GCS's component ([GCS_SYSTEM_ID]/[GCS_COMPONENT_ID]) on the vehicle link,
 * so that they all share one sequence number: "Components increment value for each message sent"
 * (https://mavlink.io/en/guide/serialization.html), whatever the message. Two counters for one component
 * would repeat numbers and make the vehicle count lost packets that were never lost.
 *
 * The number advances only for frames actually sent and wraps after 255. Senders run in different
 * coroutines (the HEARTBEAT loop, the telemetry requests), so encoding, sending and advancing happen under
 * one lock: a frame is never sent with a number another frame already used.
 *
 * Known limit: a sender cancelled while the socket write is under way (a telemetry round cut short by a
 * lost link) does not advance the number even if the frame left, so the next frame repeats it. The vehicle
 * then counts one more lost packet than there was; nothing else depends on it. Advancing anyway would do
 * the opposite when the frame did not leave.
 *
 * Every frame sent goes to the [recorder] (REQ-036), under the same lock, so the log keeps them in sequence order.
 *
 * Nothing is sent while the [session] plays a replay (REQ-037): [SendResult.NotOpen], as for a closed link. Its senders
 * never run then; this is the last guard, checked under the lock before every frame.
 */
@Singleton
internal class GcsMessageSender @Inject constructor(
    private val transport: UdpTransport,
    private val recorder: FlightRecorder,
    private val session: LinkSession,
) {

    private val lock = Mutex()

    /** Guarded by [lock]. */
    private var sequence = 0

    suspend fun send(heartbeat: Heartbeat, to: InetSocketAddress): SendResult =
        send(to) { sequence -> MavlinkEncoder.encode(sequence, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat) }

    suspend fun send(commandLong: CommandLong, to: InetSocketAddress): SendResult =
        send(to) { sequence -> MavlinkEncoder.encode(sequence, GCS_SYSTEM_ID, GCS_COMPONENT_ID, commandLong) }

    private suspend fun send(to: InetSocketAddress, encode: (sequence: Int) -> ByteArray): SendResult = lock.withLock {
        if (session.session.value.origin != DataOrigin.LIVE) return@withLock SendResult.NotOpen
        val frame = encode(sequence)
        val result = transport.send(frame, to)
        if (result == SendResult.Sent) {
            recorder.sent(frame)
            sequence = (sequence + 1) and SEQUENCE_MASK
        }
        result
    }

    companion object {
        /**
         * ArduPilot's `MAV_GCS_SYSID` default (`SYSID_MYGCS` before 4.7): the system ID it accepts for GCS
         * failsafe handling (`libraries/GCS_MAVLink/GCS_Param.cpp`).
         */
        const val GCS_SYSTEM_ID = 255

        /** `MAV_COMP_ID_MISSIONPLANNER` (`minimal.xml`), the component ground stations use. */
        const val GCS_COMPONENT_ID = 190

        private const val SEQUENCE_MASK = 0xFF
    }
}
