package com.owindev.gcs.core.mavlink

/**
 * The messages the codec supports: CRC_EXTRA (to validate received frames and checksum sent ones) and full
 * payload length, extension fields included (to zero-fill truncated payloads).
 *
 * Source: the official definitions, `message_definitions/v1.0/minimal.xml` (HEARTBEAT), `standard.xml`
 * (GLOBAL_POSITION_INT) and `common.xml` (the others), which includes both, in
 * https://github.com/mavlink/mavlink, as bundled with pymavlink 2.4.50 and cross-checked against
 * `mavlink/mavlink` master (#8); COMMAND_LONG and COMMAND_ACK (#35), ATTITUDE, GLOBAL_POSITION_INT and
 * VFR_HUD (#37), SYS_STATUS and GPS_RAW_INT (#38), HOME_POSITION (#66), STATUSTEXT (#96), PARAM_REQUEST_READ and
 * PARAM_VALUE (#97) computed with pymavlink's `mavparse` from both.
 * SERVO_OUTPUT_RAW and POWER_STATUS are only validated and restored so far. COMMAND_LONG and PARAM_REQUEST_READ are
 * only sent by the codec, so a received one stays undecoded.
 */
object MavlinkMessages : CrcExtraLookup {

    private const val HEARTBEAT_ID = 0
    private const val SYS_STATUS_ID = 1
    private const val PARAM_REQUEST_READ_ID = 20
    private const val PARAM_VALUE_ID = 22
    private const val GPS_RAW_INT_ID = 24
    private const val ATTITUDE_ID = 30
    private const val GLOBAL_POSITION_INT_ID = 33
    private const val SERVO_OUTPUT_RAW_ID = 36
    private const val VFR_HUD_ID = 74
    private const val POWER_STATUS_ID = 125
    private const val COMMAND_LONG_ID = 76
    private const val COMMAND_ACK_ID = 77
    private const val HOME_POSITION_ID = 242
    private const val STATUSTEXT_ID = 253

    private val heartbeat = Definition(crcExtra = 50, fullLength = 9)
    private val paramRequestRead = Definition(crcExtra = 214, fullLength = 20)
    private val paramValue = Definition(crcExtra = 220, fullLength = 25)

    // 31 bytes without its extensions (onboard_control_sensors_present/enabled/health_extended).
    private val sysStatus = Definition(crcExtra = 124, fullLength = 43)

    // 30 bytes without its extensions (alt_ellipsoid, h_acc, v_acc, vel_acc, hdg_acc, yaw).
    private val gpsRawInt = Definition(crcExtra = 24, fullLength = 52)

    private val attitude = Definition(crcExtra = 39, fullLength = 28)
    private val globalPositionInt = Definition(crcExtra = 104, fullLength = 28)
    private val servoOutputRaw = Definition(crcExtra = 222, fullLength = 37)
    private val vfrHud = Definition(crcExtra = 20, fullLength = 20)
    private val powerStatus = Definition(crcExtra = 203, fullLength = 6)
    private val commandLong = Definition(crcExtra = 152, fullLength = 33)

    // 3 bytes without its extensions (progress, result_param2, target_system, target_component).
    private val commandAck = Definition(crcExtra = 143, fullLength = 10)

    // 52 bytes without its extension (time_usec).
    private val homePosition = Definition(crcExtra = 104, fullLength = 60)

    // 51 bytes without its extensions (id, chunk_seq).
    private val statusText = Definition(crcExtra = 83, fullLength = 54)

    override fun crcExtraFor(msgId: Int): Int? = definitionOf(msgId)?.crcExtra

    /** Full payload length of [msgId] in bytes, extension fields included, or `null` if not supported. */
    fun fullLengthOf(msgId: Int): Int? = definitionOf(msgId)?.fullLength

    // A `when` over constants, not a map: the parser calls this for every frame and it must not box. One flat branch
    // per message, so its complexity grows with the table, not with any logic.
    @Suppress("CyclomaticComplexMethod")
    private fun definitionOf(msgId: Int): Definition? = when (msgId) {
        HEARTBEAT_ID -> heartbeat
        SYS_STATUS_ID -> sysStatus
        PARAM_REQUEST_READ_ID -> paramRequestRead
        PARAM_VALUE_ID -> paramValue
        GPS_RAW_INT_ID -> gpsRawInt
        ATTITUDE_ID -> attitude
        GLOBAL_POSITION_INT_ID -> globalPositionInt
        SERVO_OUTPUT_RAW_ID -> servoOutputRaw
        VFR_HUD_ID -> vfrHud
        POWER_STATUS_ID -> powerStatus
        COMMAND_LONG_ID -> commandLong
        COMMAND_ACK_ID -> commandAck
        HOME_POSITION_ID -> homePosition
        STATUSTEXT_ID -> statusText
        else -> null
    }

    private class Definition(val crcExtra: Int, val fullLength: Int)
}
