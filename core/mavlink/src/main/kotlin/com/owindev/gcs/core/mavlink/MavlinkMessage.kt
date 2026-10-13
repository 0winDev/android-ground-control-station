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
     * ATTITUDE (id 30), as defined in https://mavlink.io/en/messages/common.html#ATTITUDE: "The attitude in
     * the aeronautical frame (right-handed, Z-down, Y-right, X-front, ZYX, intrinsic)."
     *
     * Raw fields in the units of the definition (radians, not degrees); converting them is up to
     * `:data:vehicle`. Field descriptions quote `common.xml` on `mavlink/mavlink` master.
     *
     * @property timeBootMs "Timestamp (time since system boot)" in ms, a uint32 (hence [Long]).
     * @property roll "Roll angle (-pi..+pi)" in rad.
     * @property pitch "Pitch angle (-pi..+pi)" in rad.
     * @property yaw "Yaw angle (-pi..+pi)" in rad.
     * @property rollspeed "Roll angular speed" in rad/s.
     * @property pitchspeed "Pitch angular speed" in rad/s.
     * @property yawspeed "Yaw angular speed" in rad/s.
     */
    data class Attitude(
        val timeBootMs: Long,
        val roll: Float,
        val pitch: Float,
        val yaw: Float,
        val rollspeed: Float,
        val pitchspeed: Float,
        val yawspeed: Float,
    ) : MavlinkMessage

    /**
     * GLOBAL_POSITION_INT (id 33), as defined in https://mavlink.io/en/messages/common.html#GLOBAL_POSITION_INT:
     * "The filtered global position (e.g. fused GPS and accelerometers)."
     *
     * Defined in `standard.xml`, which `common.xml` includes. Raw scaled integers in the units of the
     * definition; converting them is up to `:data:vehicle`. Field descriptions quote `standard.xml` on
     * `mavlink/mavlink` master.
     *
     * The description goes on "The position is in GPS-frame (right-handed, Z-up)", yet the `vz` field says
     * "positive down"; [vz] follows the field, so a climbing vehicle sends a negative `vz`.
     *
     * @property timeBootMs "Timestamp (time since system boot)" in ms, a uint32 (hence [Long]).
     * @property lat latitude in degE7 (degrees × 10⁷).
     * @property lon longitude in degE7.
     * @property alt "Altitude (MSL)" in mm.
     * @property relativeAlt "Altitude above home" in mm: not above the ground under the vehicle.
     * @property vx "Ground X Speed (Latitude, positive north)" in cm/s.
     * @property vy "Ground Y Speed (Longitude, positive east)" in cm/s.
     * @property vz "Ground Z Speed (Altitude, positive down)" in cm/s: negative while climbing.
     * @property hdg "Vehicle heading (yaw angle), 0.0..359.99 degrees" in cdeg, a uint16; "If unknown, set to:
     * UINT16_MAX" (65535).
     */
    // One property per field of the message definition; grouping them would hide that mapping.
    @Suppress("LongParameterList")
    data class GlobalPositionInt(
        val timeBootMs: Long,
        val lat: Int,
        val lon: Int,
        val alt: Int,
        val relativeAlt: Int,
        val vx: Int,
        val vy: Int,
        val vz: Int,
        val hdg: Int,
    ) : MavlinkMessage

    /**
     * VFR_HUD (id 74), as defined in https://mavlink.io/en/messages/common.html#VFR_HUD: "Metrics typically
     * displayed on a HUD for fixed wing aircraft."
     *
     * Raw fields in the units of the definition. Field descriptions quote `common.xml` on `mavlink/mavlink`
     * master.
     *
     * @property airspeed "Vehicle speed in form appropriate for vehicle type" in m/s.
     * @property groundspeed "Current ground speed" in m/s.
     * @property heading "Current heading in compass units (0-360, 0=north)" in degrees, an int16.
     * @property throttle "Current throttle setting (0 to 100)" in %, a uint16.
     * @property alt "Current altitude (MSL)" in m.
     * @property climb "Current climb rate" in m/s.
     */
    data class VfrHud(
        val airspeed: Float,
        val groundspeed: Float,
        val heading: Int,
        val throttle: Int,
        val alt: Float,
        val climb: Float,
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
