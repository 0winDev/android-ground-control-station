package com.owindev.gcs.core.mavlink

/** A decoded MAVLink message. Values are the raw fields of the `common` dialect, not interpreted. */
sealed interface MavlinkMessage {

    /**
     * HEARTBEAT (id 0), as defined in https://mavlink.io/en/messages/common.html#HEARTBEAT.
     *
     * Autopilot-specific meaning (ArduCopter flight mode in [customMode], the armed bit in [baseMode])
     * is mapped in `:data:vehicle` (ADR 0003).
     *
     * @property type `MAV_TYPE` of the sender.
     * @property autopilot `MAV_AUTOPILOT` of the sender.
     * @property baseMode `MAV_MODE_FLAG` bitmap.
     * @property customMode autopilot-specific mode, a uint32 (hence [Long]).
     * @property systemStatus `MAV_STATE`.
     * @property mavlinkVersion MAVLink version the sender implements (3 for MAVLink 2).
     */
    data class Heartbeat(
        val type: Int,
        val autopilot: Int,
        val baseMode: Int,
        val customMode: Long,
        val systemStatus: Int,
        val mavlinkVersion: Int,
    ) : MavlinkMessage

    /**
     * A message the codec does not decode into fields. For a message in [MavlinkMessages], [payload] is
     * already zero-filled to the message's full length; otherwise it is the payload as received.
     *
     * Not a data class: it holds an array, so equality compares its contents explicitly.
     */
    class Undecoded(val messageId: Int, val payload: ByteArray) : MavlinkMessage {

        override fun equals(other: Any?): Boolean =
            other is Undecoded && messageId == other.messageId && payload.contentEquals(other.payload)

        override fun hashCode(): Int = HASH_MULTIPLIER * messageId + payload.contentHashCode()

        override fun toString(): String = "Undecoded(msgid=$messageId, len=${payload.size})"

        private companion object {
            const val HASH_MULTIPLIER = 31
        }
    }
}
