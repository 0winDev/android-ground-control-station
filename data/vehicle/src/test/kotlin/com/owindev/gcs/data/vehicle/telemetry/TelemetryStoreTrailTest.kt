package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Trail
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.SessionId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The rule itself is covered in `TrailRuleTest`; here, which positions reach it and whose trail they extend. */
@Tag("REQ-035")
class TelemetryStoreTrailTest {

    private val clock = TestTimeSource()

    private lateinit var store: TelemetryStore

    @BeforeEach
    fun setUp() {
        store = TelemetryStore(TrailRule(StalenessRule()))
    }

    @Test
    fun `GIVEN a fresh 3D fix WHEN positions 2·1 m apart arrive THEN the vehicle's trail holds them`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_3D))

        record(SYSTEM_1, trailPosition(step = 0))
        clock += POSITION_PERIOD
        record(SYSTEM_1, trailPosition(step = 1))
        val result = store.trails.value

        result shouldBeEqualTo
            mapOf(SystemId(SYSTEM_1) to Trail(listOf(listOf(trailPoint(0), trailPoint(1))), open = true))
    }

    @Test
    fun `GIVEN no 3D fix WHEN positions arrive THEN no trail is kept`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_2D))

        record(SYSTEM_1, trailPosition(step = 0))
        clock += POSITION_PERIOD
        record(SYSTEM_1, trailPosition(step = 1))
        val result = store.trails.value

        result shouldBeEqualTo emptyMap()
    }

    @Test
    fun `GIVEN a GPS that stopped reporting WHEN a position arrives THEN closes the segment without it`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_3D))
        record(SYSTEM_1, trailPosition(step = 0))
        clock += GPS_SILENCE

        record(SYSTEM_1, trailPosition(step = 1))
        val result = store.trails.value

        result shouldBeEqualTo mapOf(SystemId(SYSTEM_1) to Trail(listOf(listOf(trailPoint(0))), open = false))
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN two vehicles WHEN each sends its positions THEN each keeps its own trail`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_3D))
        record(SYSTEM_2, trailGps(TRAIL_FIX_3D))

        record(SYSTEM_1, trailPosition(step = 0))
        record(SYSTEM_2, trailPosition(step = 5))
        val result = store.trails.value

        result shouldBeEqualTo mapOf(
            SystemId(SYSTEM_1) to Trail(listOf(listOf(trailPoint(0))), open = true),
            SystemId(SYSTEM_2) to Trail(listOf(listOf(trailPoint(5))), open = true),
        )
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a live trail WHEN a replay starts THEN it is forgotten and a late live position adds nothing`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_3D))
        record(SYSTEM_1, trailPosition(step = 0))

        store.startSession(REPLAY_SESSION)
        record(SYSTEM_1, trailPosition(step = 1))
        val result = store.trails.value

        result shouldBeEqualTo emptyMap()
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay WHEN its positions arrive THEN its trail starts from them alone`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_3D))
        record(SYSTEM_1, trailPosition(step = 0))
        store.startSession(REPLAY_SESSION)

        record(SYSTEM_1, trailGps(TRAIL_FIX_3D), REPLAY_SESSION)
        record(SYSTEM_1, trailPosition(step = 7), REPLAY_SESSION)
        val result = store.trails.value

        result shouldBeEqualTo mapOf(SystemId(SYSTEM_1) to Trail(listOf(listOf(trailPoint(7))), open = true))
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a trail WHEN the vehicle reboots THEN the trail is kept`() {
        record(SYSTEM_1, trailGps(TRAIL_FIX_3D))
        record(SYSTEM_1, trailPosition(step = 0, timeBootMs = UP_FOR_A_MINUTE))

        record(SYSTEM_1, attitude(timeBootMs = UP_FOR_A_MINUTE))
        record(SYSTEM_1, attitude(timeBootMs = JUST_BOOTED))
        val result = store.trails.value

        result shouldBeEqualTo mapOf(SystemId(SYSTEM_1) to Trail(listOf(listOf(trailPoint(0))), open = true))
    }

    private fun record(systemId: Int, message: MavlinkMessage, session: SessionId = FIRST_SESSION) {
        store.record(trailMessage(systemId, message, session), clock.markNow())
    }
}

private const val SYSTEM_1 = 1
private const val SYSTEM_2 = 2

/** GLOBAL_POSITION_INT's 5 Hz (`TelemetryStream`). */
private val POSITION_PERIOD = 200.milliseconds

/** Past the 2 s GPS staleness threshold (`StalenessRule`), clear of its edge. */
private val GPS_SILENCE = 2.5.seconds

private const val UP_FOR_A_MINUTE = 60_000L
private const val JUST_BOOTED = 1_000L

private fun attitude(timeBootMs: Long) = MavlinkMessage.Attitude(
    timeBootMs = timeBootMs,
    roll = 0f,
    pitch = 0f,
    yaw = 0f,
    rollspeed = 0f,
    pitchspeed = 0f,
    yawspeed = 0f,
)
