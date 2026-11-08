package com.flexingg.goprohero9.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens

/**
 * Living Flow Horizon Level Indicator.
 * Renders precision pitch and roll horizon guides over the live GoPro viewfinder.
 */
@Composable
fun HorizonIndicator(
    rollDegrees: Float = 0f,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val guideLength = 60.dp.toPx()
        val gap = 20.dp.toPx()

        // Center reticle dot
        drawCircle(
            color = LivingFlowTokens.LivingCyan,
            radius = 3.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        // Left horizon arm
        drawLine(
            color = if (kotlin.math.abs(rollDegrees) < 1.0f) LivingFlowTokens.LivingEmerald else Color(0x99FFFFFF),
            start = Offset(centerX - gap - guideLength, centerY),
            end = Offset(centerX - gap, centerY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Right horizon arm
        drawLine(
            color = if (kotlin.math.abs(rollDegrees) < 1.0f) LivingFlowTokens.LivingEmerald else Color(0x99FFFFFF),
            start = Offset(centerX + gap, centerY),
            end = Offset(centerX + gap + guideLength, centerY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
