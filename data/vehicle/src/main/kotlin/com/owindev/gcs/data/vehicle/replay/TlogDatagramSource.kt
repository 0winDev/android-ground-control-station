package com.owindev.gcs.data.vehicle.replay

import com.owindev.gcs.core.mavlink.TlogReader
import com.owindev.gcs.core.mavlink.TlogRecord
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.DatagramSource
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The longest a replay waits between two records (REQ-037), whatever their timestamps say: longer than the link-loss
 * timeout, so a real gap in the recording still shows as a lost link, but short enough that a wall-clock jump forwards
 * (or a long outage kept in the log, up to 10 min by REQ-036) never stalls the replay.
 */
internal val MAX_RECORD_WAIT: Duration = 10.seconds

/**
 * Where every replayed datagram says it came from: an unresolved address, so nothing could ever be sent to it. A
 * recording does not keep the vehicle's address, and a replay never answers.
 */
internal val REPLAY_SENDER: InetSocketAddress = InetSocketAddress.createUnresolved("replay", 0)

private const val CHUNK_SIZE = 4096

/**
 * Plays [recording], a .tlog, as the link would have delivered it (REQ-037): each record's frame as one datagram from
 * [REPLAY_SENDER], when the [clock] reaches its time, so pausing the clock pauses the replay.
 *
 * Records are spaced by the difference of their wall-clock timestamps, bounded to zero and [MAX_RECORD_WAIT]: a clock
 * set back never stalls the replay and one set forwards never skips ahead. Each collection plays the file from its
 * start, its first record at once at the clock's position then, so collecting it again never plays a burst. Damaged
 * bytes are left to the [TlogReader], which never throws; a file that cannot be read fails the flow with an
 * [java.io.IOException]. The flow completes at the end of the file. The file is read on [ioDispatcher].
 *
 * While it waits out a gap cut to [MAX_RECORD_WAIT], [onGap] has the recorded time it skips, and `null` once the next
 * record is delivered or the collection ends, so the operator knows the replay jumped (REQ-037).
 */
internal class TlogDatagramSource(
    private val recording: File,
    private val clock: ReplayClock,
    private val ioDispatcher: CoroutineDispatcher,
    private val open: (File) -> InputStream = File::inputStream,
    private val onGap: (Duration?) -> Unit = {},
) : DatagramSource {

    override fun datagrams(): Flow<Datagram> = flow {
        val pacer = Pacer(start = clock.position)
        val reader = TlogReader()
        open(recording).use { input ->
            val chunk = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                reader.read(chunk.copyOf(read)).forEach { record -> pacer.deliver(record, this) }
            }
        }
        reader.finish().forEach { record -> pacer.deliver(record, this) }
    }.flowOn(ioDispatcher)

    /** When each record is due on the [clock], from [start]; one per collection. */
    private inner class Pacer(start: Duration) {
        private var due = start
        private var previous: Long? = null

        suspend fun deliver(record: TlogRecord, collector: FlowCollector<Datagram>) {
            val skipped = previous?.let { skippedBetween(it, record.timestampMicros) }
            due += previous?.let { waitBetween(it, record.timestampMicros) } ?: Duration.ZERO
            previous = record.timestampMicros
            if (skipped != null) onGap(skipped)
            try {
                clock.awaitPosition(due)
            } finally {
                // Also when the collection is cancelled inside the gap, or a new one would show it while it plays.
                if (skipped != null) onGap(null)
            }
            collector.emit(Datagram(record.frame, REPLAY_SENDER))
        }
    }
}

/**
 * The wait between records stamped [previous] and [next], in [0, MAX_RECORD_WAIT]. A difference that overflows a Long
 * (stamps of 2⁶³ µs or more read as negative, [TlogRecord]) is a jump like any other and waits the maximum.
 */
internal fun waitBetween(previous: Long, next: Long): Duration = try {
    Math.subtractExact(next, previous).microseconds.coerceIn(Duration.ZERO, MAX_RECORD_WAIT)
} catch (@Suppress("SwallowedException") overflow: ArithmeticException) {
    MAX_RECORD_WAIT
}

/**
 * The recorded time a replay skips between records stamped [previous] and [next]: how much their gap exceeds
 * [MAX_RECORD_WAIT], `null` when it does not. A clock set back skips nothing it could name, and neither does a
 * difference that overflows a Long.
 */
internal fun skippedBetween(previous: Long, next: Long): Duration? = try {
    (Math.subtractExact(next, previous).microseconds - MAX_RECORD_WAIT).takeIf { it > Duration.ZERO }
} catch (@Suppress("SwallowedException") overflow: ArithmeticException) {
    null
}
