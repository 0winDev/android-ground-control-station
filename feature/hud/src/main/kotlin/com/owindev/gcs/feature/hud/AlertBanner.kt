package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.alert.Alerts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long an alert must have been on the banner before a tap acknowledges it, so a tap meant for the alert before
 * never acknowledges one that took its place in the same instant: about a reaction time.
 */
internal val ACKNOWLEDGE_GUARD: Duration = 1.seconds

/** What the HUD's alert banner shows of the active vehicle's alerts (REQ-028). */
sealed interface AlertBanner {

    /** No banner: nothing is wrong, or nothing has been judged yet, which is never shown as "all clear". */
    data object Hidden : AlertBanner

    /**
     * The most urgent [alert] not yet acknowledged, raised as [occurrence]; [acknowledgeable] unless it is an
     * emergency. [others] is how many more alerts are active, acknowledged or not, and [othersLevel] the most urgent
     * of them, `null` with none: an acknowledged caution is still a caution, even under a newer advisory.
     * [batteryRemaining] is the battery's fresh remaining energy, in percent, for a low or critical battery, or
     * `null` when not known.
     */
    data class Showing(
        val alert: Alert,
        val occurrence: Long,
        val acknowledgeable: Boolean,
        val others: Int,
        val othersLevel: AlertLevel? = null,
        val batteryRemaining: Int? = null,
    ) : AlertBanner

    /**
     * Every one of the [count] active alerts has been acknowledged: still active, so still said, at the [level] of
     * the most urgent of them.
     */
    data class AllAcknowledged(val count: Int, val level: AlertLevel) : AlertBanner
}

/**
 * The banner for [alerts], ordered most urgent first and newest first within a level (`AlertRule`), with the
 * battery's remaining energy from [panel] when it is fresh.
 */
internal fun alertBannerOf(alerts: Alerts, panel: TelemetryPanel): AlertBanner {
    val active = (alerts as? Alerts.Evaluated)?.active.orEmpty()
    val shown = active.firstOrNull { !it.acknowledged }
    return when {
        active.isEmpty() -> AlertBanner.Hidden

        shown == null -> AlertBanner.AllAcknowledged(count = active.size, level = active.maxOf { it.alert.level })

        else -> AlertBanner.Showing(
            alert = shown.alert,
            occurrence = shown.occurrence,
            acknowledgeable = shown.alert.level != AlertLevel.EMERGENCY,
            others = active.size - 1,
            othersLevel = active.filter { it !== shown }.maxOfOrNull { it.alert.level },
            batteryRemaining = shown.batteryRemaining(panel),
        )
    }
}

private fun ActiveAlert.batteryRemaining(panel: TelemetryPanel): Int? {
    if (alert != Alert.BatteryLow && alert != Alert.BatteryCritical) return null
    return panel.battery?.takeUnless(Reading<*>::stale)?.value?.remaining
}
