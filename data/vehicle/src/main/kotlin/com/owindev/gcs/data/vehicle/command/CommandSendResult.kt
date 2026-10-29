package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.vehicle.command.NotSentReason

/** Whether one command frame left this GCS (REQ-043). */
internal sealed interface CommandSendResult {

    data object Sent : CommandSendResult

    data class NotSent(val reason: NotSentReason) : CommandSendResult
}
