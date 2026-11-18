package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import kotlinx.coroutines.flow.StateFlow

/** The routes, and the key a command confirmed now would carry (REQ-050). */
internal interface CommandKeys : CommandRoutes {

    val commandKey: StateFlow<CommandKey?>

    /** Read at each access, from any thread, under the sender's lock included; [commandKey] may publish it later. */
    val currentKey: CommandKey?
}

/** These routes, then closed as [NotSentReason.KeyChanged] for an open one while [key] is no longer current. */
internal fun CommandKeys.keyedBy(key: CommandKey): CommandRoutes = KeyedRoutes(this, key)

// A class, not a fun-interface lambda: lint's ExperimentalDetector crashes on one (#99).
private class KeyedRoutes(private val keys: CommandKeys, private val key: CommandKey) : CommandRoutes {

    override fun routeTo(systemId: SystemId): CommandRoute {
        val route = keys.routeTo(systemId)
        return if (route is CommandRoute.Open && keys.currentKey != key) {
            CommandRoute.Closed(NotSentReason.KeyChanged)
        } else {
            route
        }
    }
}
