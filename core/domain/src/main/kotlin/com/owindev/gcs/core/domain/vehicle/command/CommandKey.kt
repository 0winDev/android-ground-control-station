package com.owindev.gcs.core.domain.vehicle.command

import com.owindev.gcs.core.domain.vehicle.SystemId

/**
 * What a confirmed command was confirmed for (REQ-050): the active live vehicle and its [connection], a counter that
 * moves on every change of its link, reboot or session. A command whose key is no longer current is never sent.
 */
data class CommandKey(val systemId: SystemId, val connection: Long)
