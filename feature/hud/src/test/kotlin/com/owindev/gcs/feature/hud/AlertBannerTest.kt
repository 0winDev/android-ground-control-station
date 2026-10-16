package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-028")
class AlertBannerTest {

    private val clock = TestTimeSource()

    @Test
    fun `GIVEN alerts not evaluated yet WHEN building the banner THEN is hidden, never all clear`() {
        val alerts = Alerts.NotEvaluated

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.Hidden
    }

    @Test
    fun `GIVEN nothing wrong WHEN building the banner THEN is hidden`() {
        val alerts = Alerts.Evaluated(emptyList())

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.Hidden
    }

    @Test
    fun `GIVEN an emergency first WHEN building the banner THEN shows it, not acknowledgeable, with the rest`() {
        val alerts = Alerts.Evaluated(
            listOf(
                raised(Alert.BatteryCritical, occurrence = 3),
                raised(Alert.GpsFixLost, occurrence = 2, acknowledged = true),
                raised(Alert.BatteryUnknown, occurrence = 1),
            ),
        )

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.Showing(
            alert = Alert.BatteryCritical,
            occurrence = 3,
            acknowledgeable = false,
            others = 2,
        )
    }

    @Test
    fun `GIVEN an acknowledged caution first WHEN building the banner THEN shows the next unacknowledged one`() {
        val alerts = Alerts.Evaluated(
            listOf(
                raised(Alert.GpsFixLost, occurrence = 2, acknowledged = true),
                raised(Alert.TelemetryStale(TelemetryKind.ATTITUDE), occurrence = 1),
            ),
        )

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.Showing(
            alert = Alert.TelemetryStale(TelemetryKind.ATTITUDE),
            occurrence = 1,
            acknowledgeable = true,
            others = 1,
        )
    }

    @Test
    fun `GIVEN every alert acknowledged WHEN building the banner THEN says how many are still active`() {
        val alerts = Alerts.Evaluated(
            listOf(
                raised(Alert.BatteryLow, occurrence = 2, acknowledged = true),
                raised(Alert.TelemetryStale(TelemetryKind.GPS), occurrence = 1, acknowledged = true),
            ),
        )

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.AllAcknowledged(count = 2)
    }

    @Test
    fun `GIVEN a low battery with a fresh reading WHEN building the banner THEN says its remaining energy`() {
        val alerts = Alerts.Evaluated(listOf(raised(Alert.BatteryLow, occurrence = 1)))

        val result = alertBannerOf(alerts, TelemetryPanel(battery = Reading(LOW_BATTERY, stale = false, Duration.ZERO)))

        result shouldBeEqualTo AlertBanner.Showing(
            alert = Alert.BatteryLow,
            occurrence = 1,
            acknowledgeable = true,
            others = 0,
            batteryRemaining = LOW_REMAINING,
        )
    }

    @Test
    fun `GIVEN a critical battery with a stale reading WHEN building the banner THEN gives no remaining energy`() {
        val alerts = Alerts.Evaluated(listOf(raised(Alert.BatteryCritical, occurrence = 1)))

        val result = alertBannerOf(alerts, TelemetryPanel(battery = Reading(LOW_BATTERY, stale = true, STALE_AGE)))

        result shouldBeEqualTo AlertBanner.Showing(
            alert = Alert.BatteryCritical,
            occurrence = 1,
            acknowledgeable = false,
            others = 0,
        )
    }

    @Test
    fun `GIVEN an alert other than the energy WHEN building the banner THEN gives no remaining energy`() {
        val alerts = Alerts.Evaluated(listOf(raised(Alert.GpsFixLost, occurrence = 1)))

        val result = alertBannerOf(alerts, TelemetryPanel(battery = Reading(LOW_BATTERY, stale = false, Duration.ZERO)))

        result shouldBeEqualTo AlertBanner.Showing(
            alert = Alert.GpsFixLost,
            occurrence = 1,
            acknowledgeable = true,
            others = 0,
        )
    }

    private fun raised(alert: Alert, occurrence: Long, acknowledged: Boolean = false) = ActiveAlert(
        alert = alert,
        occurrence = occurrence,
        raisedAt = clock.markNow(),
        acknowledged = acknowledged,
    )
}

private const val LOW_REMAINING = 18
private val STALE_AGE = 4.seconds
private val LOW_BATTERY = Battery(
    voltage = 11.1,
    current = 0.0,
    remaining = LOW_REMAINING,
    monitored = true,
    healthy = true,
)
