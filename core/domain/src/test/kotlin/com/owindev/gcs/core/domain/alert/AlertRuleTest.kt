package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.LandedState
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-028")
class AlertRuleTest {

    private val clock = TestTimeSource()
    private var raised = PREVIOUS

    /** Evaluated long enough that any value never received is late, unless a test says otherwise. */
    private val evaluatingSince = clock.markNow() - SETTLED

    private lateinit var rule: AlertRule

    @BeforeEach
    fun setUp() {
        rule = AlertRule(timeSource = clock)
    }

    @Test
    fun `GIVEN energies around the thresholds WHEN evaluating them THEN low at 20 percent and critical at 10`() {
        val energies = listOf(21, 20, 11, 10)

        val result = energies.map { remaining ->
            rule.evaluate(NONE, telemetry(battery(remaining = remaining)), ARMED, evaluatingSince, ::nextOccurrence)
        }

        result shouldBeEqualTo listOf(NONE, listOf(LOW.raised(1)), listOf(LOW.raised(2)), listOf(CRITICAL.raised(3)))
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN telemetry from a replay WHEN an alert is raised THEN it is marked as from the replay`() {
        val replayed = telemetry(battery(remaining = 20)).copy(origin = DataOrigin.REPLAY)

        val result = rule.evaluate(NONE, replayed, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(LOW.raised(1).copy(origin = DataOrigin.REPLAY))
    }

    @Test
    fun `GIVEN a low battery WHEN the energy comes back THEN clears only 3 points above the threshold`() {
        val energies = listOf(22, 23)

        val result = energies.map { remaining ->
            rule.evaluate(
                listOf(LOW.raised(PREVIOUS)),
                telemetry(battery(remaining = remaining)),
                ARMED,
                evaluatingSince,
                ::nextOccurrence,
            )
        }

        result shouldBeEqualTo listOf(listOf(LOW.raised(PREVIOUS)), NONE)
    }

    @Test
    fun `GIVEN a critical battery WHEN the energy comes back THEN is low only 3 points above the threshold`() {
        val energies = listOf(12, 13)

        val result = energies.map { remaining ->
            rule.evaluate(
                listOf(CRITICAL.raised(PREVIOUS)),
                telemetry(battery(remaining = remaining)),
                ARMED,
                evaluatingSince,
                ::nextOccurrence,
            )
        }

        result shouldBeEqualTo listOf(listOf(CRITICAL.raised(PREVIOUS)), listOf(LOW.raised(1)))
    }

    @Test
    fun `GIVEN a critical battery WHEN the energy comes back past the low threshold THEN clears 3 points above it`() {
        val energies = listOf(21, 22, 23)

        val result = energies.map { remaining ->
            rule.evaluate(
                listOf(CRITICAL.raised(PREVIOUS)),
                telemetry(battery(remaining = remaining)),
                ARMED,
                evaluatingSince,
                ::nextOccurrence,
            )
        }

        result shouldBeEqualTo listOf(listOf(LOW.raised(1)), listOf(LOW.raised(2)), NONE)
    }

    @Test
    fun `GIVEN the ends of the energy range WHEN evaluating them THEN critical at 0 and nothing at 100`() {
        val energies = listOf(0, 100)

        val result = energies.map { remaining ->
            rule.evaluate(NONE, telemetry(battery(remaining = remaining)), ARMED, evaluatingSince, ::nextOccurrence)
        }

        result shouldBeEqualTo listOf(listOf(CRITICAL.raised(1)), NONE)
    }

    @Test
    fun `GIVEN an acknowledged low battery WHEN it reaches critical THEN is a new alert, not acknowledged`() {
        val previous = listOf(LOW.raised(PREVIOUS, acknowledged = true))

        val result = rule.evaluate(
            previous,
            telemetry(battery(remaining = 10)),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(CRITICAL.raised(1))
    }

    @Test
    fun `GIVEN an acknowledged low battery WHEN it is still low THEN stays acknowledged`() {
        val previous = listOf(LOW.raised(PREVIOUS, acknowledged = true))

        val result = rule.evaluate(
            previous,
            telemetry(battery(remaining = 15)),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo previous
    }

    @Test
    fun `GIVEN an acknowledged alert that cleared WHEN it is raised again THEN is a new one, not acknowledged`() {
        val previous = listOf(LOW.raised(PREVIOUS, acknowledged = true))
        val cleared = rule.evaluate(
            previous,
            telemetry(battery(remaining = 50)),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        val result = rule.evaluate(
            cleared,
            telemetry(battery(remaining = 20)),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(LOW.raised(1))
    }

    @Test
    fun `GIVEN a low battery WHEN the battery goes stale with a full reading THEN stays low`() {
        val telemetry = telemetry(battery(remaining = 100), gps = gps(), receivedAt = clock.markNow())
        clock += BATTERY_STALE_AFTER

        val result = rule.evaluate(listOf(LOW.raised(PREVIOUS)), telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(
            LOW.raised(PREVIOUS),
            UNKNOWN.raised(1),
            STALE_BATTERY.raised(2),
            STALE_GPS.raised(3),
        )
    }

    @Test
    fun `GIVEN no alert WHEN the battery goes stale with a critical reading THEN is unknown, not critical`() {
        val telemetry = telemetry(battery(remaining = 5), gps = gps(), receivedAt = clock.markNow())
        clock += BATTERY_STALE_AFTER

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNKNOWN.raised(1), STALE_BATTERY.raised(2), STALE_GPS.raised(3))
    }

    @Test
    fun `GIVEN a battery with no alert WHEN only its reading goes stale THEN is unknown, never OK`() {
        val battery = Timed(battery(remaining = 50), clock.markNow() - BATTERY_STALE_AFTER)

        val result = rule.evaluate(
            NONE,
            Telemetry(battery = battery, gps = Timed(gps(), clock.markNow()), origin = DataOrigin.LIVE),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(UNKNOWN.raised(1), STALE_BATTERY.raised(2))
    }

    @Test
    fun `GIVEN acknowledged stale attitude WHEN the battery goes stale too THEN is a new alert, not acknowledged`() {
        val previous = listOf(STALE_ATTITUDE.raised(PREVIOUS, acknowledged = true))
        val telemetry = Telemetry(
            attitude = Timed(ATTITUDE, clock.markNow() - ATTITUDE_STALE_AFTER),
            battery = Timed(battery(remaining = 50), clock.markNow() - BATTERY_STALE_AFTER),
            gps = Timed(gps(), clock.markNow()),
            origin = DataOrigin.LIVE,
        )

        val result = rule.evaluate(previous, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(
            UNKNOWN.raised(1),
            STALE_BATTERY.raised(2),
            STALE_ATTITUDE.raised(PREVIOUS, acknowledged = true),
        )
    }

    @Test
    fun `GIVEN a critical battery WHEN the energy becomes unknown THEN stays critical and is unknown`() {
        val telemetry = telemetry(battery(remaining = null))

        val result = rule.evaluate(
            listOf(CRITICAL.raised(PREVIOUS)),
            telemetry,
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(CRITICAL.raised(PREVIOUS), UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN a vehicle without a battery monitor WHEN evaluating it THEN the battery is unknown`() {
        val telemetry = telemetry(battery(remaining = null, monitored = false))

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN a monitored battery without a voltage WHEN evaluating it THEN the battery is unknown, never OK`() {
        val telemetry = telemetry(battery(remaining = 50, voltage = null))

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN no battery received yet WHEN evaluating it THEN the battery is unknown, never OK`() {
        val telemetry = Telemetry(gps = Timed(gps(), clock.markNow()), origin = DataOrigin.LIVE)

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN a low battery WHEN the vehicle reports it unhealthy THEN is an emergency and stays low`() {
        val telemetry = telemetry(battery(remaining = 15, healthy = false))

        val result = rule.evaluate(listOf(LOW.raised(PREVIOUS)), telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(1), LOW.raised(PREVIOUS))
    }

    @Test
    fun `GIVEN a battery failsafe WHEN the battery keeps draining to critical THEN is critical too`() {
        val telemetry = telemetry(battery(remaining = 10, healthy = false))

        val result = rule.evaluate(
            listOf(UNHEALTHY.raised(PREVIOUS)),
            telemetry,
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(CRITICAL.raised(1), UNHEALTHY.raised(PREVIOUS))
    }

    @Test
    fun `GIVEN a low battery WHEN an unhealthy monitor sends no remaining energy THEN stays low`() {
        val telemetry = telemetry(battery(remaining = null, healthy = false))

        val result = rule.evaluate(listOf(LOW.raised(PREVIOUS)), telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(2), LOW.raised(PREVIOUS), UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN no alert WHEN the vehicle reports a critical battery unhealthy THEN both emergencies are raised`() {
        val telemetry = telemetry(battery(remaining = 5, healthy = false))

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(1), CRITICAL.raised(2))
    }

    @Test
    fun `GIVEN the boot battery without a reading WHEN the vehicle is disarmed THEN is unknown, not an emergency`() {
        val telemetry = telemetry(BOOT_BATTERY)

        val result = rule.evaluate(NONE, telemetry, DISARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN the boot battery without a reading WHEN the vehicle is armed THEN is an emergency`() {
        val telemetry = telemetry(BOOT_BATTERY)

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(2), UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN a healthy reading WHEN the armed vehicle's monitor fails with no reading THEN is an emergency`() {
        val healthy = rule.evaluate(NONE, telemetry(battery(remaining = 80)), ARMED, evaluatingSince, ::nextOccurrence)

        val result = rule.evaluate(healthy, telemetry(BOOT_BATTERY), ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(2), UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN a monitor failure seen armed WHEN the vehicle disarms with no reading THEN stays an emergency`() {
        val previous = listOf(UNHEALTHY.raised(PREVIOUS), UNKNOWN.raised(PREVIOUS))

        val result = rule.evaluate(previous, telemetry(BOOT_BATTERY), DISARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo previous
    }

    @Test
    fun `GIVEN a battery failsafe on a disarmed vehicle WHEN the voltage keeps arriving THEN is an emergency`() {
        val telemetry = telemetry(battery(remaining = 50, healthy = false))

        val result = rule.evaluate(NONE, telemetry, DISARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(1))
    }

    @Test
    fun `GIVEN an unhealthy battery WHEN acknowledging it THEN stays unacknowledged`() {
        val active = rule.evaluate(
            NONE,
            telemetry(battery(remaining = 100, healthy = false)),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        val result = rule.acknowledge(active, occurrence = 1)

        result shouldBeEqualTo listOf(UNHEALTHY.raised(1))
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
            rule.evaluate(NONE, telemetry(battery(remaining = 100), gps(fixType)), ARMED, evaluatingSince) { 1 }
        }

        result shouldBeEqualTo fixTypes.associateWith { if (it in FIX_3D) NONE else listOf(GPS_LOST.raised(1)) }
    }

    @Test
    fun `GIVEN no GPS received yet WHEN evaluating it THEN the fix is lost, never OK`() {
        val telemetry = Telemetry(battery = Timed(battery(remaining = 100), clock.markNow()), origin = DataOrigin.LIVE)

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(GPS_LOST.raised(1))
    }

    @Test
    fun `GIVEN a lost fix WHEN the GPS goes stale with a 3D fix THEN stays lost`() {
        val telemetry = telemetry(battery(remaining = 100), gps(GpsFixType.Fix3d), receivedAt = clock.markNow())
        clock += GPS_STALE_AFTER

        val result = rule.evaluate(
            listOf(GPS_LOST.raised(PREVIOUS)),
            telemetry,
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(GPS_LOST.raised(PREVIOUS), STALE_GPS.raised(1))
    }

    @Tag("REQ-052")
    @Test
    fun `GIVEN a stale landed state WHEN evaluating it THEN raises no alert`() {
        val landed = Timed(LandedState.ON_GROUND, clock.markNow())
        clock += LANDED_STALE_AFTER

        val result = rule.evaluate(
            NONE,
            telemetry(battery(remaining = 100)).copy(landed = landed),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo NONE
    }

    @Test
    fun `GIVEN attitude 1 ms under its threshold WHEN evaluating it THEN the telemetry is not stale`() {
        val attitude = Timed(ATTITUDE, clock.markNow())
        clock += ATTITUDE_STALE_AFTER - 1.milliseconds

        val result = rule.evaluate(
            NONE,
            telemetry(battery(remaining = 100)).copy(attitude = attitude),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo NONE
    }

    @Test
    fun `GIVEN attitude exactly at its threshold WHEN evaluating it THEN the telemetry is stale`() {
        val attitude = Timed(ATTITUDE, clock.markNow())
        clock += ATTITUDE_STALE_AFTER

        val result = rule.evaluate(
            NONE,
            telemetry(battery(remaining = 100)).copy(attitude = attitude),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(STALE_ATTITUDE.raised(1))
    }

    @Test
    fun `GIVEN fresh attitude WHEN asking when to evaluate again THEN is when it goes stale`() {
        val attitude = Timed(ATTITUDE, clock.markNow())
        clock += 300.milliseconds

        val result = rule.untilNextEvaluation(
            NONE,
            telemetry(battery(remaining = 100)).copy(attitude = attitude),
            evaluatingSince,
        )

        result shouldBeEqualTo ATTITUDE_STALE_AFTER - 300.milliseconds
    }

    @Test
    fun `GIVEN nothing received since evaluating WHEN asking when to evaluate again THEN is when the GPS is late`() {
        val since = clock.markNow()
        clock += 500.milliseconds

        val result = rule.untilNextEvaluation(NONE, Telemetry(origin = DataOrigin.LIVE), since)

        result shouldBeEqualTo GPS_STALE_AFTER - 500.milliseconds
    }

    @Test
    fun `GIVEN no battery received WHEN 1 ms is left to its threshold since evaluating THEN raises nothing yet`() {
        val since = clock.markNow()
        clock += BATTERY_STALE_AFTER - 1.milliseconds

        val result = rule.evaluate(
            NONE,
            Telemetry(gps = Timed(gps(), clock.markNow()), origin = DataOrigin.LIVE),
            ARMED,
            since,
            ::nextOccurrence,
        )

        result shouldBeEqualTo NONE
    }

    @Test
    fun `GIVEN no battery received WHEN its threshold passes since evaluating THEN the battery is unknown`() {
        val since = clock.markNow()
        clock += BATTERY_STALE_AFTER

        val result = rule.evaluate(
            NONE,
            Telemetry(gps = Timed(gps(), clock.markNow()), origin = DataOrigin.LIVE),
            ARMED,
            since,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(UNKNOWN.raised(1))
    }

    @Test
    fun `GIVEN no GPS received WHEN 1 ms is left to its threshold since evaluating THEN raises nothing yet`() {
        val since = clock.markNow()
        clock += GPS_STALE_AFTER - 1.milliseconds

        val result = rule.evaluate(
            NONE,
            Telemetry(battery = Timed(battery(100), clock.markNow()), origin = DataOrigin.LIVE),
            ARMED,
            since,
            ::nextOccurrence,
        )

        result shouldBeEqualTo NONE
    }

    @Test
    fun `GIVEN no GPS received WHEN its threshold passes since evaluating THEN the fix is lost`() {
        val since = clock.markNow()
        clock += GPS_STALE_AFTER

        val result = rule.evaluate(
            NONE,
            Telemetry(battery = Timed(battery(100), clock.markNow()), origin = DataOrigin.LIVE),
            ARMED,
            since,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(GPS_LOST.raised(1))
    }

    @Test
    fun `GIVEN stale attitude raised 1 ms under the hold WHEN attitude is fresh again THEN stays stale`() {
        val previous = listOf(STALE_ATTITUDE.raised(PREVIOUS, raisedAt = clock.markNow()))
        clock += AlertRule.STALE_MINIMUM_HOLD - 1.milliseconds
        val telemetry = telemetry(battery(remaining = 100)).copy(attitude = Timed(ATTITUDE, clock.markNow()))

        val result = rule.evaluate(previous, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo previous
    }

    @Test
    fun `GIVEN stale attitude raised exactly the hold ago WHEN attitude is fresh again THEN clears`() {
        val previous = listOf(STALE_ATTITUDE.raised(PREVIOUS, raisedAt = clock.markNow()))
        clock += AlertRule.STALE_MINIMUM_HOLD
        val telemetry = telemetry(battery(remaining = 100)).copy(attitude = Timed(ATTITUDE, clock.markNow()))

        val result = rule.evaluate(previous, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo NONE
    }

    @Test
    fun `GIVEN an acknowledged stale stream held WHEN it is fresh again THEN stays acknowledged`() {
        val previous = listOf(STALE_ATTITUDE.raised(PREVIOUS, acknowledged = true, raisedAt = clock.markNow()))
        val telemetry = telemetry(battery(remaining = 100)).copy(attitude = Timed(ATTITUDE, clock.markNow()))

        val result = rule.evaluate(previous, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo previous
    }

    @Test
    fun `GIVEN a held stale alert WHEN asking when to evaluate again THEN is when its hold ends`() {
        val active = listOf(STALE_ATTITUDE.raised(PREVIOUS, raisedAt = clock.markNow()))
        clock += 500.milliseconds

        val result = rule.untilNextEvaluation(active, telemetry(battery(remaining = 100)), evaluatingSince)

        result shouldBeEqualTo AlertRule.STALE_MINIMUM_HOLD - 500.milliseconds
    }

    @Test
    fun `GIVEN a stale alert held past its hold WHEN asking when to evaluate again THEN skips the past deadline`() {
        val active = listOf(STALE_ATTITUDE.raised(PREVIOUS, raisedAt = clock.markNow()))
        clock += AlertRule.STALE_MINIMUM_HOLD + 500.milliseconds

        val result = rule.untilNextEvaluation(active, telemetry(battery(remaining = 100)), evaluatingSince)

        result shouldBeEqualTo GPS_STALE_AFTER
    }

    @Test
    fun `GIVEN alerts of every level WHEN evaluating them THEN the most urgent comes first`() {
        val telemetry = telemetry(battery(remaining = 5), gps(GpsFixType.NoFix)).copy(
            attitude = Timed(ATTITUDE, clock.markNow() - ATTITUDE_STALE_AFTER),
        )

        val result = rule.evaluate(NONE, telemetry, ARMED, evaluatingSince, ::nextOccurrence)

        result shouldBeEqualTo listOf(CRITICAL.raised(1), GPS_LOST.raised(2), STALE_ATTITUDE.raised(3))
    }

    @Test
    fun `GIVEN an acknowledged caution WHEN another caution is raised THEN the newest comes first`() {
        val previous = listOf(GPS_LOST.raised(PREVIOUS, acknowledged = true))

        val result = rule.evaluate(
            previous,
            telemetry(battery(remaining = 20), gps(GpsFixType.NoFix)),
            ARMED,
            evaluatingSince,
            ::nextOccurrence,
        )

        result shouldBeEqualTo listOf(LOW.raised(1), GPS_LOST.raised(PREVIOUS, acknowledged = true))
    }

    @Test
    fun `GIVEN active alerts WHEN acknowledging a caution THEN only that one is acknowledged`() {
        val active = listOf(CRITICAL.raised(1), GPS_LOST.raised(2), STALE_GPS.raised(3))

        val result = rule.acknowledge(active, occurrence = 2)

        result shouldBeEqualTo listOf(CRITICAL.raised(1), GPS_LOST.raised(2, acknowledged = true), STALE_GPS.raised(3))
    }

    @Test
    fun `GIVEN active alerts WHEN acknowledging a stale stream THEN only that stream is acknowledged`() {
        val active = listOf(GPS_LOST.raised(1), STALE_ATTITUDE.raised(2), STALE_GPS.raised(3))

        val result = rule.acknowledge(active, occurrence = 3)

        result shouldBeEqualTo listOf(
            GPS_LOST.raised(1),
            STALE_ATTITUDE.raised(2),
            STALE_GPS.raised(3, acknowledged = true),
        )
    }

    @Test
    fun `GIVEN a critical battery WHEN acknowledging it THEN stays unacknowledged`() {
        val active = listOf(CRITICAL.raised(1))

        val result = rule.acknowledge(active, occurrence = 1)

        result shouldBeEqualTo listOf(CRITICAL.raised(1))
    }

    @Test
    fun `GIVEN a low battery raised again WHEN acknowledging the occurrence seen before THEN the new one is not`() {
        val active = listOf(LOW.raised(2))

        val result = rule.acknowledge(active, occurrence = 1)

        result shouldBeEqualTo listOf(LOW.raised(2))
    }

    @Test
    fun `GIVEN custom thresholds WHEN evaluating an energy THEN uses them`() {
        val custom = AlertRule(StalenessRule(), BatteryThresholds(low = 30, critical = 15, hysteresis = 5), clock)

        val result = listOf(31, 30, 15).map { remaining ->
            custom.evaluate(NONE, telemetry(battery(remaining)), ARMED, evaluatingSince, ::nextOccurrence)
        }

        result shouldBeEqualTo listOf(NONE, listOf(LOW.raised(1)), listOf(CRITICAL.raised(2)))
    }

    @Test
    fun `GIVEN a critical threshold not below the low one WHEN creating the thresholds THEN is rejected`() {
        val creating = { BatteryThresholds(low = 20, critical = 20) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a negative critical threshold WHEN creating the thresholds THEN is rejected`() {
        val creating = { BatteryThresholds(critical = -1) }

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

    /** Active as [occurrence], raised now unless [raisedAt] says otherwise. */
    private fun Alert.raised(occurrence: Long, acknowledged: Boolean = false, raisedAt: TimeMark = clock.markNow()) =
        ActiveAlert(
            alert = this,
            occurrence = occurrence,
            raisedAt = raisedAt,
            acknowledged = acknowledged,
            origin = DataOrigin.LIVE,
        )

    /** The next occurrence raised in this test: 1, 2, 3, after the [PREVIOUS] ones. */
    private fun nextOccurrence(): Long = ++raised

    /** Fresh battery and GPS, received [receivedAt]. */
    private fun telemetry(battery: Battery, gps: GpsStatus = gps(), receivedAt: TimeMark = clock.markNow()) =
        Telemetry(battery = Timed(battery, receivedAt), gps = Timed(gps, receivedAt), origin = DataOrigin.LIVE)
}

private val NONE = emptyList<ActiveAlert>()
private val LANDED_STALE_AFTER = 1.seconds
private val LOW = Alert.BatteryLow
private val CRITICAL = Alert.BatteryCritical
private val UNHEALTHY = Alert.BatteryUnhealthy
private val UNKNOWN = Alert.BatteryUnknown
private val GPS_LOST = Alert.GpsFixLost
private val STALE_ATTITUDE = Alert.TelemetryStale(TelemetryKind.ATTITUDE)
private val STALE_BATTERY = Alert.TelemetryStale(TelemetryKind.BATTERY)
private val STALE_GPS = Alert.TelemetryStale(TelemetryKind.GPS)

private const val ARMED = true
private const val DISARMED = false

/**
 * The battery TelemetryMapper maps the real boot frame `truncated/sys_status.bin` to (TelemetryMapperTest): the
 * monitor enabled but not healthy, with no voltage, current or remaining energy.
 */
private val BOOT_BATTERY = Battery(voltage = null, current = null, remaining = null, monitored = true, healthy = false)

/** Longer than any staleness threshold. */
private val SETTLED = 1.minutes

/** The occurrence of an alert raised before the evaluation under test. */
private const val PREVIOUS = 0L

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

private fun battery(remaining: Int?, monitored: Boolean = true, healthy: Boolean = true, voltage: Double? = 12.6) =
    Battery(
        voltage = voltage,
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
