package hr.theshop.yo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Yo had exactly one appearance: white Montserrat Bold on flat saturated colour. There was no light
 * mode, no dark mode and no dynamic colour, so this theme ignores the system setting rather than
 * inventing a second look the original never had.
 *
 * Built on [darkColorScheme] because every surface is a saturated colour carrying white text, which
 * gives Material's own defaults the right contrast assumptions.
 */
private val YoColorScheme = darkColorScheme(
    primary = YoPalette.Alizarin,
    onPrimary = YoPalette.OnColor,
    secondary = YoPalette.Amethyst,
    onSecondary = YoPalette.OnColor,
    background = YoPalette.Amethyst,
    onBackground = YoPalette.OnColor,
    surface = YoPalette.Amethyst,
    onSurface = YoPalette.OnColor,
    surfaceVariant = YoPalette.Amethyst,
    onSurfaceVariant = YoPalette.OnColor,
    error = YoPalette.Alizarin,
    onError = YoPalette.OnColor,
)

@Composable
fun YoTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = YoColorScheme,
        typography = YoTypography,
        content = content,
    )
}
