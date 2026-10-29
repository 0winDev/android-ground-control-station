package com.owindev.gcs.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends one command from adb for the SITL check, through the same [CommandRepository] and vetoes as the app; the
 * repository logs the result.
 *
 * ```
 * adb shell am broadcast -a com.owindev.gcs.debug.SEND_COMMAND --es command rtl --ei systemId 1 -p com.owindev.gcs
 * ```
 */
@AndroidEntryPoint
class SitlCommandReceiver : BroadcastReceiver() {

    @Inject
    lateinit var commands: CommandRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val systemId = intent.getIntExtra(EXTRA_SYSTEM_ID, NO_SYSTEM_ID)
        val send: (suspend (SystemId) -> CommandResult)? = when (command) {
            "arm" -> commands::arm
            "rtl" -> commands::returnToLaunch
            "land" -> commands::land
            else -> null
        }
        if (send == null || systemId !in SYSTEM_IDS) {
            Log.w(TAG, "Expected --es $EXTRA_COMMAND arm|rtl|land --ei $EXTRA_SYSTEM_ID 1..255")
            return
        }
        val broadcast = goAsync()
        scope.launch {
            try {
                send(SystemId(systemId))
            } finally {
                broadcast.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SitlCommandReceiver"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SYSTEM_ID = "systemId"
        const val NO_SYSTEM_ID = 0
        val SYSTEM_IDS = 1..255
    }
}
