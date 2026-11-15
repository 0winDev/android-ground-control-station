package com.owindev.gcs.core.domain.preflight

import com.owindev.gcs.core.domain.vehicle.SystemId

/** The pre-flight check, judged when asked (REQ-048). Implemented in `:data:vehicle` and bound there with Hilt. */
interface PreflightRepository {

    /** The check the command service applies to [systemId] right now; judge it again whenever it is shown. */
    fun checkFor(systemId: SystemId): PreflightCheck
}
