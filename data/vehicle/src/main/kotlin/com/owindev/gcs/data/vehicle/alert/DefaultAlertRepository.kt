package com.owindev.gcs.data.vehicle.alert

import android.util.Log
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertInput
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.alert.AlertState
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.battery.BatteryThresholdRepository
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.time.atLeastRecheck
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.SessionId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
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
 * The active vehicle's alerts, evaluated by [AlertRule] on its telemetry and its battery failsafe settings (REQ-028).
 *
 * The alerts are evaluated whenever the telemetry, the vehicle's armed state or its battery settings change, so a
 * threshold changed in flight is followed at once, and again when a value goes stale or a value never
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
 * hysteresis or acknowledgement from the vehicle before; and so they do when the link goes from live to a replay or
 * back (REQ-037), even for the same system ID, so a live alert never shows in a replay nor a recorded one after it.
 */
// One source per input the rule judges, plus the rule, its clock and its scope; grouping them would only hide that.
@Suppress("LongParameterList")
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultAlertRepository @Inject constructor(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleRepository: VehicleRepository,
    private val batteryThresholdRepository: BatteryThresholdRepository,
    session: LinkSession,
    private val rule: AlertRule,
    private val timeSource: TimeSource.WithComparableMarks,
    @ApplicationScope scope: CoroutineScope,
) : AlertRepository {

    /** Written by the evaluations and by [acknowledge], always through [change]; `null` while not evaluated. */
    private val state = MutableStateFlow<AlertState?>(null)

    /** Never reset, not even when nobody observes, so a stale tap can never acknowledge a later raise. */
    private val occurrences = AtomicLong()

    /** The active system ID is decided from the vehicles' HEARTBEATs, so they are observed with it. */
    private val evaluations: Flow<AlertInput?> = vehicleRepository.vehicles
        .combine(vehicleRepository.activeSystemId) { _, systemId -> systemId }
        .combine(session.session) { systemId, current -> systemId?.let { ActiveIn(current, it) } }
        .distinctUntilChanged()
        .flatMapLatest { active ->
            active?.let { evaluationsOfNewVehicle(it.systemId, it.session.origin) } ?: flowOf(null)
        }

    /**
     * Starts from [Alerts.NotEvaluated], so nothing of the vehicle active before is carried over. Its armed state
     * comes from its HEARTBEAT; a vehicle not heard is taken as armed, so a failing monitor is never played down.
     * Its battery settings are kept as last read while it is LOST, when its battery telemetry is stale anyway, so they
     * never raise or clear an alert then.
     */
    private fun evaluationsOfNewVehicle(systemId: SystemId, origin: DataOrigin): Flow<AlertInput?> = flow {
        emit(null)
        val evaluatingSince = timeSource.markNow()
        // A vehicle of the session before, until the watchdog forgets it, counts as not heard.
        val armed = vehicleRepository.vehicles
            .map { vehicles -> vehicles[systemId]?.takeIf { it.origin == origin }?.state?.armed ?: true }
            .distinctUntilChanged()
        emitAll(
            combine(
                telemetryRepository.activeTelemetry,
                armed,
                batteryThresholdRepository.activeThresholds,
            ) { telemetry, isArmed, battery ->
                telemetry?.let { AlertInput(it, battery, isArmed, evaluatingSince) }
            },
        )
    }

    override val activeAlerts: StateFlow<Alerts> = channelFlow {
        launch {
            try {
                // Each new evaluation cancels the wait of the one before.
                evaluations.collectLatest { evaluation ->
                    if (evaluation == null) {
                        change { null }
                        return@collectLatest
                    }
                    while (true) {
                        val evaluated = change { previous -> evaluate(previous, evaluation) } ?: AlertState()
                        val untilNext = rule.untilNextEvaluation(evaluated, evaluation)
                        delay(untilNext?.atLeastRecheck() ?: awaitCancellation())
                    }
                }
            } finally {
                // Nobody observes them any more: forgotten, not cleared, so nothing is logged. Reset here, once
                // every evaluation has stopped, so none can write its alerts back after it.
                state.value = null
            }
        }
        state.collect { send(it.toAlerts()) }
    }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeout = Duration.ZERO, replayExpiration = Duration.ZERO),
            initialValue = Alerts.NotEvaluated,
        )

    override fun acknowledge(occurrence: Long) {
        change { evaluated -> evaluated?.copy(active = rule.acknowledge(evaluated.active, occurrence)) }
    }

    /**
     * Applies [transform] atomically, so an acknowledgement is never lost to an evaluation, logs the change and
     * returns what it applied.
     */
    private fun change(transform: (AlertState?) -> AlertState?): AlertState? {
        while (true) {
            val before = state.value
            val after = transform(before)
            if (state.compareAndSet(before, after)) {
                logChange(before?.active.orEmpty(), after?.active.orEmpty())
                return after
            }
        }
    }

    private fun evaluate(previous: AlertState?, evaluation: AlertInput): AlertState =
        rule.evaluate(previous ?: AlertState(), evaluation, occurrences::incrementAndGet)

    /** Not evaluated is never shown as all clear ([Alerts.NotEvaluated]). */
    private fun AlertState?.toAlerts(): Alerts = this?.let { Alerts.Evaluated(it.active) } ?: Alerts.NotEvaluated

    private fun logChange(before: List<ActiveAlert>, after: List<ActiveAlert>) {
        val wasActive = before.associateBy(ActiveAlert::alert)
        val active = after.associateBy(ActiveAlert::alert)
        (active.keys - wasActive.keys).forEach { Log.i(TAG, "Alert raised: ${it.describe()}") }
        (wasActive.keys - active.keys).forEach { Log.i(TAG, "Alert cleared: ${it.describe()}") }
        active.values
            .filter { it.acknowledged && wasActive[it.alert]?.acknowledged == false }
            .forEach { Log.i(TAG, "Alert acknowledged: ${it.alert.describe()}") }
    }

    /** `BatteryVoltageLow (caution)`, `TelemetryStale(kind=GPS) (advisory)`. */
    private fun Alert.describe(): String = "$this (${level.name.lowercase()})"

    private data class ActiveIn(val session: SessionId, val systemId: SystemId)

    private companion object {
        const val TAG = "AlertRepository"
    }
}
