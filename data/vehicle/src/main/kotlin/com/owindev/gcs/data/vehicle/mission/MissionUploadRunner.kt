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
     * [inputs] holds only what came from the upload's vehicle and route, addressed to this GCS; the caller sends
     * [UploadInput.Stop] before closing it, since a closed one leaves the machine sending counts nobody answers. [send]
     * turns every transport failure into [UploadSend.Refused]: a throw, or a cancellation, ends the upload without a
     * result.
     */
    suspend fun run(
        plan: MissionUploadPlan,
        send: suspend (MavlinkMessage) -> UploadSend,
        inputs: ReceiveChannel<UploadInput>,
    ): UploadResult {
        val machine = MissionUploadMachine(plan)
        val limit = timeSource.markNow() + uploadLimit(plan.count)
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
            step = next(machine, state, Waits(deadline, limit), inputs)
        }
    }

    private suspend fun next(
        machine: MissionUploadMachine,
        state: State,
        waits: Waits,
        inputs: ReceiveChannel<UploadInput>,
    ): Step {
        // The limit bounds the exchange only: an outwaited result keeps its full wait.
        val limited = state !is State.Outwaiting && waits.limit < waits.deadline
        val due = if (limited) waits.limit else waits.deadline
        val passed = { if (limited) machine.onUploadLimit(state) else machine.onSilence(state) }
        val received = withTimeoutOrNull(-due.elapsedNow()) { inputs.receiveCatching() } ?: return passed()
        return when (val input = received.getOrNull()) {
            null -> {
                delay(-due.elapsedNow())
                passed()
            }

            is UploadInput.Stop -> machine.onStop(state, input.reason)

            // A message no part of the upload keeps the running wait: telemetry never restarts it.
            is UploadInput.Received -> machine.on(state, input.message) ?: Step(state)
        }
    }

    /** The running wait's [deadline] and the whole upload's [limit]. */
    private class Waits(val deadline: ComparableTimeMark, val limit: ComparableTimeMark)
}
