package com.flexingg.goprohero9.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
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
        const val DEFAULT_STREAM_URI = "udp://0.0.0.0:8554"
    }

    private var exoPlayer: ExoPlayer? = null
    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    fun initializePlayer(): ExoPlayer {
        if (exoPlayer != null) return exoPlayer!!

        Log.d(TAG, "Initializing Media3 ExoPlayer for UDP MPEG-TS stream...")

        // Configure ultra-low latency load control for live viewfinder
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                200,   // Min buffer ms
                500,   // Max buffer ms
                100,   // Buffer for playback ms
                100    // Buffer for playback after rebuffer ms
            )
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

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer playback error: ${error.message}", error)
                _playerState.value = PlayerState.Error(error.message ?: "Playback error")
            }
        })

        exoPlayer = player
        return player
    }

    fun startPlaying(streamUri: String = DEFAULT_STREAM_URI) {
        val player = exoPlayer ?: initializePlayer()

        try {
            val udpDataSourceFactory = DataSource.Factory {
                UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, 5000)
            }
            val extractorsFactory = DefaultExtractorsFactory()

            val mediaSource = ProgressiveMediaSource.Factory(udpDataSourceFactory, extractorsFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streamUri)))

            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
            Log.d(TAG, "ExoPlayer started for $streamUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP playback: ${e.message}")
            _playerState.value = PlayerState.Error("Failed to open UDP stream: ${e.message}")
        }
    }

    fun stop() {
        exoPlayer?.stop()
        _playerState.value = PlayerState.Idle
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        _playerState.value = PlayerState.Idle
    }
}
