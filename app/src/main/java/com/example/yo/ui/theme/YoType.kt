package com.example.yo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.yo.R

/**
 * Yo's branding guidelines state the typeface in two words: "Font: Montserrat Bold". The logo
 * source in Yo's own press kit embeds `Montserrat-Bold.ttf`, and justyo.co loaded exactly
 * `Montserrat:700,400` from Google Fonts.
 *
 * Bundled as a static 700 instance under the SIL OFL (see docs/licenses) rather than resolved from
 * a variable font at runtime, because minSdk 24 predates variable-font support and would otherwise
 * silently render everything at Regular.
 */
val MontserratBold = FontFamily(Font(R.font.montserrat_bold, FontWeight.Bold))

/**
 * Today's Montserrat is a 2017 redraw whose letterforms average ~3.7% wider than the 2014 cut Yo
 * used ('Y' is 8.6% wider). Yo sized its labels so the longest one only just fit the screen, so a
 * modern build has to claw that width back or long names overflow where they originally didn't.
 */
private val REDRAW_WIDTH_COMPENSATION = (-0.03).em

/**
 * The contact row. Size is derived from measurement, not guesswork: cap height on the press-kit
 * render is 29.66pt and Montserrat's capHeight is 0.700em, giving 42sp. That puts the cap height at
 * almost exactly one third of the 89dp row, which matches the original's proportions.
 *
 * Yo used a FIXED size with no shrink-to-fit — labels of 5 and 12 characters measure identical cap
 * heights — so this style is deliberately not auto-scaling either.
 */
val YoRowText = TextStyle(
    fontFamily = MontserratBold,
    fontWeight = FontWeight.Bold,
    fontSize = 42.sp,
    letterSpacing = REDRAW_WIDTH_COMPENSATION,
    textAlign = TextAlign.Center,
    color = YoPalette.OnColor,
)

/** Row labels that need to survive a long group name sit a notch smaller. */
val YoRowTextSmall = YoRowText.copy(fontSize = 30.sp)

/**
 * The wordmark: the literal characters "Yo" — capital Y, lowercase o, no period. Yo's guidelines
 * are explicit and unusually emphatic about this: «Please note to casing of the name: "Yo". Not YO,
 * yo, Yo!, YO!.» The website set it at 342px against a desktop viewport; on a phone that is ~88sp.
 */
val YoWordmark = TextStyle(
    fontFamily = MontserratBold,
    fontWeight = FontWeight.Bold,
    fontSize = 88.sp,
    letterSpacing = (-0.04).em,
    textAlign = TextAlign.Center,
    color = YoPalette.OnColor,
)

/** "It's that simple." — 42px against the wordmark's 342px, i.e. an eighth of it. */
val YoTagline = TextStyle(
    fontFamily = MontserratBold,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    letterSpacing = 0.05.em,
    textAlign = TextAlign.Center,
    color = YoPalette.OnColor,
)

/** Small all-caps label, used inside the menu sheet where the original had no equivalent. */
val YoLabel = TextStyle(
    fontFamily = MontserratBold,
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
    letterSpacing = 0.12.em,
    textAlign = TextAlign.Center,
    color = YoPalette.OnColor,
)

val YoBody = TextStyle(
    fontFamily = MontserratBold,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    color = YoPalette.OnColor,
)

val YoTypography = Typography(
    displayLarge = YoWordmark,
    titleLarge = YoRowText,
    titleMedium = YoLabel,
    bodyLarge = YoBody,
    bodyMedium = YoBody,
    labelLarge = YoLabel,
)
