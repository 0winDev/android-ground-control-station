package com.owindev.gcs.core.domain.geo

import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import kotlin.math.abs
import kotlin.math.floor

/**
 * A position in the Military Grid Reference System at 1 m, or why there is none (REQ-027).
 *
 * The letters and the truncation follow GeographicLib 2.7 (`MGRS::Forward`), which implements DMA TM 8358.1 (1990)
 * as updated by NGA.STND.0037 and NGA.SIG.0012 (2014), the documents GeographicLib's `MGRS.hpp` cites.
 */
sealed interface Mgrs {

    /**
     * A 1 m MGRS square, e.g. `33X VC 12345 67890`.
     *
     * @property zone UTM zone, 1 to 60, with the Norway and Svalbard exceptions.
     * @property band latitude band, C to X without I and O, 8° each except X (12°).
     * @property square the 100 km square: column letter, then row letter, without I and O.
     * @property easting metres east within the square, 0 to 99 999, truncated (never rounded).
     * @property northing metres north within the square, 0 to 99 999, truncated (never rounded).
     */
    data class Grid(val zone: Int, val band: Char, val square: String, val easting: Int, val northing: Int) : Mgrs {
        override fun toString(): String = "${zone.toString().padStart(ZONE_DIGITS, '0')}$band $square " +
            "${easting.toString().padStart(METRE_DIGITS, '0')} ${northing.toString().padStart(METRE_DIGITS, '0')}"
    }

    /**
     * Outside UTM coverage (south of 80°S, or from 84°N, where MGRS uses UPS), or not a valid position: shown as
     * not available, never as a wrong value.
     */
    data object NotAvailable : Mgrs
}

/** The MGRS square at 1 m of a WGS84 position, in degrees (REQ-027). */
fun mgrsOf(latitude: Double, longitude: Double): Mgrs {
    val utm = UtmProjection.utmOf(latitude, longitude) ?: return Mgrs.NotAvailable
    // Whole micrometres, then integer arithmetic: truncation never depends on how a decimal prints.
    val eastingMicrometres = floor(utm.easting * MICROMETRES_PER_METRE).toLong()
    val northingMicrometres = floor(utm.northing * MICROMETRES_PER_METRE).toLong()
    val column = (eastingMicrometres / MICROMETRES_PER_SQUARE).toInt()
    val row = (northingMicrometres / MICROMETRES_PER_SQUARE).toInt()
    // A latitude a hair off the equator still takes its hemisphere's band (GeographicLib's angeps).
    val band = if (abs(latitude) < EQUATOR_FUZZ) {
        if (utm.north) 0 else -1
    } else {
        UtmProjection.latitudeBand(latitude)
    }
    val zoneIndex = utm.zone - 1
    // Columns: three sets of eight letters, by zone; the first square east of 100 km is the set's first letter.
    val columnLetter = COLUMN_LETTERS[zoneIndex % COLUMN_LETTERS.size][column - FIRST_COLUMN]
    // Rows: twenty letters from the equator, repeating; even zones start five letters later.
    val rowShift = if (zoneIndex % 2 == 1) EVEN_ZONE_ROW_SHIFT else 0
    val rowLetter = ROW_LETTERS[(row + rowShift) % ROW_LETTERS.length]
    return Mgrs.Grid(
        zone = utm.zone,
        band = BAND_LETTERS[band - FIRST_BAND],
        square = "$columnLetter$rowLetter",
        easting = ((eastingMicrometres - column * MICROMETRES_PER_SQUARE) / MICROMETRES_PER_METRE).toInt(),
        northing = ((northingMicrometres - row * MICROMETRES_PER_SQUARE) / MICROMETRES_PER_METRE).toInt(),
    )
}

/** This position in MGRS at 1 m (REQ-027). */
fun GlobalPosition.toMgrs(): Mgrs = mgrsOf(latitude, longitude)

private const val ZONE_DIGITS = 2
private const val METRE_DIGITS = 5

private const val MICROMETRES_PER_METRE = 1_000_000L
private const val SQUARE_METRES = 100_000L
private const val MICROMETRES_PER_SQUARE = MICROMETRES_PER_METRE * SQUARE_METRES

/** 2⁻⁴⁶ degrees, about 1.4e-14: GeographicLib's `angeps` for doubles, 2^−(53 − 7). */
private const val EQUATOR_FUZZ = 1.0 / (1L shl 46)

/** Bands from 80°S, index −10 (C) to 9 (X) as returned by [UtmProjection.latitudeBand]. */
private const val BAND_LETTERS = "CDEFGHJKLMNPQRSTUVWX"
private const val FIRST_BAND = -10

private val COLUMN_LETTERS = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")

/** Eastings 100 to 200 km are the first column (UTM eastings run from about 100 to 900 km). */
private const val FIRST_COLUMN = 1

private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"
private const val EVEN_ZONE_ROW_SHIFT = 5
