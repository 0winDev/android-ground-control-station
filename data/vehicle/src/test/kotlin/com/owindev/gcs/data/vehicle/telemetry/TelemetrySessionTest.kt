package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkFrame
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The whole real SITL session (`derived/session-stream.bin`) through the parser and the mapper. The expected
 * counts are pymavlink's (`session telemetry values`, printed by `tools/fixtures/derive_fixtures.py`): every
 * value the vehicle sent that is not a documented "unknown". The ranges of REQ-031 must drop none of them.
 */
@Tag("REQ-031")
@Tag("REQ-026")
class TelemetrySessionTest {

    private lateinit var mapper: TelemetryMapper

    @BeforeEach
    fun setUp() {
        mapper = TelemetryMapper
    }

    @Test
    fun `GIVEN the real SITL session WHEN mapping every telemetry frame THEN keeps every value pymavlink decodes`() {
        // Like pymavlink's counts: MAVLink 2 frames only (the parser skips MAVLink 1 bytes, as its stats show)
        // from the vehicle (system 1); the mapper keeps its autopilot (component 1).
        val messages = MavlinkParser(MavlinkMessages).parse(readFixture(SESSION_STREAM))
            .filter { it.systemId == VEHICLE_SYSTEM_ID }
            .map(::received)

        val result = messages.mapNotNull { mapper.update(Telemetry(origin = DataOrigin.LIVE), it, RECEIVED_AT) }

        valueCounts(result) shouldBeEqualTo PYMAVLINK_VALUE_COUNTS.toSortedMap()
    }

    private fun received(frame: MavlinkFrame) =
        ReceivedMessage(frame.systemId, frame.componentId, MavlinkDecoder.decode(frame), SENDER)
}

private const val SESSION_STREAM = "derived/session-stream.bin"
private const val VEHICLE_SYSTEM_ID = 1

/** `session telemetry values (pymavlink)` from `derive_fixtures.py`, as listed in the fixtures README. */
private val PYMAVLINK_VALUE_COUNTS = mapOf(
    "position" to 130,
    "heading" to 130,
    "roll" to 260,
    "pitch" to 260,
    "yaw" to 260,
    "airspeed" to 260,
    "groundspeed" to 260,
    "climb" to 260,
    "throttle" to 260,
    "gps" to 325,
    "cog" to 325,
    "gps_yaw" to 0,
)

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_551)
private val RECEIVED_AT = TestTimeSource().markNow()

/** How many of the mapped messages carry each value, with the same keys as pymavlink's counts. */
private fun valueCounts(mapped: List<Telemetry>) = sortedMapOf(
    "position" to mapped.count { it.position != null },
    "heading" to mapped.count { it.position?.value?.heading != null },
    "roll" to mapped.count { it.attitude?.value?.roll != null },
    "pitch" to mapped.count { it.attitude?.value?.pitch != null },
    "yaw" to mapped.count { it.attitude?.value?.yaw != null },
    "airspeed" to mapped.count { it.airData?.value?.airspeed != null },
    "groundspeed" to mapped.count { it.airData?.value?.groundspeed != null },
    "climb" to mapped.count { it.airData?.value?.climbRate != null },
    "throttle" to mapped.count { it.airData?.value?.throttle != null },
    "gps" to mapped.count { it.gps != null },
    "cog" to mapped.count { it.gps?.value?.courseOverGround != null },
    "gps_yaw" to mapped.count { it.gps?.value?.yaw != null },
)
