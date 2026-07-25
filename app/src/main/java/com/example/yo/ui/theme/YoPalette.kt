package com.example.yo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Yo's colour system, taken from Yo's OWN "Yo Branding Guidelines" (archived at
 * docs.justyo.co/docs/ui-design-guidelines), which lists exactly ten named colours with hexes and
 * usage notes. Every value here is quoted, not sampled — and the row order below is the doc's own
 * table order, which pixel-sampling the real 2014 Play Store screenshot independently confirms.
 *
 * The palette is a curated 10-of-20 subset of Flat UI Colors (2013), names abbreviated. See
 * docs/PRD.md section 4 for the full provenance.
 */
object YoPalette {
    /** AMETHYST — "the main purple". Background and app icon only; never a row colour. */
    val Amethyst = Color(0xFF9B59B6)

    /** ALIZARIN — the doc's only entry with a usage note: "red (menu button)". Nothing else. */
    val Alizarin = Color(0xFFE74C3C)

    /** "Text color: White (#FFFFFF)" — the doc's words. There is no second text colour, no grey. */
    val OnColor = Color(0xFFFFFFFF)

    /**
     * The eight row colours, in Yo's own table order. AMETHYST and ALIZARIN are deliberately
     * absent: the doc reserves them for the background and the menu button.
     */
    val Rows = listOf(
        Color(0xFF1ABC9C), // TURQUOISE — "blueish green"
        Color(0xFF2ECC71), // EMERALD   — "light green"
        Color(0xFF3498DB), // PETER     — "light blue"
        Color(0xFF34495E), // ASPHALT   — "dark blue"
        Color(0xFF16A085), // GREEN     — "green"
        Color(0xFFF1C40F), // SUNFLOWER — "orange yellow"
        Color(0xFF2980B9), // BELIZE    — "blue"
        Color(0xFF8E44AD), // WISTERIA  — "dark purple"
    )

    /**
     * Colour is a function of ROW POSITION, not of who the contact is. Proven by the same five
     * names appearing in different colours across builds, and by Yo's own menu screen — whose
     * rows are not contacts at all — using the identical sequence.
     */
    fun colorForIndex(index: Int): Color = Rows[((index % Rows.size) + Rows.size) % Rows.size]

    /** Semi-opaque white for placeholder text, matching `rgba(255,255,255,0.8)` in Yo's own CSS. */
    val Placeholder = Color(0xCCFFFFFF)
}
