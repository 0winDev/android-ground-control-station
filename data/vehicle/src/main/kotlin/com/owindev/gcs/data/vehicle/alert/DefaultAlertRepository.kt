package com.owindev.gcs.data.vehicle.alert

import android.util.Log
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The active vehicle's alerts, evaluated by [AlertRule] on its telemetry (REQ-028).
 *
 * The alerts are evaluated whenever the telemetry changes, and again when a value goes stale or a value never
 * received has had its time to arrive, with nothing new arriving ([AlertRule.untilNextEvaluation]), so a stream that
 * stops raises [Alert.TelemetryStale] on time. That time counts from when the active vehicle's alerts started to be
 * evaluated, on [timeSource]. Each change is logged once, which is what the SITL check reads.
 *
 * Known limit, as in `DefaultVehicleRepository`: the ages count deep sleep but `delay` does not, so after the
 * device wakes a value that went stale while it slept is judged on the next telemetry or when the timer ends,
 * never early.
 *
 * Nothing remembered is ever shown (as the telemetry, #13): when the last observer leaves the alerts are dropped
 * and the next observer starts from [Alerts.NotEvaluated], judged afresh on the telemetry as it is then. Nor is one
 * vehicle's state carried to another: when the active system ID changes, its alerts start afresh, with no
 * hysteresis or acknowledgement from the vehicle before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultAlertRepository @Inject constructor(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleRepository: VehicleRepository,
    private val rule: AlertRule,
    private val timeSource: TimeSource.WithComparableMarks,
    @ApplicationScope scope: CoroutineScope,
) : AlertRepository {

    /** Written by the evaluations and by [acknowledge], always through [change]. */
    private val alerts = MutableStateFlow<Alerts>(Alerts.NotEvaluated)

    /** Never reset, not even when nobody observes, so a stale tap can never acknowledge a later raise. */
    private val occurrences = AtomicLong()

    /** The active system ID is decided from the vehicles' HEARTBEATs, so they are observed with it. */
    private val evaluations: Flow<Evaluation?> = vehicleRepository.vehicles
        .combine(vehicleRepository.activeSystemId) { _, systemId -> systemId }
        .distinctUntilChanged()
        .flatMapLatest { systemId -> if (systemId == null) flowOf(null) else evaluationsOfNewVehicle(systemId) }

    /**
     * Starts from [Alerts.NotEvaluated], so nothing of the vehicle active before is carried over. Its armed state
     * comes from its HEARTBEAT; a vehicle not heard is taken as armed, so a failing monitor is never played down.
     */
    private fun evaluationsOfNewVehicle(systemId: SystemId): Flow<Evaluation?> = flow {
        emit(null)
        val evaluatingSince = timeSource.markNow()
        val armed = vehicleRepository.vehicles.map { it[systemId]?.state?.armed ?: true }.distinctUntilChanged()
        emitAll(
            telemetryRepository.activeTelemetry.combine(armed) { telemetry, isArmed ->
                telemetry?.let { Evaluation(it, isArmed, evaluatingSince) }
            },
        )
    }

    override val activeAlerts: StateFlow<Alerts> = channelFlow {
        launch {
            try {
                // Each new evaluation cancels the wait of the one before.
                evaluations.collectLatest { evaluation ->
                    if (evaluation == null) {
                        change { Alerts.NotEvaluated }
                        return@collectLatest
                    }
                    while (true) {
                        val active = change { previous -> evaluate(previous, evaluation) }.active()
                        val untilNext =
                            rule.untilNextEvaluation(active, evaluation.telemetry, evaluation.evaluatingSince)
                        delay(untilNext ?: awaitCancellation())
                    }
                }
            } finally {
                // Nobody observes them any more: forgotten, not cleared, so nothing is logged. Reset here, once
                // every evaluation has stopped, so none can write its alerts back after it.
                alerts.value = Alerts.NotEvaluated
            }
        }
        alerts.collect(::send)
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

    /**
     * Applies [transform] atomically, so an acknowledgement is never lost to an evaluation, logs the change and
     * returns what it applied.
     */
    private fun change(transform: (Alerts) -> Alerts): Alerts {
        while (true) {
            val before = alerts.value
            val after = transform(before)
            if (alerts.compareAndSet(before, after)) {
                logChange(before.active(), after.active())
                return after
            }
        }
    }

    private fun evaluate(previous: Alerts, evaluation: Evaluation): Alerts = Alerts.Evaluated(
        rule.evaluate(
            previous = previous.active(),
            telemetry = evaluation.telemetry,
            armed = evaluation.armed,
            evaluatingSince = evaluation.evaluatingSince,
            nextOccurrence = occurrences::incrementAndGet,
        ),
    )

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

    /** The active vehicle's [telemetry], whether it is [armed], and since when its alerts have been evaluated. */
    private data class Evaluation(val telemetry: Telemetry, val armed: Boolean, val evaluatingSince: TimeMark)

    private companion object {
        const val TAG = "AlertRepository"
    }
}
