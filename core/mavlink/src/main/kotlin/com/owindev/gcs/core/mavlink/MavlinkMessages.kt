package com.owindev.gcs.core.mavlink

/**
 * The messages the codec supports: CRC_EXTRA (to validate received frames and checksum sent ones) and full
 * payload length, extension fields included (to zero-fill truncated payloads).
 *
 * Source: the official definitions, `message_definitions/v1.0/minimal.xml` (HEARTBEAT) and `common.xml`
 * (the others) in https://github.com/mavlink/mavlink, as bundled with pymavlink 2.4.50 and cross-checked
 * against `mavlink/mavlink` master (#8); COMMAND_LONG and COMMAND_ACK checked with pymavlink's `mavparse`
 * and against master (#35). SYS_STATUS, SERVO_OUTPUT_RAW and POWER_STATUS are only validated and restored
 * so far; their fields are decoded later in v0.2. COMMAND_LONG is only sent by the codec, so a received one
 * stays undecoded.
 */
object MavlinkMessages : CrcExtraLookup {

    private const val HEARTBEAT_ID = 0
    private const val SYS_STATUS_ID = 1
    private const val SERVO_OUTPUT_RAW_ID = 36
    private const val POWER_STATUS_ID = 125
    private const val COMMAND_LONG_ID = 76
    private const val COMMAND_ACK_ID = 77

    private val heartbeat = Definition(crcExtra = 50, fullLength = 9)
    private val sysStatus = Definition(crcExtra = 124, fullLength = 43)
    private val servoOutputRaw = Definition(crcExtra = 222, fullLength = 37)
    private val powerStatus = Definition(crcExtra = 203, fullLength = 6)
    private val commandLong = Definition(crcExtra = 152, fullLength = 33)

    // 3 bytes without its extensions (progress, result_param2, target_system, target_component).
    private val commandAck = Definition(crcExtra = 143, fullLength = 10)

    override fun crcExtraFor(msgId: Int): Int? = definitionOf(msgId)?.crcExtra

    /** Full payload length of [msgId] in bytes, extension fields included, or `null` if not supported. */
    fun fullLengthOf(msgId: Int): Int? = definitionOf(msgId)?.fullLength

    // A `when` over constants, not a map: the parser calls this for every frame and it must not box.
    private fun definitionOf(msgId: Int): Definition? = when (msgId) {
        HEARTBEAT_ID -> heartbeat
        SYS_STATUS_ID -> sysStatus
        SERVO_OUTPUT_RAW_ID -> servoOutputRaw
        POWER_STATUS_ID -> powerStatus
        COMMAND_LONG_ID -> commandLong
        COMMAND_ACK_ID -> commandAck
        else -> null
    }

    private class Definition(val crcExtra: Int, val fullLength: Int)
}
