package com.owindev.gcs.data.vehicle.link

import kotlin.time.TestTimeSource

/** An uplink record for tests that do not look at it. */
internal fun uplinkOnTestClock(): DefaultUplinkRepository = DefaultUplinkRepository(TestTimeSource())
