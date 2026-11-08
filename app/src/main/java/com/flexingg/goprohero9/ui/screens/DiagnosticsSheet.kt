package com.flexingg.goprohero9.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexingg.goprohero9.ui.components.GlassThickness
import com.flexingg.goprohero9.ui.components.LuminousGlassCard
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import com.flexingg.goprohero9.viewmodel.DiagnosticsState
import com.flexingg.goprohero9.viewmodel.UiState

@Composable
fun DiagnosticsSheet(
    uiState: UiState,
    onClose: () -> Unit,
    onSendHeartbeat: () -> Unit,
    onRestartStream: () -> Unit,
    onToggleUdpMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val diag = uiState.diagnostics
    val scrollState = rememberScrollState()

    LuminousGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(LivingFlowTokens.RadiusLarge),
        thickness = GlassThickness.THICK
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = "Diagnostics",
                        tint = LivingFlowTokens.LivingCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Live Stream & Network Inspector",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Camera Recording Constraint Notice (OpenGoPro #352)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33FFB300))
                    .border(1.dp, Color(0x66FFB300), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "GoPro HERO9 hardware stops preview streaming whenever recording to SD card begins. Camera must be in standby Video mode to receive live preview.",
                        color = Color(0xFFFFECB3),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // 1. UDP Ingest & Relay Section
            SectionHeader(title = "UDP Ingest & Network Routing")
            DiagnosticRow(
                label = "Routing Mode",
                value = if (diag.isDirectUdpMode) "Direct (udp://0.0.0.0:8554)" else "Relay Bound (127.0.0.1:8555)"
            )
            DiagnosticRow(
                label = "Wi-Fi Socket Bound",
                value = if (diag.isRelayBound) "Bound to Wi-Fi Interface" else "Unbound / Default",
                valueColor = if (diag.isRelayBound) LivingFlowTokens.LivingEmerald else Color(0xFFFFB300)
            )
            DiagnosticRow(
                label = "Packets Ingested",
                value = "${diag.packetsReceived} pkts (${diag.bytesReceived / 1024} KB)",
                valueColor = if (diag.packetsReceived > 0) LivingFlowTokens.LivingCyan else Color.White
            )
            DiagnosticRow(
                label = "Live Bitrate",
                value = "${(diag.bitrateKbps / 1000.0).let { "%.2f".format(it) }} Mbps"
            )
            DiagnosticRow(
                label = "MPEG-TS Sync (0x47)",
                value = if (diag.hasTsSyncByte) "Valid 0x47 Marker" else if (diag.packetsReceived > 0) "No Sync Byte" else "Waiting...",
                valueColor = if (diag.hasTsSyncByte) LivingFlowTokens.LivingEmerald else Color(0xFFFFB300)
            )
            DiagnosticRow(
                label = "Relay Status",
                value = diag.relayStatus
            )

            // 2. HTTP Camera API Section
            SectionHeader(title = "GoPro HTTP API Status")
            DiagnosticRow(
                label = "Camera Gateway IP",
                value = "${diag.gatewayIp}:8080"
            )
            DiagnosticRow(
                label = "Last HTTP Command",
                value = diag.lastHttpLog,
                valueColor = if (diag.lastHttpLog.contains("200") || diag.lastHttpLog.contains("409"))
                    LivingFlowTokens.LivingEmerald else Color(0xFFFF8A80)
            )

            // 3. Media3 ExoPlayer Section
            SectionHeader(title = "Media3 Video Player")
            DiagnosticRow(
                label = "First Frame Rendered",
                value = if (uiState.isFirstFrameRendered) "YES (Video Active)" else "NO (Buffering)",
                valueColor = if (uiState.isFirstFrameRendered) LivingFlowTokens.LivingEmerald else Color(0xFFFFB300)
            )
            DiagnosticRow(
                label = "Detected Resolution",
                value = uiState.streamResolution.ifEmpty { "Awaiting video stream..." }
            )
            if (diag.lastPlayerError.isNotEmpty()) {
                DiagnosticRow(
                    label = "Player Error",
                    value = diag.lastPlayerError,
                    valueColor = LivingFlowTokens.LivingCrimson
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Heartbeat Ping Button
                DiagnosticActionButton(
                    text = "Send Heartbeat",
                    icon = Icons.AutoMirrored.Filled.Send,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSendHeartbeat()
                        Toast.makeText(context, "Sent keepalive UDP ping to ${diag.gatewayIp}:8554", Toast.LENGTH_SHORT).show()
                    }
                )

                // Restart Stream Button
                DiagnosticActionButton(
                    text = "Restart Stream",
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onRestartStream()
                        Toast.makeText(context, "Restarting preview stream...", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Toggle Relay vs Direct
                DiagnosticActionButton(
                    text = if (diag.isDirectUdpMode) "Switch to Relay" else "Switch to Direct",
                    icon = Icons.Default.SwapHoriz,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onToggleUdpMode()
                    }
                )

                // Copy Diagnostic Dump
                DiagnosticActionButton(
                    text = "Copy Logs",
                    icon = Icons.Default.ContentCopy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val dump = buildString {
                            appendLine("=== GoPro HERO9 Stream Diagnostics ===")
                            appendLine("Gateway IP: ${diag.gatewayIp}")
                            appendLine("Bound Network: ${diag.boundNetworkInfo}")
                            appendLine("Mode: ${if (diag.isDirectUdpMode) "Direct (8554)" else "Relay (127.0.0.1:8555)"}")
                            appendLine("Relay Bound: ${diag.isRelayBound}")
                            appendLine("Packets Received: ${diag.packetsReceived} (${diag.bytesReceived} bytes)")
                            appendLine("Bitrate: ${(diag.bitrateKbps / 1000.0).let { "%.2f".format(it) }} Mbps")
                            appendLine("TS Sync Valid: ${diag.hasTsSyncByte}")
                            appendLine("Relay Status: ${diag.relayStatus}")
                            appendLine("HTTP Log: ${diag.lastHttpLog}")
                            appendLine("First Frame Rendered: ${uiState.isFirstFrameRendered}")
                            appendLine("Stream Resolution: ${uiState.streamResolution}")
                            appendLine("Player Error: ${diag.lastPlayerError}")
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("GoPro Diagnostics", dump))
                        Toast.makeText(context, "Diagnostics copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = LivingFlowTokens.LivingCyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0x99FFFFFF),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DiagnosticActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(LivingFlowTokens.RadiusPill))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
