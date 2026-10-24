package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
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
            othersLevel = AlertLevel.CAUTION,
        )
    }

    @Test
    fun `GIVEN an acknowledged caution first WHEN building the banner THEN shows the advisory, others a caution`() {
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
            othersLevel = AlertLevel.CAUTION,
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

        result shouldBeEqualTo AlertBanner.AllAcknowledged(count = 2, level = AlertLevel.CAUTION)
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

    @Tag("REQ-037")
    @Test
    fun `GIVEN an emergency raised by a replay WHEN building the banner THEN it is marked recorded`() {
        val alerts = Alerts.Evaluated(listOf(raised(Alert.BatteryCritical, occurrence = 1, origin = DataOrigin.REPLAY)))

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.Showing(
            alert = Alert.BatteryCritical,
            occurrence = 1,
            acknowledgeable = false,
            others = 0,
            recorded = true,
        )
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN every replayed alert acknowledged WHEN building the banner THEN the summary is marked recorded`() {
        val alerts = Alerts.Evaluated(
            listOf(raised(Alert.GpsFixLost, occurrence = 1, acknowledged = true, origin = DataOrigin.REPLAY)),
        )

        val result = alertBannerOf(alerts, TelemetryPanel())

        result shouldBeEqualTo AlertBanner.AllAcknowledged(count = 1, level = AlertLevel.CAUTION, recorded = true)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a live alert WHEN building the banner THEN it is never marked recorded`() {
        val alerts = Alerts.Evaluated(listOf(raised(Alert.GpsFixLost, occurrence = 1)))

        val result = alertBannerOf(alerts, TelemetryPanel())

        (result as AlertBanner.Showing).recorded shouldBeEqualTo false
    }

    private fun raised(
        alert: Alert,
        occurrence: Long,
        acknowledged: Boolean = false,
        origin: DataOrigin = DataOrigin.LIVE,
    ) = ActiveAlert(
        alert = alert,
        occurrence = occurrence,
        raisedAt = clock.markNow(),
        acknowledged = acknowledged,
        origin = origin,
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
