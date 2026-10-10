package com.owindev.gcs.core.mavlink

/**
 * The messages the codec supports in v0.1: CRC_EXTRA (to validate frames) and full payload length,
 * extension fields included (to zero-fill truncated payloads).
 *
 * Source: the official definitions, `message_definitions/v1.0/minimal.xml` (HEARTBEAT) and `common.xml`
 * (the others) in https://github.com/mavlink/mavlink, as bundled with pymavlink 2.4.50 and cross-checked
 * against `mavlink/mavlink` master (#8). SYS_STATUS, SERVO_OUTPUT_RAW and POWER_STATUS are only
 * validated and restored in v0.1; their fields are decoded in v0.2.
 */
object MavlinkMessages : CrcExtraLookup {

    private const val HEARTBEAT_ID = 0
    private const val SYS_STATUS_ID = 1
    private const val SERVO_OUTPUT_RAW_ID = 36
    private const val POWER_STATUS_ID = 125

    private val heartbeat = Definition(crcExtra = 50, fullLength = 9)
    private val sysStatus = Definition(crcExtra = 124, fullLength = 43)
    private val servoOutputRaw = Definition(crcExtra = 222, fullLength = 37)
    private val powerStatus = Definition(crcExtra = 203, fullLength = 6)

    override fun crcExtraFor(msgId: Int): Int? = definitionOf(msgId)?.crcExtra

    /** Full payload length of [msgId] in bytes, extension fields included, or `null` if not supported. */
    fun fullLengthOf(msgId: Int): Int? = definitionOf(msgId)?.fullLength

    // A `when` over constants, not a map: the parser calls this for every frame and it must not box.
    private fun definitionOf(msgId: Int): Definition? = when (msgId) {
        HEARTBEAT_ID -> heartbeat
        SYS_STATUS_ID -> sysStatus
        SERVO_OUTPUT_RAW_ID -> servoOutputRaw
        POWER_STATUS_ID -> powerStatus
        else -> null
    }

    private class Definition(val crcExtra: Int, val fullLength: Int)
}
