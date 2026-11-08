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
 * Implements ColorOS 17 multi-layer frosted effect with 1px top-lit luminous contour stroke.
 */
@Composable
fun LuminousGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LivingFlowTokens.RadiusMedium),
    thickness: GlassThickness = GlassThickness.REGULAR,
    elevation: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor = when (thickness) {
        GlassThickness.THIN -> LivingFlowTokens.DarkGlassBackgroundThin
        GlassThickness.REGULAR -> LivingFlowTokens.DarkGlassBackgroundRegular
        GlassThickness.THICK -> LivingFlowTokens.DarkGlassBackgroundThick
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0x66000000),
                spotColor = Color(0x99000000)
            )
            .clip(shape)
            .background(bgColor)
            .border(
                width = 1.dp,
                brush = LivingFlowTokens.LuminousContourDark,
                shape = shape
            )
            .padding(1.dp),
        content = content
    )
}
