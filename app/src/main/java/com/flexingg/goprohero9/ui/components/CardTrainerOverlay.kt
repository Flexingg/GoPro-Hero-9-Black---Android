package com.flexingg.goprohero9.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexingg.goprohero9.ml.CardRank
import com.flexingg.goprohero9.ml.CardSuit
import com.flexingg.goprohero9.ml.CardTrainingStore
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import kotlin.math.min

/**
 * Interactive Card Training Overlay:
 * 1. Displays a resizable box outline over the live GoPro stream for the card's corner index.
 * 2. Lets the user snap a frame, inspect the cropped patch, and tag Rank + Suit.
 * 3. Saves few-shot templates to CardTrainingStore for immediate live & VAR recognition.
 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardTrainerOverlay(
    videoWidth: Int,
    videoHeight: Int,
    onSnapFrame: () -> Bitmap?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val trainedSamples by CardTrainingStore.samplesFlow.collectAsState()

    var boxWidthDp by remember { mutableFloatStateOf(78f) }
    var snappedCrop by remember { mutableStateOf<Bitmap?>(null) }
    var selectedRank by remember { mutableStateOf(CardRank.ACE) }
    var selectedSuit by remember { mutableStateOf(CardSuit.SPADES) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showSamplesList by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x55000000))
    ) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        // Guide box centered in upper-middle area above the bottom controls
        val boxW = with(density) { boxWidthDp.dp.toPx() }
        val boxH = boxW * 1.45f
        val boxCenterX = containerW * 0.5f
        val boxCenterY = containerH * 0.36f
        val boxLeft = boxCenterX - boxW * 0.5f
        val boxTop = boxCenterY - boxH * 0.5f

        // Draw target box outline & rank/suit divider line
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = LivingFlowTokens.LivingCyan,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx())
            )
            // Midline separating Rank (top) and Suit pip (bottom)
            val midY = boxTop + boxH * 0.52f
            drawLine(
                color = LivingFlowTokens.LivingEmerald.copy(alpha = 0.85f),
                start = Offset(boxLeft + 6f, midY),
                end = Offset(boxLeft + boxW - 6f, midY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )
        }

        // Top Bar: Trainer Title + Trained Count + Close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE00A0E16))
                .border(1.dp, LivingFlowTokens.LuminousContourDark, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Card AI Trainer",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusMessage ?: "Align card top-left corner inside box",
                    color = LivingFlowTokens.LivingCyan,
                    fontSize = 11.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (showSamplesList) LivingFlowTokens.LivingCyan else Color(0x33FFFFFF))
                        .clickable { showSamplesList = !showSamplesList }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "${trainedSamples.size} trained",
                        color = if (showSamplesList) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = {
                        snappedCrop?.recycle()
                        snappedCrop = null
                        onClose()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Trainer", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Optional Manage Trained Samples Drawer
        if (showSamplesList) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 112.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEE0B101A))
                    .border(1.dp, LivingFlowTokens.LuminousContourDark, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Saved Card Templates", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (trainedSamples.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x33FF4D6D))
                                .clickable {
                                    CardTrainingStore.clearAll(context)
                                    statusMessage = "Cleared all trained samples"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFFF6B86), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All", color = Color(0xFFFF6B86), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (trainedSamples.isEmpty()) {
                    Text("No cards trained yet. Snap a card corner below!", color = Color(0xFF94A3B8), fontSize = 12.sp)
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        trainedSamples.forEach { sample ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x28FFFFFF))
                                    .border(1.dp, if (sample.suit.isRed) Color(0xFFFF4D6D) else LivingFlowTokens.LivingCyan, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sample.label,
                                    color = if (sample.suit.isRed) Color(0xFFFF6B86) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFCBD5E1),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { CardTrainingStore.removeSample(context, sample.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Controls Panel: either Snap Mode or Classify & Save Mode
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xEB0A0E16))
                .border(1.dp, LivingFlowTokens.LuminousContourDark, RoundedCornerShape(24.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val currentCrop = snappedCrop
            if (currentCrop == null) {
                // Step 1: Adjust box size & Snap
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Box Size", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = boxWidthDp,
                        onValueChange = { boxWidthDp = it },
                        valueRange = 46f..145f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = LivingFlowTokens.LivingCyan,
                            activeTrackColor = LivingFlowTokens.LivingCyan,
                            inactiveTrackColor = Color(0x33FFFFFF)
                        )
                    )
                    Text("${boxWidthDp.toInt()}dp", color = LivingFlowTokens.LivingCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(LivingFlowTokens.LivingCyan)
                        .clickable {
                            val fullBmp = onSnapFrame()
                            if (fullBmp != null) {
                                try {
                                    // Map screen box coordinates to fitted video bitmap coordinates
                                    val vw = if (videoWidth > 0) videoWidth.toFloat() else fullBmp.width.toFloat()
                                    val vh = if (videoHeight > 0) videoHeight.toFloat() else fullBmp.height.toFloat()
                                    val scale = min(containerW / vw, containerH / vh)
                                    val drawnW = vw * scale
                                    val drawnH = vh * scale
                                    val offsetX = (containerW - drawnW) * 0.5f
                                    val offsetY = (containerH - drawnH) * 0.5f

                                    val normLeft = ((boxLeft - offsetX) / drawnW).coerceIn(0f, 0.98f)
                                    val normTop = ((boxTop - offsetY) / drawnH).coerceIn(0f, 0.98f)
                                    val normRight = ((boxLeft + boxW - offsetX) / drawnW).coerceIn(normLeft + 0.02f, 1f)
                                    val normBottom = ((boxTop + boxH - offsetY) / drawnH).coerceIn(normTop + 0.02f, 1f)

                                    val cropX = (normLeft * fullBmp.width).toInt().coerceIn(0, fullBmp.width - 2)
                                    val cropY = (normTop * fullBmp.height).toInt().coerceIn(0, fullBmp.height - 2)
                                    val cropW = ((normRight - normLeft) * fullBmp.width).toInt().coerceIn(8, fullBmp.width - cropX)
                                    val cropH = ((normBottom - normTop) * fullBmp.height).toInt().coerceIn(8, fullBmp.height - cropY)

                                    snappedCrop = Bitmap.createBitmap(fullBmp, cropX, cropY, cropW, cropH)
                                    statusMessage = "Select Rank & Suit, then Save"
                                } finally {
                                    fullBmp.recycle()
                                }
                            } else {
                                statusMessage = "No video frame available yet"
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                        Text("Snap Card Corner", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Step 2: Show Snapped Preview + Rank/Suit Pickers + Save & Train
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        bitmap = currentCrop.asImageBitmap(),
                        contentDescription = "Snapped Card Corner",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(width = 58.dp, height = 84.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                            .border(1.5.dp, LivingFlowTokens.LivingCyan, RoundedCornerShape(10.dp))
                    )

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Classify as: ${selectedRank.symbol}${selectedSuit.symbol}",
                            color = if (selectedSuit.isRed) Color(0xFFFF6B86) else Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        // Suit Selector Row
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CardSuit.entries.forEach { suit ->
                                val isSel = suit == selectedSuit
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) (if (suit.isRed) Color(0xFFFF4D6D) else LivingFlowTokens.LivingCyan) else Color(0x28FFFFFF))
                                        .clickable { selectedSuit = suit },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = suit.symbol,
                                        color = if (isSel) Color.Black else if (suit.isRed) Color(0xFFFF6B86) else Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Rank Selector FlowRow
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    CardRank.entries.forEach { rank ->
                        val isSel = rank == selectedRank
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) LivingFlowTokens.LivingCyan else Color(0x28FFFFFF))
                                .clickable { selectedRank = rank }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = rank.symbol,
                                color = if (isSel) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Retake & Save Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.38f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x28FFFFFF))
                            .clickable {
                                currentCrop.recycle()
                                snappedCrop = null
                                statusMessage = "Align card top-left corner inside box"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Retake", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.62f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LivingFlowTokens.LivingEmerald)
                            .clickable {
                                val saved = CardTrainingStore.addSampleFromBitmap(
                                    context = context,
                                    cornerBitmap = currentCrop,
                                    rank = selectedRank,
                                    suit = selectedSuit
                                )
                                currentCrop.recycle()
                                snappedCrop = null
                                statusMessage = "Trained ${saved.label}! Snap next card"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Text("Save & Train", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
