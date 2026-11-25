package com.owindev.gcs.core.domain.message

import com.owindev.gcs.core.domain.alert.AlertLevel

/**
 * The alert level a vehicle message is shown at (REQ-047): emergency to error as an emergency, a warning as a caution,
 * the rest as an advisory. An unknown severity is never promoted: it is shown as an advisory.
 */
val MessageSeverity.level: AlertLevel
    get() = when (this) {
        MessageSeverity.EMERGENCY,
        MessageSeverity.ALERT,
        MessageSeverity.CRITICAL,
        MessageSeverity.ERROR,
        -> AlertLevel.EMERGENCY

        MessageSeverity.WARNING -> AlertLevel.CAUTION

        MessageSeverity.NOTICE,
        MessageSeverity.INFO,
        MessageSeverity.DEBUG,
        MessageSeverity.UNKNOWN,
        -> AlertLevel.ADVISORY
    }
