package com.flexingg.goprohero9.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens

/**
 * Retrospective Buffer Duration Selector (15s, 30s, 60s).
 * Implements spring rebound physics and ColorOS 17 glowing capsule pill indicators.
 */
@Composable
fun ClipSelector(
    selectedDuration: Int,
    onDurationSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val durations = listOf(15, 30, 60)

    LuminousGlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(LivingFlowTokens.RadiusPill),
        thickness = GlassThickness.REGULAR
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            durations.forEach { seconds ->
                val isSelected = seconds == selectedDuration
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else 1.0f,
                    animationSpec = if (isPressed) LivingFlowTokens.SpringPress else LivingFlowTokens.SpringRebound,
                    label = "DurationScale"
                )

                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) LivingFlowTokens.LivingCyan else Color.Transparent,
                    label = "DurationBg"
                )

                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.Black else Color(0xCCFFFFFF),
                    label = "DurationText"
                )

                Box(
                    modifier = Modifier
                        .scale(scale)
                        .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                        .background(bgColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onDurationSelected(seconds) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${seconds}s",
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
