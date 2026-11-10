package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.PreArmRule
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage.StatusText
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The chunks themselves are covered in `StatusTextAssemblerTest`; here, which messages are kept and for how long. */
@Tag("REQ-047")
class MessageStoreTest {

    private val clock = TestTimeSource()
    private val activeVehicle = ActiveVehicle()

    private lateinit var store: MessageStore

    @BeforeEach
    fun setUp() {
        store = MessageStore(activeVehicle, PreArmRule())
    }

    @Test
    fun `GIVEN the active vehicle WHEN it sends a text THEN it is kept with its severity, time and origin`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(wholeText(TEXT_1), FIRST_SESSION), clock.markNow())
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages(
            FIRST_SESSION,
            SystemId(MESSAGE_SYSTEM_1),
            listOf(kept(TEXT_1)),
            preArmReason = null,
        )
    }

    @Test
    fun `GIVEN a text kept WHEN a newer one arrives THEN the newest comes first`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val first = clock.markNow()
        store.record(received(wholeText(TEXT_1), FIRST_SESSION), first)
        clock += STEP

        store.record(received(wholeText(TEXT_2), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.messages

        result shouldBeEqualTo listOf(kept(TEXT_2), kept(TEXT_1, at = first))
    }

    @Test
    fun `GIVEN 100 texts kept WHEN one more arrives THEN the oldest is dropped`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        repeat(MAX_MESSAGES) { store.record(received(wholeText("text $it"), FIRST_SESSION), clock.markNow()) }

        store.record(received(wholeText(TEXT_1), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.messages

        result shouldBeEqualTo listOf(kept(TEXT_1)) + (MAX_MESSAGES - 1 downTo 1).map { kept("text $it") }
    }

    @Tag("REQ-016")
    @Tag("REQ-020")
    @Test
    fun `GIVEN another system ID WHEN it sends a text THEN it is not kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(wholeText(TEXT_1), FIRST_SESSION, systemId = MESSAGE_SYSTEM_2), clock.markNow())
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages.empty(FIRST_SESSION)
    }

    @Test
    fun `GIVEN another component of the vehicle WHEN it sends a text THEN it is not kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(wholeText(TEXT_1), FIRST_SESSION, componentId = MAV_COMP_ID_GIMBAL), clock.markNow())
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages.empty(FIRST_SESSION)
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN no active vehicle yet WHEN a text arrives THEN it is not kept`() {
        val text = received(wholeText(TEXT_1), FIRST_SESSION)

        store.record(text, clock.markNow())
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages.empty(FIRST_SESSION)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN texts and a reason kept WHEN a replay starts THEN they are all forgotten`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.startSession(REPLAY_SESSION)
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages.empty(REPLAY_SESSION)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay started WHEN a text of the live session arrives THEN it is dropped`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.startSession(REPLAY_SESSION)

        store.record(received(wholeText(TEXT_1), FIRST_SESSION), clock.markNow())
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages.empty(REPLAY_SESSION)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay WHEN its vehicle sends a text THEN it carries the replay origin`() {
        store.startSession(REPLAY_SESSION)
        activeVehicle.startReplay(REPLAY_SESSION)
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), REPLAY_SESSION)

        store.record(received(wholeText(TEXT_1), REPLAY_SESSION), clock.markNow())
        val result = store.messages.value.messages

        result shouldBeEqualTo listOf(kept(TEXT_1).copy(origin = DataOrigin.REPLAY))
    }

    @Test
    fun `GIVEN a chunked text waiting WHEN the vehicle's next message comes after 1 s THEN it is kept incomplete`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val first = clock.markNow()
        store.record(received(firstChunk(TEXT_1), FIRST_SESSION), first)
        clock += CHUNK_TIME_LIMIT

        store.record(received(heartbeat(armed = false), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.messages

        result shouldBeEqualTo listOf(kept(TEXT_1, at = first).copy(incomplete = true))
    }

    @Test
    fun `GIVEN a pre-arm failure WHEN its text arrives THEN it is the reason too`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT)
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN another kind of text arrives THEN the reason stays`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val first = clock.markNow()
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), first)
        clock += STEP

        store.record(received(wholeText(ARM_TEXT), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT, at = first)
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN a newer one arrives THEN it replaces it`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())
        clock += STEP

        store.record(received(wholeText(OTHER_PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(OTHER_PRE_ARM_TEXT)
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN a HEARTBEAT shows the vehicle armed THEN it is cleared`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.record(received(heartbeat(armed = true), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN a HEARTBEAT shows the vehicle disarmed THEN the reason stays`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.record(received(heartbeat(armed = false), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT)
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN SYS_STATUS reports the checks enabled and healthy THEN it is cleared`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.record(received(preArmStatus(enabled = true, healthy = true), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN SYS_STATUS reports the checks enabled and not healthy THEN the reason stays`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.record(received(preArmStatus(enabled = true, healthy = false), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT)
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN SYS_STATUS reports healthy checks not enabled THEN the reason stays`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.record(received(preArmStatus(enabled = false, healthy = true), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT)
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a pre-arm reason WHEN another system ID reports armed THEN the reason stays`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.record(received(heartbeat(armed = true), FIRST_SESSION, systemId = MESSAGE_SYSTEM_2), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT)
    }

    @Test
    fun `GIVEN a chunked pre-arm text waiting WHEN an armed HEARTBEAT closes it after 1 s THEN no reason is held`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(firstChunk(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())
        clock += CHUNK_TIME_LIMIT

        store.record(received(heartbeat(armed = true), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a chunked pre-arm text waiting WHEN it closes incomplete THEN it is the reason, marked incomplete`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val first = clock.markNow()
        store.record(received(firstChunk(PRE_ARM_TEXT), FIRST_SESSION), first)
        clock += CHUNK_TIME_LIMIT

        store.record(received(heartbeat(armed = false), FIRST_SESSION), clock.markNow())
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT, at = first).copy(incomplete = true)
    }

    @Test
    fun `GIVEN a pre-arm reason WHEN 100 newer texts push it out of the list THEN it is still the reason`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val first = clock.markNow()
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), first)

        repeat(MAX_MESSAGES) { store.record(received(wholeText("text $it"), FIRST_SESSION), clock.markNow()) }
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT, at = first)
    }

    @Test
    fun `GIVEN a reason and a chunk waiting WHEN the vehicle reboots THEN both are forgotten, the texts stay`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())
        store.record(received(firstChunk(TEXT_1), FIRST_SESSION), clock.markNow())

        store.rebooted(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        clock += CHUNK_TIME_LIMIT
        store.record(received(heartbeat(armed = false), FIRST_SESSION), clock.markNow())
        val result = store.messages.value

        result shouldBeEqualTo FollowedMessages(
            FIRST_SESSION,
            SystemId(MESSAGE_SYSTEM_1),
            listOf(kept(PRE_ARM_TEXT, at = clock.markNow() - CHUNK_TIME_LIMIT)),
            preArmReason = null,
        )
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a pre-arm reason WHEN another system ID reboots THEN the reason stays`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(wholeText(PRE_ARM_TEXT), FIRST_SESSION), clock.markNow())

        store.rebooted(SystemId(MESSAGE_SYSTEM_2), FIRST_SESSION)
        val result = store.messages.value.preArmReason

        result shouldBeEqualTo kept(PRE_ARM_TEXT)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a chunk waiting WHEN a replay starts THEN it never closes into the replay`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(firstChunk(TEXT_1), FIRST_SESSION), clock.markNow())
        store.startSession(REPLAY_SESSION)
        activeVehicle.startReplay(REPLAY_SESSION)
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), REPLAY_SESSION)
        clock += CHUNK_TIME_LIMIT

        store.record(received(heartbeat(armed = false), REPLAY_SESSION), clock.markNow())
        val result = store.messages.value.messages

        result.shouldBeEmpty()
    }

    private fun firstChunk(text: String) =
        StatusText(MAV_SEVERITY_CRITICAL, text, CHUNKED_ID, chunkSeq = 0, endsInField = false)

    private fun kept(text: String, at: TimeMark = clock.markNow()) =
        VehicleMessage(MessageSeverity.CRITICAL, text, receivedAt = at, origin = DataOrigin.LIVE)

    private companion object {
        const val TEXT_1 = "EKF3 IMU0 is using GPS"
        const val TEXT_2 = "EKF3 IMU1 is using GPS"
        const val ARM_TEXT = "Arm: Throttle too high"
        const val OTHER_PRE_ARM_TEXT = "PreArm: Compass not calibrated"
        const val MAX_MESSAGES = 100
        const val CHUNKED_ID = 3
        val STEP = 1.seconds
        val CHUNK_TIME_LIMIT = 1.seconds
    }
}
