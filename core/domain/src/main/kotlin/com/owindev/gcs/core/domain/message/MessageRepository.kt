package com.owindev.gcs.core.domain.message

import kotlinx.coroutines.flow.StateFlow

/** The active vehicle's messages (REQ-047). Implemented in `:data:vehicle` and bound there with Hilt. */
interface MessageRepository {

    /**
     * The active vehicle's autopilot messages in this link session, newest first and bounded; empty while there is
     * none. They are kept while the link is listened to, and observing them keeps the link open.
     */
    val activeMessages: StateFlow<List<VehicleMessage>>

    /**
     * The latest `PreArm:` message, `null` once the vehicle arms or reports its pre-arm checks healthy. Informational
     * only, never the pre-arm state: that is SYS_STATUS's `MAV_SYS_STATUS_PREARM_CHECK` (#102), since this text can be
     * lost over UDP, or grow old ([PreArmRule.isCurrent]) while the failure is still active.
     */
    val preArmReason: StateFlow<VehicleMessage?>
}
