package com.owindev.gcs.feature.hud

import java.util.Locale
import kotlin.time.Duration

/**
 * [elapsed] as a clock, truncated to the second: `00:07`, or `1:02:03` from one hour on. Digits are
 * always ASCII, whatever the device locale, like the rest of the telemetry.
 */
internal fun formatElapsed(elapsed: Duration): String = elapsed.toComponents { hours, minutes, seconds, _ ->
    if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
