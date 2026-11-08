package com.flexingg.goprohero9.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.flexingg.goprohero9.data.stream.LiveStreamDataPipe

/**
 * Custom Media3 DataSource backed by an in-memory LiveStreamDataPipe.
 * Enables ExoPlayer to read live MPEG-TS packets directly from the GoPro UDP ingest socket.
 */
@OptIn(UnstableApi::class)
class LiveStreamDataSource(
    private val dataPipe: LiveStreamDataPipe
) : BaseDataSource(true) {

    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val bytesRead = dataPipe.read(buffer, offset, length)
        if (bytesRead > 0) {
            bytesTransferred(bytesRead)
        }
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        transferEnded()
    }

    class Factory(private val dataPipe: LiveStreamDataPipe) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveStreamDataSource(dataPipe)
    }
}
