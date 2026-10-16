package com.owindev.gcs.data.vehicle.alert

import android.util.Log
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The active vehicle's alerts, evaluated by [AlertRule] on its telemetry (REQ-028).
 *
 * The alerts are evaluated whenever the telemetry changes, and again when a value goes stale with nothing new
 * arriving ([AlertRule.untilNextStale]), so a stream that stops raises [Alert.TelemetryStale] on time. Each change
 * is logged once, which is what the SITL check reads.
 *
 * Known limit, as in `DefaultVehicleRepository`: the ages count deep sleep but `delay` does not, so after the
 * device wakes a value that went stale while it slept is judged on the next telemetry or when the timer ends,
 * never early.
 *
 * Nothing remembered is ever shown (as the telemetry, #13): when the last observer leaves the alerts are dropped
 * and the next observer starts from [Alerts.NotEvaluated], judged afresh on the telemetry as it is then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultAlertRepository @Inject constructor(
    telemetryRepository: TelemetryRepository,
    private val rule: AlertRule,
    @ApplicationScope scope: CoroutineScope,
) : AlertRepository {

    /** Written by the evaluations and by [acknowledge], always through [change]. */
    private val alerts = MutableStateFlow<Alerts>(Alerts.NotEvaluated)

    /** Never reset, not even when nobody observes, so a stale tap can never acknowledge a later raise. */
    private val occurrences = AtomicLong()

    private val evaluations: Flow<Telemetry?> = telemetryRepository.activeTelemetry.flatMapLatest { telemetry ->
        flow {
            while (true) {
                emit(telemetry)
                val untilNextStale = telemetry?.let(rule::untilNextStale) ?: awaitCancellation()
                delay(untilNextStale)
            }
        }
    }

    override val activeAlerts: StateFlow<Alerts> = channelFlow {
        launch {
            evaluations.collect { telemetry ->
                change { previous ->
                    if (telemetry == null) {
                        Alerts.NotEvaluated
                    } else {
                        Alerts.Evaluated(rule.evaluate(previous.active(), telemetry, occurrences::incrementAndGet))
                    }
                }
            }
        }
        try {
            alerts.collect(::send)
        } finally {
            // Nobody observes them any more: forgotten, not cleared, so nothing is logged.
            alerts.value = Alerts.NotEvaluated
        }
    }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeout = Duration.ZERO, replayExpiration = Duration.ZERO),
            initialValue = Alerts.NotEvaluated,
        )

    override fun acknowledge(occurrence: Long) {
        change { alerts ->
            if (alerts is Alerts.Evaluated) Alerts.Evaluated(rule.acknowledge(alerts.active, occurrence)) else alerts
        }
    }

    /** Applies [transform] atomically, so an acknowledgement is never lost to an evaluation, and logs the change. */
    private fun change(transform: (Alerts) -> Alerts) {
        while (true) {
            val before = alerts.value
            val after = transform(before)
            if (alerts.compareAndSet(before, after)) {
                logChange(before.active(), after.active())
                return
            }
        }
    }

    /** What is active; nothing when not evaluated, which is never shown as all clear ([Alerts.NotEvaluated]). */
    private fun Alerts.active(): List<ActiveAlert> = (this as? Alerts.Evaluated)?.active.orEmpty()

    private fun logChange(before: List<ActiveAlert>, after: List<ActiveAlert>) {
        val wasActive = before.associateBy(ActiveAlert::alert)
        val active = after.associateBy(ActiveAlert::alert)
        (active.keys - wasActive.keys).forEach { Log.i(TAG, "Alert raised: ${it.describe()}") }
        (wasActive.keys - active.keys).forEach { Log.i(TAG, "Alert cleared: ${it.describe()}") }
        active.values
            .filter { it.acknowledged && wasActive[it.alert]?.acknowledged == false }
            .forEach { Log.i(TAG, "Alert acknowledged: ${it.alert.describe()}") }
    }

    /** `BatteryLow (caution)`, `TelemetryStale(kind=GPS) (advisory)`. */
    private fun Alert.describe(): String = "$this (${level.name.lowercase()})"

    private companion object {
        const val TAG = "AlertRepository"
    }
}
