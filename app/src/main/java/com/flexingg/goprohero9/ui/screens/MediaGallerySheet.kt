package com.flexingg.goprohero9.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexingg.goprohero9.ui.components.GlassThickness
import com.flexingg.goprohero9.ui.components.LuminousGlassCard
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import com.flexingg.goprohero9.viewmodel.SavedClip

@Composable
fun MediaGallerySheet(
    clips: List<SavedClip>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    LuminousGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = LivingFlowTokens.RadiusLarge, topEnd = LivingFlowTokens.RadiusLarge),
        thickness = GlassThickness.THICK
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = "Gallery",
                        tint = LivingFlowTokens.LivingCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Captured Retrospective Clips",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (clips.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No clips saved yet.\nTap 'CLIP' during live stream to save retrospective buffer.",
                        color = Color(0x88FFFFFF),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(300.dp)
                ) {
                    items(clips) { clip ->
                        ClipItemRow(clip)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipItemRow(clip: SavedClip) {
    LuminousGlassCard(
        shape = RoundedCornerShape(LivingFlowTokens.RadiusSmall),
        thickness = GlassThickness.REGULAR,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (clip.isFullResMaster) LivingFlowTokens.LivingCyan else LivingFlowTokens.LivingEmerald),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (clip.isFullResMaster) Icons.Default.HighQuality else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = clip.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${if (clip.isFullResMaster) "4K Master • " else "Instant Preview • "}${clip.fileSizeFormatted}",
                    color = if (clip.isFullResMaster) LivingFlowTokens.LivingCyan else LivingFlowTokens.LivingEmerald,
                    fontSize = 12.sp
                )
            }
        }
    }
}
