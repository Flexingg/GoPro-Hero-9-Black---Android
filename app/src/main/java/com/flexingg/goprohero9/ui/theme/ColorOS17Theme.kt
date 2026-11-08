package com.flexingg.goprohero9.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * ColorOS 17 "Living Flow Design" (流体设计) Theme Specification.
 * Reference: AI_Spec__Redesigning_Any_App_in_ColorOS_17_Living_Flow_Design.md
 */
object LivingFlowTokens {
    // Primary Vibrant living accent
    val LivingCyan = Color(0xFF00E5FF)
    val LivingEmerald = Color(0xFF00E676)
    val LivingAmber = Color(0xFFFFB300)
    val LivingCrimson = Color(0xFFFF3366)
    val LivingElectricBlue = Color(0xFF007AFF)

    // Frosted Glass Layer Colors (Dark & Light)
    val DarkGlassBackgroundThin = Color(0x33101520)
    val DarkGlassBackgroundRegular = Color(0x66101520)
    val DarkGlassBackgroundThick = Color(0xAA080B10)

    val LightGlassBackgroundThin = Color(0x44FFFFFF)
    val LightGlassBackgroundRegular = Color(0x88F0F4F8)
    val LightGlassBackgroundThick = Color(0xCCFFFFFF)

    // 1px Luminous Contour Strokes (Light & Dark)
    val LuminousContourDark = Brush.verticalGradient(
        colors = listOf(
            Color(0x55FFFFFF),
            Color(0x15FFFFFF),
            Color(0x05FFFFFF)
        )
    )
    val LuminousContourLight = Brush.verticalGradient(
        colors = listOf(
            Color(0xAAFFFFFF),
            Color(0x33FFFFFF),
            Color(0x10000000)
        )
    )

    // Corner Radii Tokens
    val RadiusSmall = 16.dp
    val RadiusMedium = 24.dp
    val RadiusLarge = 32.dp
    val RadiusPill = 999.dp

    // Spring Physics Specs as dictated by Living Flow:
    // Press: stiffness 800, damping 0.8
    val SpringPress = spring<Float>(
        stiffness = 800f,
        dampingRatio = 0.8f
    )
    // Rebound: stiffness 450, damping 0.55
    val SpringRebound = spring<Float>(
        stiffness = 450f,
        dampingRatio = 0.55f
    )
    // Dynamic Cloud Morphing: stiffness 380, damping 0.7
    val SpringCloudMorph = spring<Float>(
        stiffness = 380f,
        dampingRatio = 0.7f
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = LivingFlowTokens.LivingCyan,
    secondary = LivingFlowTokens.LivingEmerald,
    tertiary = LivingFlowTokens.LivingCrimson,
    background = Color(0xFF06080C),
    surface = Color(0xFF101520),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFF0F4F8),
    onSurface = Color(0xFFF0F4F8)
)

private val LightColorScheme = lightColorScheme(
    primary = LivingFlowTokens.LivingElectricBlue,
    secondary = LivingFlowTokens.LivingEmerald,
    tertiary = LivingFlowTokens.LivingCrimson,
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF10141A),
    onSurface = Color(0xFF10141A)
)

@Composable
fun ColorOS17Theme(
    darkTheme: Boolean = true, // Default to immersive dark viewfinder
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
