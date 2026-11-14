package com.flexingg.goprohero9.ui.components

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import com.flexingg.goprohero9.R
import com.flexingg.goprohero9.ml.CardDetection
import com.flexingg.goprohero9.ml.PlayingCardDetector
import com.flexingg.goprohero9.ui.theme.LivingFlowTokens
import com.flexingg.goprohero9.viewmodel.SavedClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

private enum class ReplaySpeed(val label: String, val speedMultiplier: Float) {
    ONE_FPS("1 FPS", 0f),
    QUARTER("0.25x", 0.25f),
    HALF("0.5x", 0.5f),
    NORMAL("1x", 1.0f),
    DOUBLE("2x", 2.0f)
}

@OptIn(UnstableApi::class)
@Composable
fun ClipPlayerDialog(
    clip: SavedClip,
    tsExtractorsFactory: ExtractorsFactory,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        com.flexingg.goprohero9.ml.CardTrainingStore.init(context)
    }

    var selectedSpeed by remember { mutableStateOf(ReplaySpeed.NORMAL) }
    var isVarPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var detectedFps by remember { mutableFloatStateOf(60f) }
    var videoWidth by remember { mutableIntStateOf(848) }
    var videoHeight by remember { mutableIntStateOf(480) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosMs by remember { mutableLongStateOf(0L) }

    var isCardAiEnabled by remember { mutableStateOf(true) }
    var varPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var frameDetections by remember { mutableStateOf<List<CardDetection>>(emptyList()) }
    var frameScanTrigger by remember { mutableIntStateOf(0) }

    var playerErrorMsg by remember { mutableStateOf<String?>(null) }

    val clipPlayer = remember(clip.localPath) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playerErrorMsg = error.errorCodeName
                }
            })
        }
    }

    LaunchedEffect(clip.localPath) {
        playerErrorMsg = null
        val src = File(clip.localPath)
        val targetFile = if (src.extension.equals("ts", ignoreCase = true)) {
            val cachedMp4 = File(context.cacheDir, "${src.nameWithoutExtension}_seekable_v3.mp4")
            if (!cachedMp4.exists() || cachedMp4.length() == 0L) {
                withContext(Dispatchers.IO) {
                    com.flexingg.goprohero9.buffer.TsToMp4Remuxer.remux(src, cachedMp4)
                }
            }
            if (cachedMp4.exists() && cachedMp4.length() > 0L) cachedMp4 else src
        } else {
            src
        }

        val uri = Uri.fromFile(targetFile)
        if (targetFile.extension.equals("ts", ignoreCase = true)) {
            val dataSourceFactory = DefaultDataSource.Factory(context)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.VIDEO_MP2T)
                .build()
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, tsExtractorsFactory)
                .createMediaSource(mediaItem)
            clipPlayer.setMediaSource(mediaSource)
        } else {
            clipPlayer.setMediaItem(MediaItem.fromUri(uri))
        }
        clipPlayer.prepare()
        clipPlayer.playWhenReady = true
    }

    val frameStepMs = remember(detectedFps) {
        if (detectedFps > 0f) (1000f / detectedFps).roundToLong().coerceAtLeast(16L) else 17L
    }

    fun stepFrame(direction: Int) {
        isVarPlaying = false
        clipPlayer.pause()
        val dur = clipPlayer.duration.coerceAtLeast(0L)
        val base = clipPlayer.currentPosition.coerceAtLeast(0L)
        val target = if (dur > 0L) {
            (base + (direction * frameStepMs)).coerceIn(0L, dur)
        } else {
            (base + (direction * frameStepMs)).coerceAtLeast(0L)
        }
        clipPlayer.seekTo(target)
        currentPosMs = target
        frameScanTrigger++
    }

    // Poll player position and format FPS smoothly
    LaunchedEffect(clipPlayer) {
        while (true) {
            if (!isScrubbing) {
                currentPosMs = clipPlayer.currentPosition.coerceAtLeast(0L)
            }
            val dur = clipPlayer.duration
            if (dur > 0L) durationMs = dur
            val fps = clipPlayer.videoFormat?.frameRate ?: -1f
            if (fps > 1f) detectedFps = fps
            delay(40L)
        }
    }

    // Handle 1 FPS stepping mode vs normal ExoPlayer speed mode
    LaunchedEffect(selectedSpeed, isVarPlaying, frameStepMs) {
        if (selectedSpeed == ReplaySpeed.ONE_FPS) {
            clipPlayer.pause()
            while (isVarPlaying && selectedSpeed == ReplaySpeed.ONE_FPS) {
                delay(1000L)
                val dur = clipPlayer.duration.coerceAtLeast(0L)
                val next = clipPlayer.currentPosition + frameStepMs
                val wrapped = if (dur > 0L && next >= dur) 0L else next
                clipPlayer.seekTo(wrapped)
                currentPosMs = wrapped
                frameScanTrigger++
            }
        } else {
            clipPlayer.setPlaybackSpeed(selectedSpeed.speedMultiplier)
            if (isVarPlaying) {
                if (clipPlayer.playbackState == Player.STATE_IDLE) {
                    clipPlayer.prepare()
                } else if (clipPlayer.playbackState == Player.STATE_ENDED) {
                    clipPlayer.seekTo(0L)
                }
                clipPlayer.playWhenReady = true
                clipPlayer.play()
            } else {
                clipPlayer.playWhenReady = false
                clipPlayer.pause()
            }
        }
    }

    // Run Playing Card AI detection on current VAR frame (on pause/step/scrub AND continuously during playback)
    LaunchedEffect(isCardAiEnabled, isVarPlaying, frameScanTrigger, currentPosMs / 160L, varPlayerView) {
        if (!isCardAiEnabled) {
            frameDetections = emptyList()
            return@LaunchedEffect
        }
        // Small settle delay after a seek/frame-step so TextureView renders the new frame
        if (!isVarPlaying || selectedSpeed == ReplaySpeed.ONE_FPS) {
            delay(65L)
        }
        val tv = PlayingCardDetector.findTextureView(varPlayerView)
        val bmp = tv?.getBitmap()
        if (bmp != null) {
            val detected = withContext(Dispatchers.Default) {
                try {
                    PlayingCardDetector.detect(bmp)
                } finally {
                    bmp.recycle()
                }
            }
            frameDetections = detected
        }
    }

    DisposableEffect(clip.localPath) {
        onDispose {
            clipPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF5040608))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Top Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(LivingFlowTokens.LivingCyan)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "VAR REPLAY",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Text(
                                text = clip.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${if (clip.isFullResMaster) "4K Master" else "Stream Clip"} • ${clip.fileSizeFormatted} • ${detectedFps.toInt()} fps",
                            color = if (clip.isFullResMaster) LivingFlowTokens.LivingCyan else LivingFlowTokens.LivingEmerald,
                            fontSize = 11.sp
                        )
                    }

                    // Card AI Toggle Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                            .background(if (isCardAiEnabled) LivingFlowTokens.LivingCyan else Color(0x33FFFFFF))
                            .border(
                                1.dp,
                                if (isCardAiEnabled) LivingFlowTokens.LivingCyan else Color(0x44FFFFFF),
                                RoundedCornerShape(LivingFlowTokens.RadiusPill)
                            )
                            .clickable {
                                isCardAiEnabled = !isCardAiEnabled
                                if (isCardAiEnabled) frameScanTrigger++
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Card AI",
                            tint = if (isCardAiEnabled) Color.Black else LivingFlowTokens.LivingCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Card AI",
                            color = if (isCardAiEnabled) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x44FFFFFF))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Player",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Clean Video Viewport (TextureView + Card AI Bounding Box Overlay)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(LivingFlowTokens.RadiusMedium))
                        .background(Color.Black)
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(LivingFlowTokens.RadiusMedium))
                        .clickable { isVarPlaying = !isVarPlaying }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.viewfinder_player_view, null, false) as PlayerView).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = false
                                player = clipPlayer
                                varPlayerView = this
                            }
                        },
                        update = { view ->
                            varPlayerView = view
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Playing Card Bounding Boxes + Rank/Suit Classifier Overlay
                    if (isCardAiEnabled && frameDetections.isNotEmpty()) {
                        CardDetectionOverlay(
                            detections = frameDetections,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Subtle VAR speed + millisecond HUD pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                            .background(Color(0xCC080B12))
                            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(LivingFlowTokens.RadiusPill))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (playerErrorMsg != null) {
                                "${selectedSpeed.label} • ERR: $playerErrorMsg"
                            } else {
                                "${selectedSpeed.label}  •  ${formatTimecodeMs(if (isScrubbing) scrubPosMs else currentPosMs)}"
                            },
                            color = if (playerErrorMsg != null) Color(0xFFFF6B86) else LivingFlowTokens.LivingCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Detected Cards Summary Pill on Frame
                    if (isCardAiEnabled && frameDetections.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                .background(Color(0xCC080B12))
                                .border(1.dp, LivingFlowTokens.LivingEmerald, RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Cards: ${frameDetections.joinToString("  ") { it.label }}",
                                color = LivingFlowTokens.LivingEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Mobile-Optimized VAR Control Deck
                LuminousGlassCard(
                    shape = RoundedCornerShape(LivingFlowTokens.RadiusMedium),
                    thickness = GlassThickness.THICK,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Timeline Scrubber + Millisecond Timecodes
                        Column {
                            val maxDur = durationMs.coerceAtLeast(1L).toFloat()
                            val sliderVal = (if (isScrubbing) scrubPosMs else currentPosMs).toFloat().coerceIn(0f, maxDur)
                            Slider(
                                value = sliderVal,
                                onValueChange = { newVal ->
                                    isScrubbing = true
                                    scrubPosMs = newVal.toLong()
                                    clipPlayer.seekTo(scrubPosMs)
                                    frameScanTrigger++
                                },
                                onValueChangeFinished = {
                                    currentPosMs = scrubPosMs
                                    isScrubbing = false
                                    frameScanTrigger++
                                },
                                valueRange = 0f..maxDur,
                                colors = SliderDefaults.colors(
                                    thumbColor = LivingFlowTokens.LivingCyan,
                                    activeTrackColor = LivingFlowTokens.LivingCyan,
                                    inactiveTrackColor = Color(0x44FFFFFF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatTimecodeMs(if (isScrubbing) scrubPosMs else currentPosMs),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = formatTimecodeMs(durationMs),
                                    color = Color(0x99FFFFFF),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Frame-by-Frame Transport Row: [-1 Frame] [Play/Pause] [+1 Frame]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // -1 Frame
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .background(Color(0x28FFFFFF))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .clickable { stepFrame(-1) },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Frame",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "1 Frame",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Play / Pause
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(LivingFlowTokens.LivingCyan)
                                    .clickable { isVarPlaying = !isVarPlaying },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isVarPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isVarPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // +1 Frame
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .background(Color(0x28FFFFFF))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .clickable { stepFrame(+1) },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "1 Frame",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Frame",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Speed Selector Pills: 1 FPS | 0.25x | 0.5x | 1x | 2x
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ReplaySpeed.entries.forEach { speed ->
                                val isSelected = selectedSpeed == speed
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                        .background(
                                            if (isSelected) LivingFlowTokens.LivingCyan else Color(0x22FFFFFF)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) LivingFlowTokens.LivingCyan else Color(0x28FFFFFF),
                                            shape = RoundedCornerShape(LivingFlowTokens.RadiusPill)
                                        )
                                        .clickable {
                                            selectedSpeed = speed
                                            if (speed == ReplaySpeed.ONE_FPS && !isVarPlaying) {
                                                isVarPlaying = true
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = speed.label,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Bottom Export Row: Save to Phone | Share
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .background(Color(0x2200E5FF))
                                    .border(1.dp, LivingFlowTokens.LivingCyan, RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .clickable { onDownload() }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Save to Phone",
                                    tint = LivingFlowTokens.LivingCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Save to Phone",
                                    color = LivingFlowTokens.LivingCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .background(Color(0x28FFFFFF))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(LivingFlowTokens.RadiusPill))
                                    .clickable { onShare() }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Clip",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Share / Open",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimecodeMs(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val millis = safeMs % 1000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}
