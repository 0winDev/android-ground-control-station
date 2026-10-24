package com.owindev.gcs.data.vehicle.recording

import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.mavlink.TlogReader
import com.owindev.gcs.core.mavlink.TlogRecord
import java.io.IOException
import java.io.InputStream

private const val CHUNK_SIZE = 64 * 1024

/**
 * The span of a .tlog's valid MAVLink 2 frames, the ones the replay would decode: [firstMicros] and [lastMicros] as
 * stored, both `null` when it holds none, as a file that is not a .tlog does.
 */
internal data class RecordingScan(val firstMicros: Long?, val lastMicros: Long?) {

    val hasValidFrame: Boolean get() = firstMicros != null

    companion object {

        /**
         * Reads [input] to its end with the replay's reader and parser, so damage and unknown messages are dropped as
         * the replay drops them, and nothing in the file can make it throw but the stream itself.
         */
        @Throws(IOException::class)
        fun of(input: InputStream): RecordingScan {
            val reader = TlogReader()
            val parser = MavlinkParser(MavlinkMessages)
            var first: Long? = null
            var last: Long? = null
            fun take(records: List<TlogRecord>) = records.forEach { record ->
                if (parser.parse(record.frame).isNotEmpty()) {
                    if (first == null) first = record.timestampMicros
                    last = record.timestampMicros
                }
            }
            val chunk = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                take(reader.read(chunk.copyOf(read)))
            }
            take(reader.finish())
            return RecordingScan(first, last)
        }
    }
}
