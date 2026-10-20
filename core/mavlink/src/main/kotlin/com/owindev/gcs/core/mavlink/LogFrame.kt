package com.owindev.gcs.core.mavlink

/** What [TlogWriter.loggable] lets into the flight log for one frame (ADR 0006). */
sealed interface LogFrame {

    /** The [bytes] to write: the frame itself if unsigned, a copy without its signature if signed. */
    class Write(val bytes: ByteArray) : LogFrame {

        override fun toString(): String = "Write(${bytes.size} bytes)"
    }

    /** A frame that never goes into the log, by reason. */
    sealed interface LeftOut : LogFrame

    /** SETUP_SIGNING carries the signing key: "omit SETUP_SIGNING messages from logs" (signing guide, Logging). */
    data object SetupSigning : LeftOut

    /** A signed frame whose message ID has no CRC_EXTRA, so its checksum cannot be recomputed without the signature. */
    data object SignedUnknownMessage : LeftOut

    /** A MAVLink 1 frame: the log holds MAVLink 2 only. */
    data object Mavlink1 : LeftOut
}
