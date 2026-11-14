package com.flexingg.goprohero9.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorsFactory
import com.flexingg.goprohero9.ui.components.ClipPlayerDialog
import com.flexingg.goprohero9.ui.components.GlassThickness
import com.flexingg.goprohero9.ui.components.LuminousGlassCard
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import com.flexingg.goprohero9.viewmodel.SavedClip

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaGallerySheet(
    clips: List<SavedClip>,
    tsExtractorsFactory: ExtractorsFactory,
    onExportClip: (SavedClip) -> Unit,
    onShareClip: (SavedClip) -> Unit,
    onDeleteClip: (SavedClip) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedClipForPlayback by remember { mutableStateOf<SavedClip?>(null) }

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
                    items(clips, key = { it.localPath }) { clip ->
                        ClipItemRow(
                            clip = clip,
                            onPlay = { selectedClipForPlayback = clip },
                            onExport = { onExportClip(clip) },
                            onShare = { onShareClip(clip) },
                            onDelete = { onDeleteClip(clip) }
                        )
                    }
                }
            }
        }
    }

    selectedClipForPlayback?.let { clip ->
        ClipPlayerDialog(
            clip = clip,
            tsExtractorsFactory = tsExtractorsFactory,
            onDismiss = { selectedClipForPlayback = null },
            onDownload = { onExportClip(clip) },
            onShare = { onShareClip(clip) }
        )
    }
}

@Composable
private fun ClipItemRow(
    clip: SavedClip,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    LuminousGlassCard(
        shape = RoundedCornerShape(LivingFlowTokens.RadiusSmall),
        thickness = GlassThickness.REGULAR,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (clip.isFullResMaster) LivingFlowTokens.LivingCyan else LivingFlowTokens.LivingEmerald)
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (clip.isFullResMaster) Icons.Default.HighQuality else Icons.Default.PlayArrow,
                    contentDescription = "Play Clip",
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = clip.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${if (clip.isFullResMaster) "4K Master • " else "Instant Preview • "}${clip.fileSizeFormatted}",
                    color = if (clip.isFullResMaster) LivingFlowTokens.LivingCyan else LivingFlowTokens.LivingEmerald,
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = onExport, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Save to Phone",
                    tint = LivingFlowTokens.LivingCyan,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share Clip",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Clip",
                    tint = Color(0x99FF8A80),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}
