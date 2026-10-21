package com.owindev.gcs.feature.hud

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.owindev.gcs.core.designsystem.time.elapsedText
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.PositionTrust
import kotlin.time.Duration

/** What the panel says over a position it cannot show as current (REQ-029): always the true reason. */
sealed interface PositionWarning {

    data object NoGps : PositionWarning

    /** The GPS stopped reporting [age] ago, as its chip shows it. */
    data class GpsStale(val age: Duration) : PositionWarning

    data object No3dFix : PositionWarning

    /** ArduPilot's 0°, 0° before it has a position. */
    data object Uninitialised : PositionWarning

    /** The link is lost (REQ-017): every value is the last data, [age] old, as the banner counts it. */
    data class LinkLost(val age: Duration) : PositionWarning
}

/**
 * The warning over [position], or `null` when it is current or there is none. With [lostFor] set the link is lost,
 * and that is the reason whatever the GPS said last; otherwise the reason the position is not verified, with the
 * [gps] report's age when it stopped arriving.
 */
internal fun positionWarningOf(
    position: Reading<PositionReading>?,
    gps: Reading<GpsStatus>?,
    lostFor: Duration?,
): PositionWarning? = when {
    position == null -> null

    lostFor != null -> PositionWarning.LinkLost(lostFor)

    else -> when (position.value.trust) {
        PositionTrust.VERIFIED, PositionTrust.NO_POSITION -> null
        PositionTrust.NO_GPS -> PositionWarning.NoGps
        PositionTrust.GPS_STALE -> PositionWarning.GpsStale(gps?.age ?: Duration.ZERO)
        PositionTrust.NO_3D_FIX -> PositionWarning.No3dFix
        PositionTrust.UNINITIALISED -> PositionWarning.Uninitialised
    }
}

@Composable
internal fun warningText(warning: PositionWarning): String = when (warning) {
    PositionWarning.NoGps -> stringResource(id = R.string.hud_position_no_gps)
    is PositionWarning.GpsStale -> stringResource(id = R.string.hud_position_gps_stale, elapsedText(warning.age))
    PositionWarning.No3dFix -> stringResource(id = R.string.hud_position_no_fix)
    PositionWarning.Uninitialised -> stringResource(id = R.string.hud_position_uninitialised)
    is PositionWarning.LinkLost -> stringResource(id = R.string.hud_position_link_lost, elapsedText(warning.age))
}
