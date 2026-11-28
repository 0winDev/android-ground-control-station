package com.owindev.gcs.data.vehicle.mission

import com.owindev.gcs.core.domain.route.upload.UploadResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.mission.MissionUploadMachine.State
import com.owindev.gcs.data.vehicle.mission.MissionUploadMachine.Step
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What reaches an upload: a message from its vehicle and route, or the sender stopping it. */
internal sealed interface UploadInput {

    data class Received(val message: MavlinkMessage) : UploadInput

    data class Stop(val reason: NotSentReason) : UploadInput
}

/** Whether the sender let one message of the upload leave. */
internal sealed interface UploadSend {

    data object Sent : UploadSend

    data class Refused(val reason: NotSentReason) : UploadSend
}

/** Runs a [MissionUploadMachine] to its result, timing its waits on [timeSource] (ADR 0010). */
internal class MissionUploadRunner(private val timeSource: TimeSource.WithComparableMarks) {

    /**
     * [inputs] holds only what came from the upload's vehicle and route; once it closes, the running wait is
     * outwaited in silence.
     */
    suspend fun run(
        plan: MissionUploadPlan,
        send: suspend (MavlinkMessage) -> UploadSend,
        inputs: ReceiveChannel<UploadInput>,
    ): UploadResult {
        val machine = MissionUploadMachine(plan)
        var step = machine.start()
        var deadline: ComparableTimeMark = timeSource.markNow()
        while (true) {
            val state = step.state
            if (state is State.Ended) return state.result
            val refused = step.send?.let { send(it) } as? UploadSend.Refused
            if (refused != null) {
                step = machine.onSendRefused(state, refused.reason)
                continue
            }
            step.wait?.let { deadline = timeSource.markNow() + it }
            step = next(machine, state, deadline, inputs)
        }
    }

    private suspend fun next(
        machine: MissionUploadMachine,
        state: State,
        deadline: ComparableTimeMark,
        inputs: ReceiveChannel<UploadInput>,
    ): Step {
        val remaining = -deadline.elapsedNow()
        val received = withTimeoutOrNull(remaining) { inputs.receiveCatching() }
        if (received == null) return machine.onSilence(state)
        return when (val input = received.getOrNull()) {
            null -> {
                delay(-deadline.elapsedNow())
                machine.onSilence(state)
            }

            is UploadInput.Stop -> machine.onStop(state, input.reason)

            // A message no part of the upload keeps the running wait: telemetry never restarts it.
            is UploadInput.Received -> machine.on(state, input.message) ?: Step(state)
        }
    }
}
