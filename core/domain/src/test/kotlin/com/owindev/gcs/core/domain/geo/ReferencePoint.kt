package com.owindev.gcs.core.domain.geo

import com.owindev.gcs.core.testing.readFixture

/**
 * One row of `geo/mgrs-reference.csv`: values from GeographicLib GeoConvert and NGA GEOTRANS, never from our code
 * (see `src/test/resources/geo/README.md`).
 *
 * @property mgrs at 1 m, or `null` where the references say the point is outside UTM coverage.
 * @property utm GeoConvert's UTM to 1 µm, or `null` with [mgrs].
 */
internal data class ReferencePoint(
    val latitude: Double,
    val longitude: Double,
    val mgrs: String?,
    val utm: Utm?,
    val reason: String,
) {
    /** Names the parameterized test cases. */
    override fun toString(): String = "$latitude, $longitude: $reason"
}

/** Every point of the reference CSV, in file order. */
internal val REFERENCE_POINTS: List<ReferencePoint> by lazy {
    readFixture(REFERENCE_CSV)
        .decodeToString()
        .lines()
        .filter { line -> line.isNotBlank() && !line.startsWith("#") }
        .drop(1)
        .map(::referencePoint)
}

private const val REFERENCE_CSV = "geo/mgrs-reference.csv"
private const val NOT_AVAILABLE = "NOT_AVAILABLE"
private const val COLUMNS = 8

/** Splits a CSV line on commas outside double quotes (only the reason is ever quoted). */
private fun referencePoint(line: String): ReferencePoint {
    val fields = Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*$)""").split(line).map { it.trim('"') }
    check(fields.size == COLUMNS) { "Expected $COLUMNS columns: $line" }
    val mgrs = fields[2]
    val available = mgrs != NOT_AVAILABLE
    return ReferencePoint(
        latitude = fields[0].toDouble(),
        longitude = fields[1].toDouble(),
        mgrs = mgrs.takeIf { available },
        utm = if (available) {
            Utm(
                zone = fields[3].toInt(),
                north = fields[4] == "n",
                easting = fields[5].toDouble(),
                northing = fields[6].toDouble(),
            )
        } else {
            null
        },
        reason = fields[7],
    )
}
