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
     * PARAM_REQUEST_READ (id 20, https://mavlink.io/en/messages/common.html#PARAM_REQUEST_READ): asks a component for
     * one parameter by name, answered with a [ParamValue]. The codec only encodes it, always with `param_index` -1, so
     * the name is the identifier.
     *
     * @property paramId 1 to 16 ASCII characters without NUL, checked on construction: `param_id` is `char[16]`, sent
     * "WITHOUT null termination (NULL) byte if the length is exactly 16 chars" (`common.xml`).
     */
    data class ParamRequestRead(val targetSystem: Int, val targetComponent: Int, val paramId: String) :
        MavlinkMessage {

        init {
            require(paramId.length in PARAM_ID_LENGTHS) { "param_id \"$paramId\" is not 1 to 16 characters" }
            require(paramId.all { it in PARAM_ID_CHARACTERS }) { "param_id \"$paramId\" is not ASCII without NUL" }
        }

        private companion object {
            val PARAM_ID_LENGTHS = 1..16
            val PARAM_ID_CHARACTERS = '\u0001'..'\u007F'
        }
    }

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
     * SYS_STATUS (id 1), as defined in https://mavlink.io/en/messages/common.html#SYS_STATUS: "Sensor and
     * subsystem status information."
     *
     * Raw fields in the units of the definition; converting them is up to `:data:vehicle`. Field descriptions
     * quote `common.xml` on `mavlink/mavlink` master. The battery fields are "ambiguous on multi-battery
     * systems"; the definition recommends BATTERY_STATUS instead.
     *
     * The three `*Extended` fields are MAVLink 2 extensions. ArduCopter sends them as 0, so they are normally
     * truncated (LEN 31 in the captured session) and read as 0 here (the wire rule).
     *
     * @property onboardControlSensorsPresent `MAV_SYS_STATUS_SENSOR` bitmap: "Value of 0: not present. Value
     * of 1: present." A uint32 (hence [Long]).
     * @property onboardControlSensorsEnabled the same bitmap: "Value of 0: not enabled. Value of 1: enabled."
     * @property onboardControlSensorsHealth the same bitmap: "Value of 0: error. Value of 1: healthy."
     * @property load "Maximum usage in percent of the mainloop time" in d% (0–1000).
     * @property voltageBattery "Battery voltage" in mV, a uint16; "UINT16_MAX: Voltage not sent by autopilot".
     * @property currentBattery "Battery current" in cA, an int16; "-1: Current not sent by autopilot".
     * @property batteryRemaining "Battery energy remaining" in %, an int8; "-1: Battery remaining energy not
     * sent by autopilot".
     * @property dropRateComm "Communication drop rate" in c%, "dropped packets on all links".
     * @property errorsComm "Communication errors (UART, I2C, SPI, CAN)".
     * @property errorsCount1 "Autopilot-specific errors".
     * @property errorsCount2 "Autopilot-specific errors".
     * @property errorsCount3 "Autopilot-specific errors".
     * @property errorsCount4 "Autopilot-specific errors".
     * @property onboardControlSensorsPresentExtended `MAV_SYS_STATUS_SENSOR_EXTENDED` bitmap, as
     * [onboardControlSensorsPresent].
     * @property onboardControlSensorsEnabledExtended the same, as [onboardControlSensorsEnabled].
     * @property onboardControlSensorsHealthExtended the same, as [onboardControlSensorsHealth].
     */
    // One property per field of the message definition; grouping them would hide that mapping.
    @Suppress("LongParameterList")
    data class SysStatus(
        val onboardControlSensorsPresent: Long,
        val onboardControlSensorsEnabled: Long,
        val onboardControlSensorsHealth: Long,
        val load: Int,
        val voltageBattery: Int,
        val currentBattery: Int,
        val batteryRemaining: Int,
        val dropRateComm: Int,
        val errorsComm: Int,
        val errorsCount1: Int,
        val errorsCount2: Int,
        val errorsCount3: Int,
        val errorsCount4: Int,
        val onboardControlSensorsPresentExtended: Long,
        val onboardControlSensorsEnabledExtended: Long,
        val onboardControlSensorsHealthExtended: Long,
    ) : MavlinkMessage {

        /**
         * The `invalid` values of `common.xml` (master): what the sender puts when it has no reading. The codec
         * keeps them as sent; deciding what they mean is up to `:data:vehicle`.
         */
        companion object {
            /** [voltageBattery]: "UINT16_MAX: Voltage not sent by autopilot". */
            const val VOLTAGE_BATTERY_UNKNOWN = 0xFFFF

            /** [currentBattery]: "-1: Current not sent by autopilot". */
            const val CURRENT_BATTERY_UNKNOWN = -1

            /** [batteryRemaining]: "-1: Battery remaining energy not sent by autopilot". */
            const val BATTERY_REMAINING_UNKNOWN = -1
        }
    }

    /**
     * GPS_RAW_INT (id 24), as defined in https://mavlink.io/en/messages/common.html#GPS_RAW_INT: "The global
     * position, as returned by the Global Positioning System (GPS). This is NOT the global position estimate
     * of the system, but rather a RAW sensor value." The estimate is [GlobalPositionInt].
     *
     * Raw fields in the units of the definition; converting them is up to `:data:vehicle`. Field descriptions
     * quote `common.xml` on `mavlink/mavlink` master.
     *
     * Every field after [satellitesVisible] is a MAVLink 2 extension: a sender may omit or truncate them, and
     * then they read as 0 (the wire rule). Only for [yaw] does 0 mean "not provided"; a 0 accuracy is not a
     * perfect one, and a 0 [altEllipsoid] may not be a real altitude.
     *
     * @property timeUsec "Timestamp (UNIX Epoch time or time since system boot)" in µs, a uint64 read as a
     * [Long]: the magnitude tells which of the two it is. A negative value means the sender put 2⁶³ or more,
     * which no real timestamp reaches, so it is out of range.
     * @property fixType "GPS fix type", a `GPS_FIX_TYPE`.
     * @property lat "Latitude (WGS84, EGM96 ellipsoid)" in degE7.
     * @property lon "Longitude (WGS84, EGM96 ellipsoid)" in degE7.
     * @property alt "Altitude (MSL). Positive for up." in mm.
     * @property eph "GPS HDOP horizontal dilution of position (unitless * 100). If unknown, set to:
     * UINT16_MAX".
     * @property epv "GPS VDOP vertical dilution of position (unitless * 100). If unknown, set to: UINT16_MAX".
     * @property vel "GPS ground speed" in cm/s; "If unknown, set to: UINT16_MAX".
     * @property cog "Course over ground (NOT heading, but direction of movement)" in cdeg; "If unknown, set
     * to: UINT16_MAX".
     * @property satellitesVisible "Number of satellites visible. If unknown, set to UINT8_MAX".
     * @property altEllipsoid "Altitude (above WGS84, EGM96 ellipsoid). Positive for up." in mm.
     * @property hAcc "Position uncertainty" in mm, a uint32 (hence [Long]).
     * @property vAcc "Altitude uncertainty" in mm, a uint32.
     * @property velAcc "Speed uncertainty" in mm/s, a uint32.
     * @property hdgAcc "Heading / track uncertainty" in degE5, a uint32.
     * @property yaw "Yaw in earth frame from north" in cdeg: "Use 0 if this GPS does not provide yaw. Use
     * UINT16_MAX if this GPS is configured to provide yaw and is currently unable to provide it. Use 36000
     * for north."
     */
    // One property per field of the message definition; grouping them would hide that mapping.
    @Suppress("LongParameterList")
    data class GpsRawInt(
        val timeUsec: Long,
        val fixType: Int,
        val lat: Int,
        val lon: Int,
        val alt: Int,
        val eph: Int,
        val epv: Int,
        val vel: Int,
        val cog: Int,
        val satellitesVisible: Int,
        val altEllipsoid: Int,
        val hAcc: Long,
        val vAcc: Long,
        val velAcc: Long,
        val hdgAcc: Long,
        val yaw: Int,
    ) : MavlinkMessage {

        /**
         * The `invalid` values of `common.xml` (master) and the other special values it gives for [yaw]. The
         * codec keeps them as sent; deciding what they mean is up to `:data:vehicle`.
         */
        companion object {
            /** [eph]: "If unknown, set to: UINT16_MAX". */
            const val EPH_UNKNOWN = 0xFFFF

            /** [epv]: "If unknown, set to: UINT16_MAX". */
            const val EPV_UNKNOWN = 0xFFFF

            /** [vel]: "If unknown, set to: UINT16_MAX". */
            const val VEL_UNKNOWN = 0xFFFF

            /** [cog]: "If unknown, set to: UINT16_MAX". */
            const val COG_UNKNOWN = 0xFFFF

            /** [satellitesVisible]: "If unknown, set to UINT8_MAX". */
            const val SATELLITES_VISIBLE_UNKNOWN = 0xFF

            /** [yaw]: "Use 0 if this GPS does not provide yaw"; also what zero-fill restores. */
            const val YAW_NOT_PROVIDED = 0

            /**
             * [yaw]: "Use UINT16_MAX if this GPS is configured to provide yaw and is currently unable to provide
             * it".
             */
            const val YAW_UNAVAILABLE = 0xFFFF

            /** [yaw]: "Use 36000 for north", since 0 already means [YAW_NOT_PROVIDED]. */
            const val YAW_NORTH = 36_000
        }
    }

    /**
     * HOME_POSITION (id 242, https://mavlink.io/en/messages/common.html#HOME_POSITION), raw in the definition's
     * units. Zero-fill restores cut bytes as 0: a missing [timeUsec] reads 0, and a cut `q` reads 0, 0, 0, 0, not its
     * `invalid` NaN. ArduPilot sends NaN in [x], [y], [z] without an EKF origin (`send_home_position`,
     * `GCS_Common.cpp`).
     *
     * @property latitude degE7, WGS84.
     * @property longitude degE7, WGS84.
     * @property altitude mm above mean sea level, not above home.
     * @property x local NED X in m.
     * @property y local NED Y in m.
     * @property z local NED Z in m, positive down.
     * @property q1 `q[0]` of the world-to-surface-normal and heading quaternion of the takeoff position; NaN in all
     * four when unknown.
     * @property q2 `q[1]`.
     * @property q3 `q[2]`.
     * @property q4 `q[3]`.
     * @property approachX end of the approach vector, local X in m.
     * @property approachY end of the approach vector, local Y in m.
     * @property approachZ end of the approach vector, local Z in m.
     * @property timeUsec µs since the UNIX epoch or since boot (the magnitude tells); negative from 2⁶³ up.
     */
    // One property per field of the definition, so the mapping stays visible.
    @Suppress("LongParameterList")
    data class HomePosition(
        val latitude: Int,
        val longitude: Int,
        val altitude: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        val q1: Float,
        val q2: Float,
        val q3: Float,
        val q4: Float,
        val approachX: Float,
        val approachY: Float,
        val approachZ: Float,
        val timeUsec: Long,
    ) : MavlinkMessage

    /**
     * STATUSTEXT (id 253, https://mavlink.io/en/messages/common.html#STATUSTEXT): a text from the system, which
     * ArduPilot uses for the reasons it refuses to arm (`PreArm: …`, `Arm: …`). [id] and [chunkSeq] are extensions:
     * omitted, they read 0, and `id` 0 means the message is a single chunk.
     *
     * @property severity `MAV_SEVERITY`, 0 (emergency) to 7 (debug), as sent, even outside that range.
     * @property text from up to 50 bytes, ending at the first NUL, the end of the field or the end of a truncated
     * payload, decoded as UTF-8 with each invalid sequence replaced by U+FFFD; a character split between chunks is
     * replaced in each of them.
     * @property id the message's chunks share it; 0 for a message sent in one piece.
     * @property chunkSeq "This chunk's sequence number; indexing is from zero."
     * @property endsInField the text ended before the 50th byte (a NUL or a truncated payload), which on a chunk
     * "is taken to mean this was the last chunk"; false when all 50 bytes are text.
     */
    data class StatusText(
        val severity: Int,
        val text: String,
        val id: Int,
        val chunkSeq: Int,
        val endsInField: Boolean,
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
