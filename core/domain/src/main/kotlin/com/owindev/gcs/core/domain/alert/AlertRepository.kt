package com.owindev.gcs.core.domain.alert

import kotlinx.coroutines.flow.StateFlow

/** The alerts of the active vehicle (REQ-028). Implemented in `:data:vehicle` and bound there with Hilt. */
interface AlertRepository {

    /**
     * The active vehicle's alerts, most urgent first ([AlertRule]); [Alerts.NotEvaluated] while there is no active
     * vehicle, never an empty "all clear". Observing it keeps the link open, as observing the telemetry does.
     * Nothing remembered is ever shown: once nobody observes it, it starts again from [Alerts.NotEvaluated], so
     * acknowledgements do not outlive the observer.
     */
    val activeAlerts: StateFlow<Alerts>

    /** Marks [alert] as seen while it stays active; an emergency is never acknowledged. */
    fun acknowledge(alert: Alert)
}
