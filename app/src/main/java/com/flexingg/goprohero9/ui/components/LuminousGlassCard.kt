package com.flexingg.goprohero9.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens

enum class GlassThickness {
    THIN,
    REGULAR,
    THICK
}

/**
 * Living Flow Luminous Glass Surface.
 * Implements ColorOS 17 multi-layer frosted effect with refractive sheen and 1px luminous contour stroke.
 */
@Composable
fun LuminousGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LivingFlowTokens.RadiusMedium),
    thickness: GlassThickness = GlassThickness.REGULAR,
    elevation: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor = when (thickness) {
        GlassThickness.THIN -> LivingFlowTokens.DarkGlassBackgroundThin
        GlassThickness.REGULAR -> LivingFlowTokens.DarkGlassBackgroundRegular
        GlassThickness.THICK -> LivingFlowTokens.DarkGlassBackgroundThick
    }

    val frostedSheen = Brush.linearGradient(
        colors = listOf(
            Color(0x1EFFFFFF),
            Color(0x06FFFFFF),
            Color(0x1000E5FF),
            Color(0x12000000)
        )
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0x88000000),
                spotColor = Color(0xBB000000)
            )
            .clip(shape)
            .background(bgColor)
            .background(frostedSheen)
            .border(
                width = 1.dp,
                brush = LivingFlowTokens.LuminousContourDark,
                shape = shape
            )
            .padding(1.dp),
        content = content
    )
}
