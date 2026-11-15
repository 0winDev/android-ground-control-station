package com.owindev.gcs.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.TakeOffAltitudeRule
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends one command from adb for the SITL check, through the same [CommandRepository] and vetoes as the app; the
 * repository logs the result.
 *
 * ```
 * adb shell am broadcast -a com.owindev.gcs.debug.SEND_COMMAND --es command rtl --ei systemId 1 -p com.owindev.gcs
 * ```
 *
 * A take off is `--es command takeoff --ef altitude 10`; without `--ef altitude` it goes to the rule's default. An
 * arm acknowledges nothing unless `--ez acknowledgeAmbers true` acknowledges every amber pre-flight item of the check
 * judged now, as the operator would on the checklist (#106); red items still refuse it.
 */
@AndroidEntryPoint
class SitlCommandReceiver : BroadcastReceiver() {

    @Inject
    lateinit var commands: CommandRepository

    @Inject
    lateinit var altitudeRule: TakeOffAltitudeRule

    @Inject
    lateinit var preflight: PreflightRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val systemId = intent.getIntExtra(EXTRA_SYSTEM_ID, NO_SYSTEM_ID)
        val send: (suspend (SystemId) -> CommandResult)? = when (command) {
            "arm" -> { id ->
                commands.arm(id, acknowledgementsFor(id, intent.getBooleanExtra(EXTRA_ACKNOWLEDGE, false)))
            }

            "rtl" -> commands::returnToLaunch

            "land" -> commands::land

            "takeoff" -> { id ->
                commands.takeOff(id, intent.getFloatExtra(EXTRA_ALTITUDE, altitudeRule.defaultMetres))
            }

            else -> null
        }
        if (send == null || systemId !in SYSTEM_IDS) {
            Log.w(TAG, "Expected --es $EXTRA_COMMAND arm|rtl|land|takeoff --ei $EXTRA_SYSTEM_ID 1..255")
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

    private fun acknowledgementsFor(systemId: SystemId, acknowledgeAmbers: Boolean): PreflightAcknowledgements {
        if (!acknowledgeAmbers) return PreflightAcknowledgements()
        val check = preflight.checkFor(systemId)
        val now = TimeSource.Monotonic.markNow()
        return check.ambers.fold(PreflightAcknowledgements()) { acknowledged, item ->
            acknowledged.acknowledge(item, check, now)
        }
    }

    private companion object {
        const val TAG = "SitlCommandReceiver"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SYSTEM_ID = "systemId"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_ACKNOWLEDGE = "acknowledgeAmbers"
        const val NO_SYSTEM_ID = 0
        val SYSTEM_IDS = 1..255
    }
}
