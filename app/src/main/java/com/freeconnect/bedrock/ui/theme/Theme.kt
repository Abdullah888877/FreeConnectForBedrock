package com.freeconnect.bedrock.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark colour scheme — the app default.
 * Uses Minecraft-inspired greens with a deep background.
 */
private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary              = GreenPrimary,
    onPrimary            = GreenOnPrimary,
    primaryContainer     = GreenContainer,
    onPrimaryContainer   = GreenOnContainer,
    secondary            = TealSecondary,
    onSecondary          = TealOnSecondary,
    secondaryContainer   = TealContainer,
    onSecondaryContainer = TealOnContainer,
    error                = RedError,
    onError              = RedOnError,
    errorContainer       = RedErrorContainer,
    onErrorContainer     = RedOnErrorContainer,
    background           = DarkBackground,
    onBackground         = DarkOnBackground,
    surface              = DarkSurface,
    onSurface            = DarkOnSurface,
    surfaceVariant       = DarkSurfaceVariant,
    onSurfaceVariant     = DarkOnSurfaceVariant,
    outline              = DarkOutline
)

/**
 * Light colour scheme — user can toggle to this in Settings.
 */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary              = GreenPrimaryLight,
    onPrimary            = GreenOnPrimaryLight,
    primaryContainer     = GreenContainerLight,
    onPrimaryContainer   = GreenOnContainerLight,
    secondary            = TealSecondaryLight,
    onSecondary          = TealOnSecondaryLight,
    secondaryContainer   = TealContainerLight,
    onSecondaryContainer = TealOnContainerLight,
    background           = LightBackground,
    onBackground         = LightOnBackground,
    surface              = LightSurface,
    onSurface            = LightOnSurface,
    surfaceVariant       = LightSurfaceVariant,
    onSurfaceVariant     = LightOnSurfaceVariant,
    outline              = LightOutline
)

/**
 * Root theme composable.
 *
 * @param darkTheme Whether to use the dark colour scheme.
 *                  Defaults to true (dark by default per spec).
 */
@Composable
fun FreeConnectTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AppTypography,
        content     = content
    )
}
