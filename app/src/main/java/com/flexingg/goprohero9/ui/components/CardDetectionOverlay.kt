package com.flexingg.goprohero9.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexingg.goprohero9.ml.CardDetection
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws real-time playing card bounding boxes, corner index highlights, and Rank/Suit classification badges
 * aligned to the fitted video viewport.
 */
@Composable
fun CardDetectionOverlay(
    detections: List<CardDetection>,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier
) {
    if (detections.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        // Compute exact fitted video rectangle matching PlayerView resize_mode="fit"
        val vw = if (videoWidth > 0) videoWidth.toFloat() else 16f
        val vh = if (videoHeight > 0) videoHeight.toFloat() else 9f
        val scale = min(containerW / vw, containerH / vh)
        val drawnW = vw * scale
        val drawnH = vh * scale
        val offsetX = (containerW - drawnW) * 0.5f
        val offsetY = (containerH - drawnH) * 0.5f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 2.5.dp.toPx()
            val bracketLen = 14.dp.toPx()

            for (det in detections) {
                val left = offsetX + det.boundingBox.left * drawnW
                val top = offsetY + det.boundingBox.top * drawnH
                val right = offsetX + det.boundingBox.right * drawnW
                val bottom = offsetY + det.boundingBox.bottom * drawnH
                val w = (right - left).coerceAtLeast(4f)
                val h = (bottom - top).coerceAtLeast(4f)

                val accent = if (det.suit.isRed) Color(0xFFFF4D6D) else LivingFlowTokens.LivingCyan

                // Subtle translucent fill
                drawRect(
                    color = accent.copy(alpha = 0.10f),
                    topLeft = Offset(left, top),
                    size = Size(w, h)
                )

                // Thin full outline
                drawRect(
                    color = accent.copy(alpha = 0.45f),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 1.dp.toPx())
                )

                // Crisp VAR corner reticles
                val bLen = min(bracketLen, min(w, h) * 0.35f)
                // Top-left
                drawLine(accent, Offset(left, top), Offset(left + bLen, top), strokePx, StrokeCap.Round)
                drawLine(accent, Offset(left, top), Offset(left, top + bLen), strokePx, StrokeCap.Round)
                // Top-right
                drawLine(accent, Offset(right, top), Offset(right - bLen, top), strokePx, StrokeCap.Round)
                drawLine(accent, Offset(right, top), Offset(right, top + bLen), strokePx, StrokeCap.Round)
                // Bottom-left
                drawLine(accent, Offset(left, bottom), Offset(left + bLen, bottom), strokePx, StrokeCap.Round)
                drawLine(accent, Offset(left, bottom), Offset(left, bottom - bLen), strokePx, StrokeCap.Round)
                // Bottom-right
                drawLine(accent, Offset(right, bottom), Offset(right - bLen, bottom), strokePx, StrokeCap.Round)
                drawLine(accent, Offset(right, bottom), Offset(right, bottom - bLen), strokePx, StrokeCap.Round)

                // Corner index highlight box (where Rank/Suit was read)
                det.cornerBox?.let { cb ->
                    val cLeft = offsetX + cb.left * drawnW
                    val cTop = offsetY + cb.top * drawnH
                    val cW = ((cb.right - cb.left) * drawnW).coerceAtLeast(4f)
                    val cH = ((cb.bottom - cb.top) * drawnH).coerceAtLeast(4f)
                    drawRect(
                        color = LivingFlowTokens.LivingEmerald.copy(alpha = 0.85f),
                        topLeft = Offset(cLeft, cTop),
                        size = Size(cW, cH),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }

        // Floating Rank + Suit + Confidence label pills
        for (det in detections) {
            val leftPx = offsetX + det.boundingBox.left * drawnW
            val topPx = offsetY + det.boundingBox.top * drawnH
            val pillOffsetY = if (topPx > with(density) { 28.dp.toPx() }) {
                (topPx - with(density) { 24.dp.toPx() }).roundToInt()
            } else {
                (topPx + with(density) { 4.dp.toPx() }).roundToInt()
            }
            val pillOffsetX = leftPx.roundToInt().coerceIn(0, (containerW - 120f).toInt().coerceAtLeast(0))

            val accent = if (det.suit.isRed) Color(0xFFFF4D6D) else LivingFlowTokens.LivingCyan
            val confPct = (det.confidence * 100).roundToInt()

            Row(
                modifier = Modifier
                    .offset { IntOffset(pillOffsetX, pillOffsetY) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xDC080B12))
                    .border(1.dp, accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = det.label,
                    color = if (det.suit.isRed) Color(0xFFFF6B86) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "$confPct%",
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
