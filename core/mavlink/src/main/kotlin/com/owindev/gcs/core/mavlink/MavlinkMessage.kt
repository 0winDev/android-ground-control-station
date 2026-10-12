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
     * COMMAND_LONG (id 76), as defined in https://mavlink.io/en/messages/common.html#COMMAND_LONG: a
     * command with up to seven float parameters, sent by the GCS and answered with a [CommandAck].
     *
     * The codec only encodes it; what each param means depends on [command] (`MAV_CMD`). Field
     * descriptions quote `common.xml` on `mavlink/mavlink` master.
     *
     * @property targetSystem "System which should execute the command".
     * @property targetComponent "Component which should execute the command, 0 for all components".
     * @property command `MAV_CMD` to execute, a uint16.
     * @property confirmation "0: First transmission of this command. 1-255: Confirmation transmissions".
     */
    // One property per field of the message definition; grouping the params would hide that mapping.
    @Suppress("LongParameterList")
    data class CommandLong(
        val targetSystem: Int,
        val targetComponent: Int,
        val command: Int,
        val confirmation: Int,
        val param1: Float,
        val param2: Float,
        val param3: Float,
        val param4: Float,
        val param5: Float,
        val param6: Float,
        val param7: Float,
    ) : MavlinkMessage

    /**
     * COMMAND_ACK (id 77), as defined in https://mavlink.io/en/messages/common.html#COMMAND_ACK: the
     * answer to a command.
     *
     * Every field after [result] is a MAVLink 2 extension: a sender may omit or truncate them, and then
     * they read as 0 (the wire rule), even where the definition gives another value for "unknown". Field
     * descriptions quote `common.xml` on `mavlink/mavlink` master.
     *
     * @property command `MAV_CMD` being acknowledged, a uint16.
     * @property result `MAV_RESULT`.
     * @property progress "The progress percentage when result is MAV_RESULT_IN_PROGRESS. Values: [0-100], or
     * UINT8_MAX if the progress is unknown." An ACK without extensions reads 0 here, not 255.
     * @property resultParam2 "Additional result information", an int32 whose enum, if any, is documented in
     * the `MAV_CMD`.
     * @property targetSystem "System ID of the target recipient": the system that sent the command.
     * @property targetComponent "Component ID of the target recipient": the component that sent the command.
     */
    data class CommandAck(
        val command: Int,
        val result: Int,
        val progress: Int,
        val resultParam2: Int,
        val targetSystem: Int,
        val targetComponent: Int,
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
