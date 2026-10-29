package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.Rejection
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Every `MAV_RESULT` of `common.xml` (mavlink/mavlink master), read from a COMMAND_ACK. */
@Tag("REQ-042")
class CommandMessagesTest {

    @Test
    fun `GIVEN ACCEPTED and TEMPORARILY_REJECTED WHEN read THEN the command is accepted or the vehicle busy`() {
        val acks = listOf(ack(result = 0), ack(result = 1))

        val result = acks.map { it.toResult() }

        result shouldBeEqualTo listOf(CommandResult.Accepted, CommandResult.Busy)
    }

    @ParameterizedTest
    @CsvSource(
        "2, DENIED",
        "3, UNSUPPORTED",
        "4, FAILED",
        "6, CANCELLED",
        "7, COMMAND_LONG_ONLY",
        "8, COMMAND_INT_ONLY",
        "9, UNSUPPORTED_FRAME",
        "10, NOT_IN_CONTROL",
        "11, UNKNOWN",
        "255, UNKNOWN",
    )
    fun `GIVEN a refusing MAV_RESULT WHEN read THEN the command is rejected for that reason`(
        mavResult: Int,
        rejection: Rejection,
    ) {
        val ack = ack(result = mavResult)

        val result = ack.toResult()

        result shouldBeEqualTo CommandResult.Rejected(rejection, reason = null)
    }

    @ParameterizedTest
    @CsvSource("5, true", "0, false", "1, false", "4, false")
    fun `GIVEN a MAV_RESULT WHEN read THEN only IN_PROGRESS is not final`(mavResult: Int, inProgress: Boolean) {
        val ack = ack(result = mavResult)

        val result = ack.isInProgress

        result shouldBeEqualTo inProgress
    }

    @Test
    fun `GIVEN a progress and an unknown one WHEN read THEN the percentage or none`() {
        val acks = listOf(
            ack(result = 5, progress = 40),
            ack(result = 5, progress = 0),
            ack(result = 5, progress = 255),
        )

        val result = acks.map { it.percent }

        result shouldBeEqualTo listOf(40, 0, null)
    }

    private fun ack(result: Int, progress: Int = UNKNOWN_PROGRESS) = CommandAck(
        command = MAV_CMD_NAV_RETURN_TO_LAUNCH_ID,
        result = result,
        progress = progress,
        resultParam2 = 0,
        targetSystem = 255,
        targetComponent = 190,
    )

    private companion object {
        const val MAV_CMD_NAV_RETURN_TO_LAUNCH_ID = 20
        const val UNKNOWN_PROGRESS = 255
    }
}
