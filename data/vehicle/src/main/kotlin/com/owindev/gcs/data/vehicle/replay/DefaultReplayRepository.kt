package com.owindev.gcs.data.vehicle.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.replay.ReplayFailure
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayRule
import com.owindev.gcs.core.domain.replay.ReplayState
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.LinkStatus
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.recording.LastClosedMarker
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plays the GCS's own recordings, the .tlogs in [recordings], through the [session] (REQ-037).
 *
 * [play] checks, in order, that the file is one of [recordings] (by its real path, so `..` or a link cannot reach
 * another file) and that [rule] allows a replay with every live vehicle as it is now; either
 * failing leaves the live link untouched. Then the [recorder] keeps that file from retention while it plays, since
 * closing the live recording applies the limit, and the session closes the live link and plays the file from
 * [replaySource] on a new replay clock, with its own pin of the active vehicle; the session switches the [clock] once
 * the live link has closed. [stop] gives the link back to a new live session.
 *
 * The end of the recording and a failure to read it come from the session's status, watched in [scope]. Calls come
 * from the UI, one at a time, so a lock is enough to keep them in order.
 */
// One collaborator per step above; grouping them would only hide that.
@Suppress("LongParameterList")
internal class DefaultReplayRepository(
    private val session: LinkSession,
    private val vehicleRepository: VehicleRepository,
    private val clock: PipelineClock,
    private val recorder: FlightRecorder,
    private val rule: ReplayRule,
    private val recordings: File,
    private val replaySource: (File, ReplayClock) -> DatagramSource,
    scope: CoroutineScope,
) : ReplayRepository {

    private val lock = Any()
    private val mutableState = MutableStateFlow<ReplayState>(ReplayState.Off)

    /** The clock of the replay playing now; under [lock]. */
    private var replayClock: ReplayClock? = null

    override val state: StateFlow<ReplayState> = mutableState.asStateFlow()

    init {
        scope.launch { session.status.collect(::follow) }
    }

    override fun play(recording: File): ReplayState = synchronized(lock) {
        val playing = mutableState.value.origin == DataOrigin.REPLAY
        // The live link is already closed during a replay: playing another one changes nothing for the vehicle.
        val refusal = if (playing) null else rule.refusalFor(vehicleRepository.vehicles.value.values)
        val real = recordingAt(recording)
        val result = when {
            real == null -> ReplayState.Failed(recording, ReplayFailure.NOT_A_RECORDING)

            refusal != null -> ReplayState.Refused(refusal)

            else -> {
                start(real)
                ReplayState.Playing(recording)
            }
        }
        // A file that is not a recording leaves whatever plays as it is, a replay included.
        if (result.origin == DataOrigin.REPLAY || !playing) mutableState.value = result
        result
    }

    override fun pause() = synchronized(lock) {
        val current = mutableState.value
        if (current is ReplayState.Playing) {
            replayClock?.pause()
            mutableState.value = ReplayState.Paused(current.recording)
        }
    }

    override fun resume() = synchronized(lock) {
        val current = mutableState.value
        if (current is ReplayState.Paused) {
            replayClock?.play()
            mutableState.value = ReplayState.Playing(current.recording)
        }
    }

    override fun stop() = synchronized(lock) {
        if (mutableState.value.origin == DataOrigin.REPLAY) {
            session.live()
            recorder.replayed = null
            replayClock = null
        }
        mutableState.value = ReplayState.Off
    }

    /** A replay after another starts afresh too: its own pin and time, and the session switches source. */
    private fun start(recording: File) {
        recorder.replayed = recording.name
        val started = clock.newReplayClock()
        replayClock = started
        session.replay(replaySource(recording, started), started)
        started.play()
    }

    /**
     * [file] by its real path if it is one of the GCS's recordings: a regular file named as the recorder names its logs
     * (`.tlog`, never the marker) right in [recordings], by real paths, links resolved, which a canonical path does not
     * do on every platform, so neither `..` nor a link can reach another file. `null` for anything else, a file that does
     * not exist included. The replay opens the real path it returns, never the name it was given.
     */
    private fun recordingAt(file: File): File? = try {
        val real = file.toPath().toRealPath()
        val isRecording = real.parent == recordings.toPath().toRealPath() &&
            Files.isRegularFile(real) &&
            real.fileName.toString().matches(LastClosedMarker.LOG_NAME)
        real.toFile().takeIf { isRecording }
    } catch (@Suppress("SwallowedException") unresolvable: IOException) {
        null
    } catch (@Suppress("SwallowedException") invalid: InvalidPathException) {
        null
    }

    /** The end of a recording or a failure to read it, as the session reports it while a replay is on. */
    private fun follow(status: LinkStatus) = mutableState.update { current ->
        // Read late, after another replay started: it belongs to the one before.
        if (session.status.value != status) return@update current
        val recording = when (current) {
            is ReplayState.Playing -> current.recording
            is ReplayState.Paused -> current.recording
            else -> return@update current
        }
        when (status) {
            is LinkStatus.ReplayEnded -> ReplayState.Ended(recording)
            is LinkStatus.ReplayFailed -> ReplayState.Failed(recording, ReplayFailure.UNREADABLE)
            else -> current
        }
    }
}
