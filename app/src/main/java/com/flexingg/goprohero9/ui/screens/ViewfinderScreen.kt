package com.flexingg.goprohero9.ui.screens

import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.flexingg.goprohero9.ui.components.ClipSelector
import com.flexingg.goprohero9.ui.components.FluidCloudCapsule
import com.flexingg.goprohero9.ui.components.GlassThickness
import com.flexingg.goprohero9.ui.components.HorizonIndicator
import com.flexingg.goprohero9.ui.components.LuminousGlassCard
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import com.flexingg.goprohero9.viewmodel.GoProViewModel

@OptIn(UnstableApi::class)
@Composable
fun ViewfinderScreen(
    viewModel: GoProViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var isGalleryOpen by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF040608))
    ) {
        // 1. Live Video Viewfinder Surface (Media3 ExoPlayer PlayerView)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    player = viewModel.playerManager.initializePlayer()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Rule of Thirds subtle overlay grid & Horizon
        HorizonIndicator(
            modifier = Modifier.align(Alignment.Center)
        )

        // 2. Top ColorOS 17 Fluid Cloud (流体云) Dynamic Island Capsule
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            FluidCloudCapsule(
                cameraName = uiState.cameraName,
                batteryPercent = uiState.batteryPercent,
                isStreaming = uiState.isStreaming,
                isRecording = uiState.isRecording,
                isWifiConnected = uiState.isWifiConnected,
                isBleConnected = uiState.isBleConnected,
                selectedClipDuration = uiState.selectedClipDuration,
                streamFps = uiState.streamFps
            )
        }

        // 3. Connect prompt if not connected
        if (!uiState.isBleConnected && !uiState.isWifiConnected) {
            LuminousGlassCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                shape = RoundedCornerShape(LivingFlowTokens.RadiusMedium),
                thickness = GlassThickness.REGULAR
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = "Search",
                        tint = LivingFlowTokens.LivingCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Connect GoPro HERO9 Black",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Turn camera ON with Wireless Connections enabled.\nApp will pair via BLE and start 5GHz Wi-Fi streaming.",
                        color = Color(0xBBFFFFFF),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    LuminousActionButton(
                        text = "Scan & Pair Camera",
                        onClick = { viewModel.startDiscovery() }
                    )

                    // Fallback direct stream button if user is already on GoPro Wi-Fi
                    Text(
                        text = "Already on GoPro Wi-Fi? Start Stream",
                        color = LivingFlowTokens.LivingCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { viewModel.startLiveStream() }
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        // 4. Wi-Fi AP Provisioned Card (Displays credentials and direct Wi-Fi options)
        if (uiState.isBleConnected && !uiState.isWifiConnected && uiState.wifiApSsid.isNotEmpty()) {
            LuminousGlassCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                shape = RoundedCornerShape(LivingFlowTokens.RadiusMedium),
                thickness = GlassThickness.THICK
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi",
                        tint = LivingFlowTokens.LivingEmerald,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Camera Wi-Fi AP Ready",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x33000000))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SSID: ${uiState.wifiApSsid}",
                                color = LivingFlowTokens.LivingCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Password: ${uiState.wifiApPassword}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                .background(Color(0x33FFFFFF))
                                .clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("GoPro Password", uiState.wifiApPassword)
                                    clipboard?.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, "Password copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Copy",
                                color = LivingFlowTokens.LivingCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "GoPro Wi-Fi has no external internet (normal).\nAndroid routes traffic via 5G unless bound. Once joined, tap 'Start Stream' below to bind and stream directly.",
                        color = Color(0xBBFFFFFF),
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            LuminousSmallButton(
                                text = "Wi-Fi Settings",
                                icon = Icons.Default.Settings,
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                }
                            )
                        }
                        Box(modifier = Modifier.weight(1.2f)) {
                            LuminousSmallButton(
                                text = "Bind & Stream",
                                icon = Icons.Default.Videocam,
                                isAccent = true,
                                onClick = { viewModel.startLiveStream() }
                            )
                        }
                    }
                }
            }
        }

        // 4b. Stream Ready / Retry Card (When Wi-Fi is connected but stream is not active yet)
        if (uiState.isWifiConnected && !uiState.isStreaming) {
            LuminousGlassCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                shape = RoundedCornerShape(LivingFlowTokens.RadiusMedium),
                thickness = GlassThickness.THICK
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Stream",
                        tint = LivingFlowTokens.LivingCyan,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Camera Connected",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = uiState.statusMessage,
                        color = LivingFlowTokens.LivingCyan,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "If GoPro LCD shows 'Pairing Successful', press the camera's side Mode button once to return to video.",
                        color = Color(0xBBFFFFFF),
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    LuminousActionButton(
                        text = "Start Live Viewfinder",
                        onClick = { viewModel.startLiveStream() }
                    )
                }
            }
        }

        // 5. Status Notification Pill
        if (uiState.statusMessage.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 115.dp, start = 20.dp, end = 20.dp)
            ) {
                LuminousGlassCard(
                    shape = RoundedCornerShape(LivingFlowTokens.RadiusPill),
                    thickness = GlassThickness.THIN
                ) {
                    Text(
                        text = uiState.statusMessage,
                        color = LivingFlowTokens.LivingCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 6. Download / Sync Progress Bar
        if (uiState.isClipping || uiState.clipDownloadProgress > 0f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = if (uiState.clipDownloadProgress > 0f)
                        "Syncing 4K Master Clip: ${(uiState.clipDownloadProgress * 100).toInt()}%"
                    else "Flushing HindSight buffer to camera...",
                    color = LivingFlowTokens.LivingCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                LinearProgressIndicator(
                    progress = { uiState.clipDownloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = LivingFlowTokens.LivingCyan,
                    trackColor = Color(0x44FFFFFF)
                )
            }
        }

        // 7. Bottom Living Flow Control Deck
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 36.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Clip duration pills (15s, 30s, 60s)
            ClipSelector(
                selectedDuration = uiState.selectedClipDuration,
                onDurationSelected = { viewModel.setClipDuration(it) }
            )

            // Primary Action Row: Gallery | Giant Retrospective CLIP | Stream Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery button
                LuminousIconButton(
                    icon = Icons.Default.PhotoLibrary,
                    badgeCount = uiState.savedClips.size,
                    onClick = { isGalleryOpen = true }
                )

                // Giant Retrospective CLIP Trigger Button
                RetrospectiveClipButton(
                    durationSeconds = uiState.selectedClipDuration,
                    isClipping = uiState.isClipping,
                    onClick = { viewModel.triggerRetrospectiveClip() }
                )

                // Stream Toggle button
                LuminousIconButton(
                    icon = if (uiState.isStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                    tint = if (uiState.isStreaming) LivingFlowTokens.LivingCrimson else LivingFlowTokens.LivingEmerald,
                    onClick = {
                        if (uiState.isStreaming) viewModel.stopLiveStream() else viewModel.startLiveStream()
                    }
                )
            }
        }

        // 8. Media Gallery Bottom Sheet
        AnimatedVisibility(
            visible = isGalleryOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            MediaGallerySheet(
                clips = uiState.savedClips,
                onClose = { isGalleryOpen = false }
            )
        }
    }
}

/**
 * Giant Retrospective "CLIP" Shutter with Living Flow Spring feedback.
 */
@Composable
private fun RetrospectiveClipButton(
    durationSeconds: Int,
    isClipping: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = if (isPressed) LivingFlowTokens.SpringPress else LivingFlowTokens.SpringRebound,
        label = "ClipScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .size(84.dp)
            .shadow(
                elevation = 20.dp,
                shape = CircleShape,
                ambientColor = LivingFlowTokens.LivingCyan,
                spotColor = LivingFlowTokens.LivingCyan
            )
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF26E6FF),
                        Color(0xFF00B0FF),
                        Color(0xFF0055FF)
                    )
                )
            )
            .border(
                width = 3.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color(0x66FFFFFF), Color(0x00FFFFFF))
                ),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (!isClipping) onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (isClipping) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = "Clip",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "CLIP ${durationSeconds}s",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun LuminousIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = if (isPressed) LivingFlowTokens.SpringPress else LivingFlowTokens.SpringRebound,
        label = "ButtonScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .size(54.dp)
            .clip(CircleShape)
            .background(LivingFlowTokens.DarkGlassBackgroundRegular)
            .border(1.dp, LivingFlowTokens.LuminousContourDark, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )

        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(LivingFlowTokens.LivingCyan),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$badgeCount",
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LuminousActionButton(
    text: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = if (isPressed) LivingFlowTokens.SpringPress else LivingFlowTokens.SpringRebound,
        label = "ActionScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
            .background(LivingFlowTokens.LivingCyan)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LuminousSmallButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = if (isPressed) LivingFlowTokens.SpringPress else LivingFlowTokens.SpringRebound,
        label = "SmallBtnScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
            .background(if (isAccent) LivingFlowTokens.LivingCyan else Color(0x33FFFFFF))
            .border(
                1.dp,
                if (isAccent) LivingFlowTokens.LuminousContourLight else LivingFlowTokens.LuminousContourDark,
                RoundedCornerShape(LivingFlowTokens.RadiusPill)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isAccent) Color.Black else Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = if (isAccent) Color.Black else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
