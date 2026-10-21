package com.owindev.gcs.core.designsystem.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.owindev.gcs.core.designsystem.R
import kotlin.time.Duration

/** A time shown to the operator, truncated to the whole second, never rounded up. */
data class ElapsedParts(val hours: Long, val minutes: Int, val seconds: Int)

/** [elapsed] in whole hours, minutes and seconds. */
fun elapsedPartsOf(elapsed: Duration): ElapsedParts =
    elapsed.toComponents { hours, minutes, seconds, _ -> ElapsedParts(hours, minutes, seconds) }

/**
 * [elapsed] as every screen shows a time: `13 s`, then `1 min 46 s` past a minute and `1 h 2 min 3 s` past an hour,
 * so the same age reads the same on the banner, the panel and the map.
 */
@Composable
@ReadOnlyComposable
fun elapsedText(elapsed: Duration): String {
    val parts = elapsedPartsOf(elapsed)
    return when {
        parts.hours > 0 -> stringResource(R.string.elapsed_hours, parts.hours, parts.minutes, parts.seconds)
        parts.minutes > 0 -> stringResource(R.string.elapsed_minutes, parts.minutes, parts.seconds)
        else -> stringResource(R.string.elapsed_seconds, parts.seconds)
    }
}
