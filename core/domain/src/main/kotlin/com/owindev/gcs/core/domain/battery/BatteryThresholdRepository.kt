package com.owindev.gcs.core.domain.battery

import kotlinx.coroutines.flow.StateFlow

/** The active vehicle's battery failsafe settings (REQ-028). Implemented in `:data:vehicle`, bound there with Hilt. */
interface BatteryThresholdRepository {

    /**
     * The active vehicle's settings in this link session, each unknown until read and all unknown again after it
     * reboots, since they may have changed; [BatteryThresholds.UNKNOWN] while there is no active vehicle. A change made
     * on the vehicle is followed as it announces it. Observing it keeps the link open.
     */
    val activeThresholds: StateFlow<BatteryThresholds>
}
