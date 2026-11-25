package com.owindev.gcs.core.domain.message

/**
 * [text], from the link, made safe to show on one line (REQ-047): control characters and line breaks become a space;
 * bidirectional controls and zero-width characters, which could reorder or hide what the operator reads, become
 * U+FFFD, so their presence stays visible. Every other character is kept as it came.
 */
fun shownText(text: String): String {
    if (text.none { it.isSpace() || it.isReplaced() }) return text
    return buildString(text.length) {
        for (character in text) {
            when {
                character.isSpace() -> append(' ')
                character.isReplaced() -> append(REPLACEMENT)
                else -> append(character)
            }
        }
    }
}

/** C0 and C1 controls, DEL, and the Unicode line and paragraph separators. */
private fun Char.isSpace(): Boolean =
    this in C0_CONTROLS || this == DELETE || this in C1_CONTROLS || this == LINE_SEPARATOR ||
        this == PARAGRAPH_SEPARATOR

/** Bidirectional embeddings, overrides, isolates and marks, and zero-width characters. */
private fun Char.isReplaced(): Boolean = this in BIDI_EMBEDDINGS ||
    this in BIDI_ISOLATES ||
    this in BIDI_MARKS ||
    this in ZERO_WIDTH ||
    this == WORD_JOINER ||
    this == BYTE_ORDER_MARK

private const val REPLACEMENT = '\uFFFD'
private const val DELETE = '\u007F'
private const val LINE_SEPARATOR = '\u2028'
private const val PARAGRAPH_SEPARATOR = '\u2029'
private const val WORD_JOINER = '\u2060'
private const val BYTE_ORDER_MARK = '\uFEFF'
private val C0_CONTROLS = '\u0000'..'\u001F'
private val C1_CONTROLS = '\u0080'..'\u009F'
private val BIDI_EMBEDDINGS = '\u202A'..'\u202E'
private val BIDI_ISOLATES = '\u2066'..'\u2069'
private val BIDI_MARKS = '\u200E'..'\u200F'
private val ZERO_WIDTH = '\u200B'..'\u200D'
