package com.owindev.gcs.core.mavlink

class TlogReader {

    val stats: TlogStats
        get() = TODO("#68")

    // Stubs so the tests compile red; the implementation follows in the next commit.
    @Suppress("UnusedParameter")
    fun read(chunk: ByteArray): List<TlogRecord> = TODO("#68")

    fun finish(): List<TlogRecord> = TODO("#68")
}
