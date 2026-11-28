package com.owindev.gcs.data.vehicle.mission

import com.owindev.gcs.core.domain.route.upload.OnBoardMission
import com.owindev.gcs.core.domain.route.upload.UploadNotSentReason
import com.owindev.gcs.core.domain.route.upload.UploadResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The waits of ADR 0010 in virtual time, with every answer kept off a timeout's edge (#36). */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-056")
class MissionUploadRunnerTest {

    private val items = listOf(home(), item(seq = 1), item(seq = 2))
    private val plan = requireNotNull(MissionUploadPlan.of(items))
    private val inputs = Channel<UploadInput>(Channel.UNLIMITED)
    private val sent = mutableListOf<Pair<Long, MavlinkMessage>>()
    private val scope = TestScope()

    private lateinit var runner: MissionUploadRunner

    @BeforeEach
    fun setUp() {
        runner = MissionUploadRunner(scope.testScheduler.timeSource)
    }

    @Test
    fun `GIVEN a vehicle asking for every item WHEN uploaded THEN each is sent once and the upload accepted`() =
        scope.runTest {
            val vehicle = answering { message ->
                when (message) {
                    is MissionCount -> listOf(request(seq = 0))
                    is MissionItemInt -> listOf(if (message.seq < 2) request(message.seq + 1) else ack(ACCEPTED))
                    else -> emptyList()
                }
            }

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
            val result = deferred.await()

            result shouldBeEqualTo UploadResult.Accepted
            sent.map { it.second } shouldBeEqualTo listOf(plan.countMessage()) + items
        }

    @Test
    fun `GIVEN a silent vehicle WHEN uploaded THEN counts at 0, 1,5 and 3 s and no answer at 12 s`() = scope.runTest {
        val vehicle = answering { emptyList() }

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
        val result = deferred.await()

        result shouldBeEqualTo UploadResult.NoAnswer(stoppedBy = null, OnBoardMission.PreviousCut(count = 3))
        sent.map { it.first } shouldBeEqualTo listOf(0L, 1_500L, 3_000L)
        currentTime shouldBeEqualTo 12_000L
    }

    @Test
    fun `GIVEN telemetry every 0,5 s and no request WHEN uploaded THEN telemetry restarts no wait`() = scope.runTest {
        val vehicle = answering { emptyList() }
        launch {
            repeat(TELEMETRY_FRAMES) {
                inputs.send(UploadInput.Received(MavlinkMessage.MissionItemReached(seq = 1)))
                delay(500)
            }
        }

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
        val result = deferred.await()

        result shouldBeEqualTo UploadResult.NoAnswer(stoppedBy = null, OnBoardMission.PreviousCut(count = 3))
        currentTime shouldBeEqualTo 12_000L
    }

    @Test
    fun `GIVEN item 0 asked for then telemetry only WHEN uploaded THEN no answer 9 s after the request`() =
        scope.runTest {
            val vehicle = askingForItem0Only()
            launch {
                repeat(TELEMETRY_FRAMES) {
                    delay(500)
                    inputs.send(UploadInput.Received(MavlinkMessage.ExtendedSysState(0, 1)))
                }
            }

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
            val result = deferred.await()

            result shouldBeEqualTo UploadResult.NoAnswer(stoppedBy = null, partial(0..1))
            currentTime shouldBeEqualTo VEHICLE_DELAY + 9_000L
        }

    @Test
    fun `GIVEN item 0 sent and INVALID_SEQUENCE at 4 s WHEN uploaded THEN the wait restarts and ends at 13 s`() =
        scope.runTest {
            val vehicle = askingForItem0Only()
            launch {
                delay(4_000)
                inputs.send(UploadInput.Received(ack(INVALID_SEQUENCE)))
            }

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
            val result = deferred.await()

            result shouldBeEqualTo UploadResult.NoAnswer(stoppedBy = null, partial(0..1))
            currentTime shouldBeEqualTo 13_000L
        }

    @Test
    fun `GIVEN item 0 sent and a stale ACCEPTED at 4 s WHEN uploaded THEN the wait ends 9 s after the request`() =
        scope.runTest {
            val vehicle = askingForItem0Only()
            launch {
                delay(4_000)
                inputs.send(UploadInput.Received(ack(ACCEPTED)))
            }

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
            val result = deferred.await()

            result shouldBeEqualTo UploadResult.NoAnswer(stoppedBy = null, partial(0..1))
            currentTime shouldBeEqualTo VEHICLE_DELAY + 9_000L
        }

    @Test
    fun `GIVEN a stop at 2 s and requests after it WHEN uploaded THEN nothing more is sent and no answer at 11 s`() =
        scope.runTest {
            val vehicle = askingForItem0Only()
            launch {
                delay(2_000)
                inputs.send(UploadInput.Stop(NotSentReason.KeyChanged))
                delay(1_000)
                inputs.send(UploadInput.Received(request(seq = 1)))
            }

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
            val result = deferred.await()

            result shouldBeEqualTo UploadResult.NoAnswer(NotSentReason.KeyChanged, partial(0..1))
            sent.map { it.second } shouldBeEqualTo listOf(plan.countMessage(), items[0])
            currentTime shouldBeEqualTo 11_000L
        }

    @Test
    fun `GIVEN the first count refused WHEN uploaded THEN not sent at once`() = scope.runTest {
        val refusing: suspend (MavlinkMessage) -> UploadSend = { UploadSend.Refused(NotSentReason.LinkLost) }

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, refusing, inputs) }
        val result = deferred.await()

        result shouldBeEqualTo UploadResult.NotSent(UploadNotSentReason.Refused(NotSentReason.LinkLost))
        currentTime shouldBeEqualTo 0L
    }

    @Test
    fun `GIVEN item 1 refused WHEN uploaded THEN no answer 9 s later with item 1 not stored`() = scope.runTest {
        val vehicle: suspend (MavlinkMessage) -> UploadSend = { message ->
            when {
                message is MissionItemInt && message.seq == 1 -> UploadSend.Refused(NotSentReason.RouteChanged)

                else -> {
                    sent += testScheduler.currentTime to message
                    val next = if (message is MissionCount) 0 else 1
                    launch {
                        delay(VEHICLE_DELAY)
                        inputs.send(UploadInput.Received(request(next)))
                    }
                    UploadSend.Sent
                }
            }
        }

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
        val result = deferred.await()

        result shouldBeEqualTo UploadResult.NoAnswer(NotSentReason.RouteChanged, partial(1..1))
        currentTime shouldBeEqualTo 2 * VEHICLE_DELAY + 9_000L
    }

    @Test
    fun `GIVEN the inputs closed after the count WHEN uploaded THEN the counts go on and no answer at 12 s`() =
        scope.runTest {
            val vehicle = answering { emptyList() }
            inputs.close()

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { runner.run(plan, vehicle, inputs) }
            val result = deferred.await()

            result shouldBeEqualTo UploadResult.NoAnswer(stoppedBy = null, OnBoardMission.PreviousCut(count = 3))
            sent.map { it.first } shouldBeEqualTo listOf(0L, 1_500L, 3_000L)
            currentTime shouldBeEqualTo 12_000L
        }

    /** A vehicle that answers each message it gets, [VEHICLE_DELAY] later, with what [reply] says. */
    private fun TestScope.answering(
        reply: (MavlinkMessage) -> List<MavlinkMessage>,
    ): suspend (MavlinkMessage) -> UploadSend = { message ->
        sent += testScheduler.currentTime to message
        val answers = reply(message)
        launch {
            delay(VEHICLE_DELAY)
            answers.forEach { inputs.send(UploadInput.Received(it)) }
        }
        UploadSend.Sent
    }

    private fun TestScope.askingForItem0Only() =
        answering { message -> if (message is MissionCount) listOf(request(seq = 0)) else emptyList() }

    private fun partial(routeItems: IntRange) = OnBoardMission.Partial(routeItems, count = 3)

    private companion object {
        const val VEHICLE_DELAY = 20L
        const val TELEMETRY_FRAMES = 40
        const val ACCEPTED = 0
        const val INVALID_SEQUENCE = 13

        fun request(seq: Int) = MissionRequest(255, 190, seq, missionType = 0)

        fun ack(type: Int) = MissionAck(255, 190, type, missionType = 0, opaqueId = 0)

        fun home() = item(seq = 0).copy(frame = 0, current = 1)

        fun item(seq: Int) = MissionItemInt(
            targetSystem = 1,
            targetComponent = 1,
            seq = seq,
            frame = 3,
            command = 16,
            current = 0,
            autocontinue = 1,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            x = -353_633_515,
            y = 1_491_652_412,
            z = 10f,
            missionType = 0,
        )
    }
}
