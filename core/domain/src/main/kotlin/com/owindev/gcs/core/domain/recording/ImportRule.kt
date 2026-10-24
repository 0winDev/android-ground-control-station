package com.owindev.gcs.core.domain.recording

/**
 * What an imported flight log must be before it joins the recordings (REQ-038). A file brought from elsewhere is
 * untrusted: it is refused above [maxBytes] whether its source declares more or the copy counts more, since a declared
 * size can lie, and it is stored under a name of its own making, one file name made only of ASCII letters, digits, `.`,
 * `_` and `-`, never starting with a dot and never one already taken.
 */
class ImportRule(val maxBytes: Long = RecordingRule.DEFAULT_MAX_TOTAL_BYTES) {

    init {
        require(maxBytes > 0) { "The import limit must be positive, was $maxBytes bytes" }
    }

    /** Whether a source declaring [declaredBytes] is refused before a byte is copied; an unknown size is not. */
    fun refusesDeclared(declaredBytes: Long?): Boolean = declaredBytes != null && declaredBytes > maxBytes

    fun exceeds(copiedBytes: Long): Boolean = copiedBytes > maxBytes

    /**
     * The name to store a log called [displayName] under: its last path part, without `.tlog`, each other character
     * replaced by `_`, leading dots dropped and cut to [MAX_BASE_LENGTH], [DEFAULT_BASE] when nothing is left, then the
     * first of `name.tlog`, `name_1.tlog`, … not in [taken], compared ignoring case.
     */
    fun nameFor(displayName: String?, taken: Set<String>): String {
        val lastPart = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val base = lastPart
            .removeSuffixIgnoringCase(EXTENSION)
            .replace(NOT_ALLOWED, "_")
            .trimStart('.')
            .take(MAX_BASE_LENGTH)
            .ifEmpty { DEFAULT_BASE }
        val takenLower = taken.mapTo(HashSet()) { it.lowercase() }
        return generateSequence(0) { it + 1 }
            .map { clash -> if (clash == 0) "$base$EXTENSION" else "${base}_$clash$EXTENSION" }
            .first { it.lowercase() !in takenLower }
    }

    private fun String.removeSuffixIgnoringCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    companion object {
        const val EXTENSION = ".tlog"

        /** Long enough for any name a ground station gives its logs, short enough for any file system. */
        const val MAX_BASE_LENGTH = 64

        const val DEFAULT_BASE = "imported"

        private val NOT_ALLOWED = Regex("[^A-Za-z0-9._-]")
    }
}
