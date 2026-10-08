package com.owindev.gcs.core.testing

/**
 * Reads a test fixture (e.g. a MAVLink frame captured from ArduPilot SITL) from the calling
 * module's `src/test/resources`. [path] is relative to the resources root, e.g. `heartbeat/armed.bin`.
 */
fun readFixture(path: String): ByteArray {
    val stream = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) {
        "Fixture not found on the test classpath: $path"
    }
    return stream.use { it.readBytes() }
}
