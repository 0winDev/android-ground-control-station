package com.owindev.gcs.core.domain.background

import com.owindev.gcs.core.domain.alert.AlertLevel
import kotlinx.coroutines.flow.StateFlow

/**
 * What this device lets the background link do (REQ-046): whether it runs, whether its notification is hidden, and
 * whether the system may stop the app in the background. No warning means only no known restriction, never that the
 * link will survive: other manufacturers' managers are invisible here. The pre-flight check (#102) reads it.
 */
data class BackgroundLinkStatus(
    val running: Boolean = false,
    val notificationHidden: Boolean = false,
    val batteryOptimized: Boolean = false,
    val backgroundRestricted: Boolean = false,
)

/** The background link as the device reports it. Implemented and bound with Hilt in `:app`. */
interface BackgroundLinkRepository {

    val status: StateFlow<BackgroundLinkStatus>
}

/**
 * Why the background link may fail the operator. A restriction the operator set is a caution; battery optimisation
 * is only an advisory, since it is every app's default and the stock system keeps a foreground service running.
 */
enum class BackgroundLinkWarning(val level: AlertLevel) {
    NOTIFICATION_HIDDEN(AlertLevel.ADVISORY),
    BATTERY_OPTIMIZED(AlertLevel.ADVISORY),
    BACKGROUND_RESTRICTED(AlertLevel.CAUTION),
}
