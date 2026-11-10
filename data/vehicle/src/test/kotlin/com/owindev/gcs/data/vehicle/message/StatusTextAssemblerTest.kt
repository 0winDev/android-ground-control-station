package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.StatusText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-047")
class StatusTextAssemblerTest {

    private val clock = TestTimeSource()
    private val start = clock.markNow()

    private lateinit var assembler: StatusTextAssembler

    @BeforeEach
    fun setUp() {
        assembler = StatusTextAssembler(DataOrigin.LIVE)
    }

    @Test
    fun `GIVEN a message with id 0 WHEN accepted THEN it is a whole message at once`() {
        val statusText = StatusText(CRITICAL, SHORT_TEXT, id = 0, chunkSeq = 0, endsInField = true)

        val result = assembler.accept(statusText, start)

        result shouldBeEqualTo listOf(message(SHORT_TEXT))
    }

    @Test
    fun `GIVEN a message with id 0 filling its field WHEN accepted THEN it is a whole message at once`() {
        val statusText = StatusText(CRITICAL, FULL_FIELD, id = 0, chunkSeq = 0, endsInField = false)

        val result = assembler.accept(statusText, start)

        result shouldBeEqualTo listOf(message(FULL_FIELD))
    }

    @Test
    fun `GIVEN the first chunks WHEN the last one is not received yet THEN nothing is complete`() {
        assembler.accept(chunk(seq = 0, FULL_FIELD), start)

        val result = assembler.accept(chunk(seq = 1, FULL_FIELD), start)

        result.shouldBeEmpty()
    }

    @Test
    fun `GIVEN chunks in order WHEN the one ending in its field arrives THEN they are joined by chunk_seq`() {
        assembler.accept(chunk(seq = 0, FULL_FIELD), start)
        assembler.accept(chunk(seq = 1, FULL_FIELD), start)

        val result = assembler.accept(chunk(seq = 2, SHORT_TEXT, endsInField = true), clock.markNow())

        result shouldBeEqualTo listOf(message(FULL_FIELD + FULL_FIELD + SHORT_TEXT))
    }

    @Test
    fun `GIVEN chunks out of order WHEN the missing one arrives last THEN they are joined by chunk_seq`() {
        assembler.accept(chunk(seq = 2, SHORT_TEXT, endsInField = true), start)
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)

        val result = assembler.accept(chunk(seq = 1, SECOND_FIELD), start)

        result shouldBeEqualTo listOf(message(FIRST_FIELD + SECOND_FIELD + SHORT_TEXT))
    }

    @Test
    fun `GIVEN a text exactly two fields long WHEN its empty last chunk arrives THEN it is complete`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        assembler.accept(chunk(seq = 1, SECOND_FIELD), start)

        val result = assembler.accept(chunk(seq = 2, text = "", endsInField = true), start)

        result shouldBeEqualTo listOf(message(FIRST_FIELD + SECOND_FIELD))
    }

    @Test
    fun `GIVEN a lost chunk WHEN a chunk of another id arrives THEN the pending one is closed incomplete`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        assembler.accept(chunk(seq = 2, SHORT_TEXT, endsInField = true), start)

        val result = assembler.accept(chunk(seq = 0, SHORT_TEXT, id = OTHER_ID, endsInField = true), start)

        result shouldBeEqualTo listOf(message(FIRST_FIELD + SHORT_TEXT, incomplete = true), message(SHORT_TEXT))
    }

    @Test
    fun `GIVEN a pending message WHEN a message with id 0 arrives THEN the pending one is closed first`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)

        val result = assembler.accept(StatusText(CRITICAL, SHORT_TEXT, id = 0, chunkSeq = 0, endsInField = true), start)

        result shouldBeEqualTo listOf(message(FIRST_FIELD, incomplete = true), message(SHORT_TEXT))
    }

    @Test
    fun `GIVEN a pending message WHEN just under 1 s passed since its last chunk THEN it has not expired`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        clock += TIME_LIMIT - 1.milliseconds

        val result = assembler.expired()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a pending message WHEN 1 s passed since its last chunk THEN it expires incomplete`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        clock += TIME_LIMIT / 2
        assembler.accept(chunk(seq = 1, SECOND_FIELD), clock.markNow())
        clock += TIME_LIMIT

        val result = assembler.expired()

        result shouldBeEqualTo message(FIRST_FIELD + SECOND_FIELD, incomplete = true)
    }

    @Test
    fun `GIVEN a message that expired WHEN its late chunk arrives THEN it starts a new pending message`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        clock += TIME_LIMIT
        assembler.expired()

        val result = assembler.accept(chunk(seq = 1, SHORT_TEXT, endsInField = true), start)

        result.shouldBeEmpty()
    }

    @Test
    fun `GIVEN nothing pending WHEN checking for expiry THEN nothing expires`() {
        clock += TIME_LIMIT

        val result = assembler.expired()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a duplicated chunk WHEN the message completes THEN the first copy is kept`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        assembler.accept(chunk(seq = 0, SECOND_FIELD), start)

        val result = assembler.accept(chunk(seq = 1, SHORT_TEXT, endsInField = true), start)

        result shouldBeEqualTo listOf(message(FIRST_FIELD + SHORT_TEXT))
    }

    @Test
    fun `GIVEN the last chunk received WHEN a later chunk_seq arrives THEN it is dropped`() {
        assembler.accept(chunk(seq = 1, SHORT_TEXT, endsInField = true), start)
        assembler.accept(chunk(seq = 2, SECOND_FIELD), start)

        val result = assembler.accept(chunk(seq = 0, FIRST_FIELD), start)

        result shouldBeEqualTo listOf(message(FIRST_FIELD + SHORT_TEXT))
    }

    @Test
    fun `GIVEN a chunk_seq beyond ArduPilot's 6 chunks WHEN accepted THEN nothing is kept for it`() {
        assembler.accept(chunk(seq = 6, SHORT_TEXT, endsInField = true), start)
        clock += TIME_LIMIT

        val result = assembler.expired()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN chunks of another severity WHEN joined THEN the first chunk's severity and time are kept`() {
        assembler.accept(chunk(seq = 0, FIRST_FIELD), start)
        clock += TIME_LIMIT / 2

        val result = assembler.accept(chunk(seq = 1, SHORT_TEXT, endsInField = true, severity = 6), clock.markNow())

        result shouldBeEqualTo listOf(message(FIRST_FIELD + SHORT_TEXT))
    }

    @ParameterizedTest
    @CsvSource("0, EMERGENCY", "1, ALERT", "2, CRITICAL", "3, ERROR", "4, WARNING", "5, NOTICE", "6, INFO", "7, DEBUG")
    fun `GIVEN a MAV_SEVERITY WHEN accepted THEN it keeps its level`(value: Int, severity: MessageSeverity) {
        val statusText = StatusText(value, SHORT_TEXT, id = 0, chunkSeq = 0, endsInField = true)

        val result = assembler.accept(statusText, start)

        result shouldBeEqualTo listOf(message(SHORT_TEXT, severity = severity))
    }

    @ParameterizedTest
    @ValueSource(ints = [8, 255, -1])
    fun `GIVEN a severity outside MAV_SEVERITY WHEN accepted THEN it is unknown`(value: Int) {
        val statusText = StatusText(value, SHORT_TEXT, id = 0, chunkSeq = 0, endsInField = true)

        val result = assembler.accept(statusText, start)

        result shouldBeEqualTo listOf(message(SHORT_TEXT, severity = MessageSeverity.UNKNOWN))
    }

    @Test
    fun `GIVEN a replay's assembler WHEN a message is accepted THEN it carries the replay origin`() {
        val replay = StatusTextAssembler(DataOrigin.REPLAY)

        val result = replay.accept(StatusText(CRITICAL, SHORT_TEXT, id = 0, chunkSeq = 0, endsInField = true), start)

        result shouldBeEqualTo listOf(message(SHORT_TEXT).copy(origin = DataOrigin.REPLAY))
    }

    private fun chunk(
        seq: Int,
        text: String,
        id: Int = CHUNKED_ID,
        endsInField: Boolean = false,
        severity: Int = CRITICAL,
    ) = StatusText(severity, text, id, seq, endsInField)

    private fun message(
        text: String,
        incomplete: Boolean = false,
        severity: MessageSeverity = MessageSeverity.CRITICAL,
    ) = VehicleMessage(severity, text, receivedAt = start, origin = DataOrigin.LIVE, incomplete = incomplete)

    private companion object {
        const val CRITICAL = 2
        const val CHUNKED_ID = 7
        const val OTHER_ID = 8
        const val SHORT_TEXT = "check failed"
        val FULL_FIELD = "x".repeat(50)
        val FIRST_FIELD = "a".repeat(50)
        val SECOND_FIELD = "b".repeat(50)
        val TIME_LIMIT = 1.seconds
    }
}
