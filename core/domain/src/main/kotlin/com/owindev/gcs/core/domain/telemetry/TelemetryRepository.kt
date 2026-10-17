package com.owindev.gcs.core.domain.telemetry

import kotlinx.coroutines.flow.StateFlow

/** The telemetry of the vehicles heard on the link. Implemented in `:data:vehicle` and bound there with Hilt. */
interface TelemetryRepository {

    /**
     * The telemetry of the active vehicle (REQ-020), `null` while there is none. It only ever comes from that
     * vehicle's system ID: telemetry from another vehicle is never merged into it (REQ-016).
     *
     * Observing it keeps the link open, as observing the vehicles does. It only emits when something arrives,
     * so judging staleness is the consumer's job, on its own clock ([StalenessRule]); and a vehicle whose link
     * is lost keeps its last values here, which the consumer must not show as current.
     */
    val activeTelemetry: StateFlow<Telemetry?>
}
