package com.flexingg.goprohero9.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens

/**
 * ColorOS 17 Fluid Cloud (流体云) Dynamic Island Capsule.
 * Displays real-time GoPro HERO9 Black telemetry and status with spring physics morphing.
 */
@Composable
fun FluidCloudCapsule(
    cameraName: String,
    batteryPercent: Int,
    isStreaming: Boolean,
    isRecording: Boolean,
    isWifiConnected: Boolean,
    isBleConnected: Boolean,
    selectedClipDuration: Int,
    streamFps: Int,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val scale by animateFloatAsState(
        targetValue = if (isExpanded) 1.02f else 1.0f,
        animationSpec = LivingFlowTokens.SpringCloudMorph,
        label = "CloudScale"
    )

    LuminousGlassCard(
        modifier = modifier
            .scale(scale)
            .animateContentSize(animationSpec = androidx.compose.animation.core.spring(stiffness = 380f, dampingRatio = 0.7f))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { isExpanded = !isExpanded },
        shape = RoundedCornerShape(if (isExpanded) LivingFlowTokens.RadiusMedium else LivingFlowTokens.RadiusPill),
        thickness = GlassThickness.REGULAR
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact Header Row (Always visible)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live / Recording glowing indicator dot
                val dotColor = when {
                    isRecording -> LivingFlowTokens.LivingCrimson
                    isStreaming -> LivingFlowTokens.LivingEmerald
                    isBleConnected -> LivingFlowTokens.LivingCyan
                    else -> Color.Gray
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                // Camera Model & Status
                Column {
                    Text(
                        text = cameraName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            isRecording -> "REC • BUFFERING FULL-RES"
                            isStreaming -> "LIVE UDP • 720p$streamFps"
                            isWifiConnected -> "WI-FI READY • 5GHz"
                            isBleConnected -> "BLE PAIRED"
                            else -> "SEARCHING HERO9..."
                        },
                        color = if (isRecording) LivingFlowTokens.LivingCrimson else LivingFlowTokens.LivingCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Battery Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                        .background(Color(0x33FFFFFF))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "Battery",
                        tint = if (batteryPercent > 20) LivingFlowTokens.LivingEmerald else LivingFlowTokens.LivingAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$batteryPercent%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Expanded Fluid Cloud Telemetry Details
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x22FFFFFF))
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryItem(
                            icon = Icons.Default.BluetoothConnected,
                            label = "BLE Radio",
                            value = if (isBleConnected) "Connected" else "Standby",
                            valueColor = if (isBleConnected) LivingFlowTokens.LivingEmerald else Color.Gray
                        )
                        TelemetryItem(
                            icon = Icons.Default.Wifi,
                            label = "GoPro AP",
                            value = if (isWifiConnected) "10.5.5.9" else "Disconnected",
                            valueColor = if (isWifiConnected) LivingFlowTokens.LivingEmerald else Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryItem(
                            icon = Icons.Default.Videocam,
                            label = "HindSight Master",
                            value = "4K 60fps / 5K",
                            valueColor = LivingFlowTokens.LivingCyan
                        )
                        TelemetryItem(
                            icon = Icons.Default.Speed,
                            label = "Clip Length",
                            value = "${selectedClipDuration}s Rolling",
                            valueColor = LivingFlowTokens.LivingAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap pill again to collapse Living Cloud",
                        color = Color(0x66FFFFFF),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = valueColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                color = Color(0x88FFFFFF),
                fontSize = 10.sp
            )
            Text(
                text = value,
                color = valueColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
