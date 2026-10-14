package com.owindev.gcs.core.domain.alert

import kotlinx.coroutines.flow.StateFlow

/** The alerts of the active vehicle (REQ-028). Implemented in `:data:vehicle`, bound in `:app`. */
interface AlertRepository {

    /**
     * The active vehicle's active alerts, most urgent first ([AlertRule]); empty while there is no active vehicle.
     * Observing it keeps the link open, as observing the telemetry does. Nothing remembered is ever shown: once
     * nobody observes it, it starts again from no alerts, so acknowledgements do not outlive the observer.
     */
    val activeAlerts: StateFlow<List<ActiveAlert>>

    /** Marks [alert] as seen while it stays active; an emergency is never acknowledged. */
    fun acknowledge(alert: Alert)
}
