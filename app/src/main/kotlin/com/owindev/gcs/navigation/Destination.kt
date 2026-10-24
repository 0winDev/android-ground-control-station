package com.owindev.gcs.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/** The app's screens (ADR 0007); the flight screen is always at the bottom of the back stack. */
enum class Destination { FLIGHT, RECORDINGS }

/** Saved by name, so the back stack survives a process death without a serialization plugin (ADR 0007). */
internal val BackStackSaver: Saver<SnapshotStateList<Destination>, Any> = listSaver(
    save = { stack -> stack.map(Destination::name) },
    restore = { names -> names.map(Destination::valueOf).toMutableStateList() },
)

/** Opens the recordings over the flight screen, once. */
internal fun MutableList<Destination>.openRecordings() {
    if (lastOrNull() != Destination.RECORDINGS) add(Destination.RECORDINGS)
}

/** Back to the flight screen, whatever is on top of it. */
internal fun MutableList<Destination>.backToFlight() {
    retainAll { it == Destination.FLIGHT }
    if (isEmpty()) add(Destination.FLIGHT)
}
