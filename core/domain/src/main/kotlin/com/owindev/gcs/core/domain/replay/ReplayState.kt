package com.owindev.gcs.core.domain.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import java.io.File

/** The replay's state; [origin] says whether the link is the live one or the replay's. */
sealed interface ReplayState {

    val origin: DataOrigin

    /** No replay: the link is the live one. */
    data object Off : ReplayState {
        override val origin = DataOrigin.LIVE
    }

    /** [recording] is playing at 1×. */
    data class Playing(val recording: File) : ReplayState {
        override val origin = DataOrigin.REPLAY
    }

    /** [recording] is paused: its time stands still, so nothing ages. */
    data class Paused(val recording: File) : ReplayState {
        override val origin = DataOrigin.REPLAY
    }

    /** [recording] has nothing more to play; its time goes on, so what it showed grows stale until [ReplayRepository.stop]. */
    data class Ended(val recording: File) : ReplayState {
        override val origin = DataOrigin.REPLAY
    }

    /** A replay was not started, for [reason]: the live link was left as it was. */
    data class Refused(val reason: ReplayRefusal) : ReplayState {
        override val origin = DataOrigin.LIVE
    }

    /** [recording] could not be played, for [reason]. */
    data class Failed(val recording: File, val reason: ReplayFailure) : ReplayState {
        override val origin = reason.origin
    }
}

/**
 * Why a replay is refused: it would close the live link, and with it this GCS's HEARTBEAT, which the vehicle's GCS
 * failsafe watches (`FS_GCS_ENABLE`).
 */
enum class ReplayRefusal {
    /** The live vehicle is being heard. */
    VEHICLE_CONNECTED,

    /** The live vehicle was armed when last heard, connected or not. */
    VEHICLE_ARMED,
}

/** Why a recording could not be played, and whether the live link was closed for it ([origin]). */
enum class ReplayFailure(val origin: DataOrigin) {
    /** The file is not one of the GCS's recordings: never opened, the live link left as it was. */
    NOT_A_RECORDING(DataOrigin.LIVE),

    /** Reading the recording failed; the live link stays closed until [ReplayRepository.stop]. */
    UNREADABLE(DataOrigin.REPLAY),
}
