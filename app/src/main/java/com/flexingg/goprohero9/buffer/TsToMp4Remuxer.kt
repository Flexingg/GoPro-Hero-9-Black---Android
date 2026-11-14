package com.flexingg.goprohero9.buffer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Lossless, near-instant remuxer from GoPro MPEG-TS (.ts) to seekable MP4 (.mp4).
 * Uses Media3's TsExtractor (filtering only the primary H.264/H.265 video track) and
 * Android's MediaMuxer so VAR scrubbing, -1/+1 frame stepping, and 1 FPS mode work accurately.
 */
@OptIn(UnstableApi::class)
object TsToMp4Remuxer {
    private const val TAG = "TsToMp4Remuxer"

    fun remux(tsFile: File, mp4File: File): Boolean {
        if (!tsFile.exists() || tsFile.length() == 0L) return false

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1
        var firstPtsUs = -1L
        var lastPtsUs = -1L
        var seenKeyframe = false
        var framesWritten = 0

        val sampleBuffer = ByteArrayOutputStream(65536)

        val tsPayloadFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM or
            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM

        val defaultFactory = DefaultTsPayloadReaderFactory(tsPayloadFlags)
        var videoReaderCreated = false
        val goproPayloadFactory = object : TsPayloadReader.Factory {
            override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = SparseArray()

            override fun createPayloadReader(
                streamType: Int,
                esInfo: TsPayloadReader.EsInfo
            ): TsPayloadReader? {
                return when (streamType) {
                    TsExtractor.TS_STREAM_TYPE_H264,
                    TsExtractor.TS_STREAM_TYPE_H265 -> {
                        if (!videoReaderCreated) {
                            videoReaderCreated = true
                            defaultFactory.createPayloadReader(streamType, esInfo)
                        } else null
                    }
                    else -> null
                }
            }
        }

        val trackOutput = object : TrackOutput {
            override fun format(format: Format) {
                if (muxerStarted) return
                try {
                    val mime = format.sampleMimeType ?: MimeTypes.VIDEO_H264
                    val width = if (format.width > 0) format.width else 848
                    val height = if (format.height > 0) format.height else 480
                    val mediaFormat = MediaFormat.createVideoFormat(mime, width, height)
                    format.initializationData.forEachIndexed { index, data ->
                        mediaFormat.setByteBuffer("csd-$index", ByteBuffer.wrap(data))
                    }
                    if (format.frameRate > 0f) {
                        mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate)
                    }
                    muxer = MediaMuxer(mp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                        videoTrackIndex = addTrack(mediaFormat)
                        start()
                    }
                    muxerStarted = true
                    Log.d(TAG, "MediaMuxer started: ${width}x${height} mime=$mime csd=${format.initializationData.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaMuxer: ${e.message}", e)
                }
            }

            override fun sampleData(
                input: DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int
            ): Int {
                val tmp = ByteArray(length)
                val read = input.read(tmp, 0, length)
                if (read > 0) sampleBuffer.write(tmp, 0, read)
                return read
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val tmp = ByteArray(length)
                data.readBytes(tmp, 0, length)
                sampleBuffer.write(tmp, 0, length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?
            ) {
                val allBytes = sampleBuffer.toByteArray()
                val sampleEnd = (allBytes.size - offset).coerceIn(0, allBytes.size)
                val sampleStart = (sampleEnd - size).coerceIn(0, sampleEnd)

                // Preserve any trailing bytes that belong to the next sample
                sampleBuffer.reset()
                if (sampleEnd < allBytes.size) {
                    sampleBuffer.write(allBytes, sampleEnd, allBytes.size - sampleEnd)
                }

                if (!muxerStarted || videoTrackIndex < 0 || sampleEnd <= sampleStart) return

                val avccBytes = annexBToAvcc(allBytes, sampleStart, sampleEnd)
                if (avccBytes.isEmpty()) return

                val isIdr = containsIdrNal(avccBytes)
                val isKeyframe = isIdr || ((flags and C.BUFFER_FLAG_KEY_FRAME) != 0)
                if (!seenKeyframe) {
                    if (!isIdr) return
                    seenKeyframe = true
                }

                val validTimeUs = if (timeUs != C.TIME_UNSET && timeUs >= 0L) {
                    timeUs
                } else if (lastPtsUs >= 0L) {
                    lastPtsUs + 16_667L
                } else {
                    0L
                }

                val ptsUs = if (firstPtsUs < 0L) {
                    firstPtsUs = validTimeUs
                    0L
                } else {
                    val normalized = (validTimeUs - firstPtsUs).coerceAtLeast(0L)
                    if (normalized <= lastPtsUs) lastPtsUs + 16_667L else normalized
                }
                lastPtsUs = ptsUs

                val info = MediaCodec.BufferInfo().apply {
                    this.offset = 0
                    this.size = avccBytes.size
                    this.presentationTimeUs = ptsUs
                    this.flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }

                try {
                    val bb = ByteBuffer.wrap(avccBytes)
                    muxer?.writeSampleData(videoTrackIndex, bb, info)
                    framesWritten++
                } catch (e: Exception) {
                    Log.w(TAG, "writeSampleData error: ${e.message}")
                }
            }
        }

        val extractorOutput = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = trackOutput
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }

        return try {
            val extractor = TsExtractor(
                TsExtractor.MODE_SINGLE_PMT,
                TimestampAdjuster(0),
                goproPayloadFactory
            )
            extractor.init(extractorOutput)

            FileInputStream(tsFile).use { fis ->
                val dataReader = DataReader { buffer, offset, length ->
                    val r = fis.read(buffer, offset, length)
                    if (r == -1) C.RESULT_END_OF_INPUT else r
                }
                val input = DefaultExtractorInput(dataReader, 0L, C.LENGTH_UNSET.toLong())
                val posHolder = PositionHolder()
                while (true) {
                    val res = extractor.read(input, posHolder)
                    if (res == Extractor.RESULT_END_OF_INPUT) break
                    if (res == Extractor.RESULT_SEEK) break
                }
            }

            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null

            var ok = framesWritten > 0 && mp4File.exists() && mp4File.length() > 0L
            if (ok) {
                ok = verifyMp4Decodable(mp4File)
            }
            Log.d(TAG, "Remux complete: ok=$ok frames=$framesWritten size=${mp4File.length()}")
            if (!ok && mp4File.exists()) mp4File.delete()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Remux failed: ${e.message}", e)
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
            if (mp4File.exists()) mp4File.delete()
            false
        }
    }

    private fun verifyMp4Decodable(mp4File: File): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mp4File.absolutePath)
            val bmp = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1L)
            val valid = bmp != null && bmp.width > 0 && bmp.height > 0
            bmp?.recycle()
            Log.d(TAG, "MediaMetadataRetriever verifyMp4Decodable=$valid")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataRetriever verification failed: ${e.message}")
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun containsIdrNal(avccBytes: ByteArray): Boolean {
        var i = 0
        while (i + 4 < avccBytes.size) {
            val len = ((avccBytes[i].toInt() and 0xFF) shl 24) or
                ((avccBytes[i + 1].toInt() and 0xFF) shl 16) or
                ((avccBytes[i + 2].toInt() and 0xFF) shl 8) or
                (avccBytes[i + 3].toInt() and 0xFF)
            if (len <= 0 || i + 4 + len > avccBytes.size) break
            val nalType = avccBytes[i + 4].toInt() and 0x1F
            if (nalType == 5) return true
            i += 4 + len
        }
        return false
    }

    /**
     * Converts Annex-B NAL units (00 00 01 or 00 00 00 01 start codes) into
     * 4-byte big-endian length-prefixed AVCC/HVCC NAL units required by Android MediaMuxer.
     */
    private fun annexBToAvcc(data: ByteArray, start: Int, end: Int): ByteArray {
        val out = ByteArrayOutputStream((end - start).coerceAtLeast(16))
        var i = start
        var currentNalStart = -1

        while (i < end) {
            val scLen = when {
                i + 3 < end && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                i + 2 < end && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (scLen > 0) {
                if (currentNalStart >= 0 && i > currentNalStart) {
                    writeAvccNal(out, data, currentNalStart, i)
                }
                i += scLen
                currentNalStart = i
            } else {
                i++
            }
        }

        if (currentNalStart >= 0) {
            if (end > currentNalStart) {
                writeAvccNal(out, data, currentNalStart, end)
            }
            return out.toByteArray()
        }

        // Fallback if sample was already non-Annex-B
        return data.copyOfRange(start, end)
    }

    private fun writeAvccNal(out: ByteArrayOutputStream, data: ByteArray, nalStart: Int, nalEnd: Int) {
        val len = nalEnd - nalStart
        if (len <= 0) return
        // Skip H.264 SPS (7), PPS (8), and AUD (9) inside mdat samples (SPS/PPS belong in avcC)
        val nalType = data[nalStart].toInt() and 0x1F
        if (nalType == 7 || nalType == 8 || nalType == 9) return

        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(data, nalStart, len)
    }
}
