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

/**
 * Opens the recordings over the flight screen, once, only if the operator [mayLeaveFlight] when it runs: a tap drawn
 * a frame before a vehicle armed can arrive after the app went back to the flight screen.
 */
internal fun MutableList<Destination>.openRecordings(mayLeaveFlight: Boolean) {
    if (mayLeaveFlight && lastOrNull() != Destination.RECORDINGS) add(Destination.RECORDINGS)
}

internal fun MutableList<Destination>.backToFlight() {
    retainAll { it == Destination.FLIGHT }
    if (isEmpty()) add(Destination.FLIGHT)
}
