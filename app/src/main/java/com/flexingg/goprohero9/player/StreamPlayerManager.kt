package com.flexingg.goprohero9.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class PlayerState {
    object Idle : PlayerState()
    object Buffering : PlayerState()
    object Playing : PlayerState()
    data class Error(val message: String) : PlayerState()
}

@OptIn(UnstableApi::class)
class StreamPlayerManager(private val context: Context) {
    companion object {
        private const val TAG = "StreamPlayerManager"
        const val LOOPBACK_STREAM_URI = "udp://127.0.0.1:8555"
        const val DIRECT_STREAM_URI = "udp://0.0.0.0:8554"
    }

    private var exoPlayer: ExoPlayer? = null
    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _isFirstFrameRendered = MutableStateFlow(false)
    val isFirstFrameRendered: StateFlow<Boolean> = _isFirstFrameRendered.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution: StateFlow<String> = _videoResolution.asStateFlow()

    private val _lastPlayerError = MutableStateFlow("")
    val lastPlayerError: StateFlow<String> = _lastPlayerError.asStateFlow()

    fun initializePlayer(): ExoPlayer {
        if (exoPlayer != null) return exoPlayer!!

        Log.d(TAG, "Initializing Media3 ExoPlayer for MPEG-TS UDP playback...")

        // Configure load control allowing sufficient buffer to acquire full GOP keyframes
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,  // Min buffer ms
                5000,  // Max buffer ms
                250,   // Buffer for playback ms
                500    // Buffer for playback after rebuffer ms
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> _playerState.value = PlayerState.Idle
                    Player.STATE_BUFFERING -> _playerState.value = PlayerState.Buffering
                    Player.STATE_READY -> _playerState.value = PlayerState.Playing
                    Player.STATE_ENDED -> _playerState.value = PlayerState.Idle
                }
            }

            override fun onRenderedFirstFrame() {
                Log.d(TAG, "ExoPlayer rendered first video frame!")
                _isFirstFrameRendered.value = true
                _playerState.value = PlayerState.Playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val res = "${videoSize.width}x${videoSize.height}"
                    Log.d(TAG, "ExoPlayer video size detected: $res")
                    _videoResolution.value = res
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val err = error.message ?: "Playback error (${error.errorCode})"
                Log.e(TAG, "ExoPlayer playback error: $err", error)
                _lastPlayerError.value = err
                _playerState.value = PlayerState.Error(err)
            }
        })

        exoPlayer = player
        return player
    }

    fun startPlaying(streamUri: String = LOOPBACK_STREAM_URI) {
        _isFirstFrameRendered.value = false
        _lastPlayerError.value = ""
        val player = exoPlayer ?: initializePlayer()

        try {
            player.stop()
            player.clearMediaItems()

            val udpDataSourceFactory = DataSource.Factory {
                UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, 8000)
            }
            val extractorsFactory = DefaultExtractorsFactory().apply {
                setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                )
                setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            }

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(streamUri))
                .setMimeType(MimeTypes.VIDEO_MP2T)
                .build()

            val mediaSource = ProgressiveMediaSource.Factory(udpDataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
            Log.d(TAG, "ExoPlayer started for $streamUri (MPEG-TS non-IDR keyframe tolerance enabled)")
        } catch (e: Exception) {
            val msg = "Failed to open UDP stream: ${e.message}"
            Log.e(TAG, msg)
            _lastPlayerError.value = msg
            _playerState.value = PlayerState.Error(msg)
        }
    }

    fun stop() {
        exoPlayer?.stop()
        _playerState.value = PlayerState.Idle
        _isFirstFrameRendered.value = false
        _videoResolution.value = ""
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        _playerState.value = PlayerState.Idle
        _isFirstFrameRendered.value = false
        _videoResolution.value = ""
    }
}
