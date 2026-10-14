package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-028")
class AlertRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: AlertRule

    @BeforeEach
    fun setUp() {
        rule = AlertRule()
    }

    @Test
    fun `GIVEN energies around the thresholds WHEN evaluating them THEN low at 20 percent and critical at 10`() {
        val energies = listOf(21, 20, 11, 10)

        val result = energies.map { remaining -> rule.evaluate(NONE, telemetry(battery(remaining = remaining))) }

        result shouldBeEqualTo listOf(NONE, listOf(LOW), listOf(LOW), listOf(CRITICAL))
    }

    @Test
    fun `GIVEN a low battery WHEN the energy comes back THEN clears only 3 points above the threshold`() {
        val energies = listOf(22, 23)

        val result = energies.map { remaining -> rule.evaluate(listOf(LOW), telemetry(battery(remaining = remaining))) }

        result shouldBeEqualTo listOf(listOf(LOW), NONE)
    }

    @Test
    fun `GIVEN a critical battery WHEN the energy comes back THEN is low only 3 points above the threshold`() {
        val energies = listOf(12, 13)

        val result = energies.map { remaining ->
            rule.evaluate(listOf(CRITICAL), telemetry(battery(remaining = remaining)))
        }

        result shouldBeEqualTo listOf(listOf(CRITICAL), listOf(LOW))
    }

    @Test
    fun `GIVEN an acknowledged low battery WHEN it reaches critical THEN is a new alert, not acknowledged`() {
        val previous = listOf(LOW.copy(acknowledged = true))

        val result = rule.evaluate(previous, telemetry(battery(remaining = 10)))

        result shouldBeEqualTo listOf(CRITICAL)
    }

    @Test
    fun `GIVEN an acknowledged low battery WHEN it is still low THEN stays acknowledged`() {
        val previous = listOf(LOW.copy(acknowledged = true))

        val result = rule.evaluate(previous, telemetry(battery(remaining = 15)))

        result shouldBeEqualTo previous
    }

    @Test
    fun `GIVEN an acknowledged alert that cleared WHEN it is raised again THEN is not acknowledged`() {
        val cleared = rule.evaluate(listOf(LOW.copy(acknowledged = true)), telemetry(battery(remaining = 50)))

        val result = rule.evaluate(cleared, telemetry(battery(remaining = 20)))

        result shouldBeEqualTo listOf(LOW)
    }

    @Test
    fun `GIVEN a low battery WHEN the battery goes stale with a full reading THEN stays low`() {
        val telemetry = telemetry(battery(remaining = 100), gps = gps(), receivedAt = clock.markNow())
        clock += BATTERY_STALE_AFTER

        val result = rule.evaluate(listOf(LOW), telemetry)

        result shouldBeEqualTo listOf(LOW, STALE)
    }

    @Test
    fun `GIVEN no alert WHEN the battery goes stale with a critical reading THEN only the staleness is raised`() {
        val telemetry = telemetry(battery(remaining = 5), gps = gps(), receivedAt = clock.markNow())
        clock += BATTERY_STALE_AFTER

        val result = rule.evaluate(NONE, telemetry)

        result shouldBeEqualTo listOf(STALE)
    }

    @Test
    fun `GIVEN a critical battery WHEN the energy becomes unknown THEN stays critical and is unknown`() {
        val telemetry = telemetry(battery(remaining = null))

        val result = rule.evaluate(listOf(CRITICAL), telemetry)

        result shouldBeEqualTo listOf(CRITICAL, UNKNOWN)
    }

    @Test
    fun `GIVEN a vehicle without a battery monitor WHEN evaluating it THEN the battery is unknown`() {
        val telemetry = telemetry(battery(remaining = null, monitored = false))

        val result = rule.evaluate(NONE, telemetry)

        result shouldBeEqualTo listOf(UNKNOWN)
    }

    @Test
    fun `GIVEN no battery received yet WHEN evaluating it THEN the battery is unknown, never OK`() {
        val telemetry = Telemetry(gps = Timed(gps(), clock.markNow()))

        val result = rule.evaluate(NONE, telemetry)

        result shouldBeEqualTo listOf(UNKNOWN)
    }

    @Test
    fun `GIVEN a low battery WHEN the vehicle reports it unhealthy THEN is an emergency and stays low`() {
        val telemetry = telemetry(battery(remaining = 50, healthy = false))

        val result = rule.evaluate(listOf(LOW), telemetry)

        result shouldBeEqualTo listOf(UNHEALTHY, LOW)
    }

    @Test
    fun `GIVEN an unhealthy battery WHEN acknowledging it THEN stays unacknowledged`() {
        val active = rule.evaluate(NONE, telemetry(battery(remaining = 100, healthy = false)))

        val result = rule.acknowledge(active, Alert.BatteryUnhealthy)

        result shouldBeEqualTo listOf(UNHEALTHY)
    }

    @Test
    fun `GIVEN each fix type WHEN evaluating it THEN only a fix without a 3D position is a lost fix`() {
        val fixTypes = listOf(
            GpsFixType.NoGps,
            GpsFixType.NoFix,
            GpsFixType.Fix2d,
            GpsFixType.Fix3d,
            GpsFixType.Dgps,
            GpsFixType.RtkFloat,
            GpsFixType.RtkFixed,
            GpsFixType.Static,
            GpsFixType.Ppp,
            GpsFixType.Unknown(UNDEFINED_FIX_TYPE),
        )

        val result = fixTypes.associateWith { fixType ->
            rule.evaluate(NONE, telemetry(battery(remaining = 100), gps(fixType))).isNotEmpty()
        }

        result shouldBeEqualTo fixTypes.associateWith { it !in FIX_3D }
    }

    @Test
    fun `GIVEN a lost fix WHEN the GPS goes stale with a 3D fix THEN stays lost`() {
        val telemetry = telemetry(battery(remaining = 100), gps(GpsFixType.Fix3d), receivedAt = clock.markNow())
        clock += GPS_STALE_AFTER

        val result = rule.evaluate(listOf(GPS_LOST), telemetry)

        result shouldBeEqualTo listOf(GPS_LOST, STALE)
    }

    @Test
    fun `GIVEN attitude 1 ms under its threshold WHEN evaluating it THEN the telemetry is not stale`() {
        val attitude = Timed(ATTITUDE, clock.markNow())
        clock += ATTITUDE_STALE_AFTER - 1.milliseconds

        val result = rule.evaluate(NONE, telemetry(battery(remaining = 100)).copy(attitude = attitude))

        result shouldBeEqualTo NONE
    }

    @Test
    fun `GIVEN attitude exactly at its threshold WHEN evaluating it THEN the telemetry is stale`() {
        val attitude = Timed(ATTITUDE, clock.markNow())
        clock += ATTITUDE_STALE_AFTER

        val result = rule.evaluate(NONE, telemetry(battery(remaining = 100)).copy(attitude = attitude))

        result shouldBeEqualTo listOf(STALE)
    }

    @Test
    fun `GIVEN alerts of every level WHEN evaluating them THEN the most urgent comes first`() {
        val telemetry = telemetry(battery(remaining = 5), gps(GpsFixType.NoFix)).copy(
            attitude = Timed(ATTITUDE, clock.markNow() - ATTITUDE_STALE_AFTER),
        )

        val result = rule.evaluate(NONE, telemetry)

        result shouldBeEqualTo listOf(CRITICAL, GPS_LOST, STALE)
    }

    @Test
    fun `GIVEN an acknowledged caution WHEN another caution is raised THEN the newest comes first`() {
        val previous = listOf(GPS_LOST.copy(acknowledged = true))

        val result = rule.evaluate(previous, telemetry(battery(remaining = 20), gps(GpsFixType.NoFix)))

        result shouldBeEqualTo listOf(LOW, GPS_LOST.copy(acknowledged = true))
    }

    @Test
    fun `GIVEN active alerts WHEN acknowledging a caution THEN only that one is acknowledged`() {
        val active = listOf(CRITICAL, GPS_LOST, STALE)

        val result = rule.acknowledge(active, Alert.GpsFixLost)

        result shouldBeEqualTo listOf(CRITICAL, GPS_LOST.copy(acknowledged = true), STALE)
    }

    @Test
    fun `GIVEN active alerts WHEN acknowledging an advisory THEN is acknowledged`() {
        val active = listOf(GPS_LOST, STALE)

        val result = rule.acknowledge(active, Alert.TelemetryStale)

        result shouldBeEqualTo listOf(GPS_LOST, STALE.copy(acknowledged = true))
    }

    @Test
    fun `GIVEN a critical battery WHEN acknowledging it THEN stays unacknowledged`() {
        val active = listOf(CRITICAL)

        val result = rule.acknowledge(active, Alert.BatteryCritical)

        result shouldBeEqualTo listOf(CRITICAL)
    }

    @Test
    fun `GIVEN custom thresholds WHEN evaluating an energy THEN uses them`() {
        val custom = AlertRule(StalenessRule(), BatteryThresholds(low = 30, critical = 15, hysteresis = 5))

        val result = listOf(31, 30, 15).map { remaining -> custom.evaluate(NONE, telemetry(battery(remaining))) }

        result shouldBeEqualTo listOf(NONE, listOf(LOW), listOf(CRITICAL))
    }

    @Test
    fun `GIVEN a critical threshold not below the low one WHEN creating the thresholds THEN is rejected`() {
        val creating = { BatteryThresholds(low = 20, critical = 20) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN no hysteresis WHEN creating the thresholds THEN is rejected`() {
        val creating = { BatteryThresholds(hysteresis = 0) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a low threshold whose hysteresis passes 100 percent WHEN creating the thresholds THEN is rejected`() {
        val creating = { BatteryThresholds(low = 98, hysteresis = 3) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    /** Fresh battery and GPS, received [receivedAt]. */
    private fun telemetry(battery: Battery, gps: GpsStatus = gps(), receivedAt: TimeMark = clock.markNow()) =
        Telemetry(battery = Timed(battery, receivedAt), gps = Timed(gps, receivedAt))
}

private val NONE = emptyList<ActiveAlert>()
private val LOW = ActiveAlert(Alert.BatteryLow)
private val CRITICAL = ActiveAlert(Alert.BatteryCritical)
private val UNHEALTHY = ActiveAlert(Alert.BatteryUnhealthy)
private val UNKNOWN = ActiveAlert(Alert.BatteryUnknown)
private val GPS_LOST = ActiveAlert(Alert.GpsFixLost)
private val STALE = ActiveAlert(Alert.TelemetryStale)

private val ATTITUDE_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.ATTITUDE)
private val GPS_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.GPS)
private val BATTERY_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.BATTERY)

/** `GPS_FIX_TYPE` ends at 8 (`GPS_FIX_TYPE_PPP`) in `common.xml`. */
private const val UNDEFINED_FIX_TYPE = 9

private val FIX_3D = setOf(
    GpsFixType.Fix3d,
    GpsFixType.Dgps,
    GpsFixType.RtkFloat,
    GpsFixType.RtkFixed,
    GpsFixType.Static,
    GpsFixType.Ppp,
)

private val ATTITUDE = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)

private fun battery(remaining: Int?, monitored: Boolean = true, healthy: Boolean = true) = Battery(
    voltage = 12.6,
    current = 0.0,
    remaining = remaining,
    monitored = monitored,
    healthy = healthy,
)

private fun gps(fixType: GpsFixType = GpsFixType.Fix3d) = GpsStatus(
    fixType = fixType,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)
