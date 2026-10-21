package com.owindev.gcs.feature.map

/**
 * The tiles' [attributions], HTML as the style's sources declare them, as one line of plain text with no links: always
 * shown, never a tap that opens a browser in flight. The OpenStreetMap attribution guidelines allow it as long as the
 * links can be found elsewhere in the app, which the HUD's licences do (ADR 0005).
 */
internal fun plainAttribution(attributions: List<String>): String = attributions
    .joinToString(separator = " ") { html ->
        ENTITIES.entries.fold(html.replace(TAG, "")) { text, (entity, character) -> text.replace(entity, character) }
    }
    .replace(SPACES, " ")
    .trim()

private val TAG = Regex("<[^>]*>")
private val SPACES = Regex("""\s+""")

/** The entities the tiles' attributions use, and the few any HTML text may. */
private val ENTITIES = mapOf("&copy;" to "©", "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&amp;" to "&")
