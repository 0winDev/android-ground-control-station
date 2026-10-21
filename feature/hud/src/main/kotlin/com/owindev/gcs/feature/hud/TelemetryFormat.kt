package com.owindev.gcs.feature.hud

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.designsystem.time.elapsedText
import com.owindev.gcs.core.domain.geo.Mgrs
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import kotlin.math.abs
import kotlin.time.Duration

// How the HUD writes telemetry values: units in the strings, numbers in the device's locale (REQ-029).

@Composable
internal fun valueColor(reading: Reading<*>?, lost: Boolean = false): Color =
    if (lost || reading?.stale == true) GcsTheme.colors.stale else MaterialTheme.colorScheme.onSurface

@Composable
internal fun ageText(age: Duration): String = stringResource(id = R.string.hud_age_seconds, elapsedText(age))

@Composable
internal fun metres(value: Double): String = stringResource(id = R.string.hud_value_metres, shown(value, decimals = 1))

@Composable
internal fun speed(value: Double): String = stringResource(id = R.string.hud_value_speed, shown(value, decimals = 1))

@Composable
internal fun climbRate(value: Double): String {
    val rate = shown(value, decimals = 1)
    // A sign only on a real climb or descent: 0 is "0.0 m/s", never "+0.0".
    return stringResource(id = if (rate == 0.0) R.string.hud_value_speed else R.string.hud_value_climb_rate, rate)
}

@Composable
internal fun degrees(value: Double): String = stringResource(id = R.string.hud_value_degrees, shownHeading(value))

@Composable
internal fun signedDegrees(value: Double): String {
    val angle = shown(value, decimals = 0)
    return stringResource(
        id = if (angle ==
            0.0
        ) {
            R.string.hud_value_degrees
        } else {
            R.string.hud_value_signed_degrees
        },
        angle,
    )
}

@Composable
internal fun coordinates(position: GlobalPosition): String {
    // The hemisphere from the value as shown, so -0.000001° is "0.00000° N", never "0.00000° S".
    val shownLatitude = shown(position.latitude, COORDINATE_DECIMALS)
    val shownLongitude = shown(position.longitude, COORDINATE_DECIMALS)
    val latitude = stringResource(
        id = R.string.hud_coordinate,
        abs(shownLatitude),
        stringResource(id = if (shownLatitude < 0) R.string.hud_south else R.string.hud_north),
    )
    val longitude = stringResource(
        id = R.string.hud_coordinate,
        abs(shownLongitude),
        stringResource(id = if (shownLongitude < 0) R.string.hud_west else R.string.hud_east),
    )
    return stringResource(id = R.string.hud_coordinates, latitude, longitude)
}

@Composable
internal fun mgrsText(mgrs: Mgrs): String = when (mgrs) {
    is Mgrs.Grid -> mgrs.toString()
    Mgrs.NotAvailable -> stringResource(id = R.string.hud_mgrs_not_available)
}

/** As `hud_coordinate` shows them: 5 decimals, about 1 m. */
private const val COORDINATE_DECIMALS = 5
