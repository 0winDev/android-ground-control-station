package com.owindev.gcs.data.vehicle.alert

import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.telemetry.TelemetryMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The real boot SYS_STATUS (`truncated/sys_status.bin`: battery monitor enabled, not healthy, 0 mV) through the
 * mapper and the rule, as the app judges it at a vehicle's boot (#56).
 */
@Tag("REQ-028")
class BootBatteryAlertTest {

    private val clock = TestTimeSource()
    private var raised = 0L

    private lateinit var rule: AlertRule

    @BeforeEach
    fun setUp() {
        rule = AlertRule(timeSource = clock)
    }

    @Test
    fun `GIVEN the real boot SYS_STATUS WHEN the vehicle is disarmed THEN the battery is unknown, not an emergency`() {
        val telemetry = bootTelemetry()

        val result = rule.evaluate(emptyList(), telemetry, armed = false, clock.markNow() - SETTLED, ::nextOccurrence)

        result shouldBeEqualTo
            listOf(
                ActiveAlert(Alert.BatteryUnknown, occurrence = 1, raisedAt = clock.markNow(), origin = DataOrigin.LIVE),
            )
    }

    @Test
    fun `GIVEN the real boot SYS_STATUS WHEN the vehicle is armed THEN the battery is an emergency`() {
        val telemetry = bootTelemetry()

        val result = rule.evaluate(emptyList(), telemetry, armed = true, clock.markNow() - SETTLED, ::nextOccurrence)

        result shouldBeEqualTo listOf(
            ActiveAlert(Alert.BatteryUnhealthy, occurrence = 2, raisedAt = clock.markNow(), origin = DataOrigin.LIVE),
            ActiveAlert(Alert.BatteryUnknown, occurrence = 1, raisedAt = clock.markNow(), origin = DataOrigin.LIVE),
        )
    }

    private fun nextOccurrence(): Long = ++raised

    /** The real boot SYS_STATUS mapped as received now, with a fresh 3D GPS fix so only the battery is judged. */
    private fun bootTelemetry(): Telemetry {
        val frame = MavlinkParser(MavlinkMessages).parse(readFixture(SYS_STATUS_BOOT)).single()
        val received = ReceivedMessage(frame.systemId, frame.componentId, MavlinkDecoder.decode(frame), SENDER)
        val now = clock.markNow()
        return checkNotNull(
            TelemetryMapper.update(Telemetry(gps = Timed(GPS_3D_FIX, now), origin = DataOrigin.LIVE), received, now),
        )
    }
}

private const val SYS_STATUS_BOOT = "truncated/sys_status.bin"

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_551)

/** Longer than any staleness threshold, so nothing is waiting for a first value. */
private val SETTLED = 1.minutes

private val GPS_3D_FIX = GpsStatus(
    fixType = GpsFixType.Fix3d,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)
