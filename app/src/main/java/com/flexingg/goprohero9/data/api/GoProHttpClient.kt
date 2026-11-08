package com.flexingg.goprohero9.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class GoProMediaFile(
    val directory: String,
    val filename: String,
    val sizeBytes: Long,
    val creationTime: Long
)

class GoProHttpClient(
    private val scope: CoroutineScope,
    var baseIp: String = "10.5.5.9",
    private val port: Int = 8080
) {
    companion object {
        private const val TAG = "GoProHttpClient"
    }

    private var client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateNetwork(network: android.net.Network?) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (network != null) {
            Log.d(TAG, "Binding OkHttpClient to network socketFactory: $network")
            builder.socketFactory(network.socketFactory)
        }
        client = builder.build()
    }

    private val baseUrl: String get() = "http://$baseIp:$port"
    private val gson = Gson()
    private var keepAliveJob: Job? = null

    /**
     * Starts the periodic 2.5s keep-alive heartbeat required by HERO9 firmware
     * to keep the live UDP stream alive and prevent camera standby.
     */
    fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting keep-alive heartbeat loop...")
            while (isActive) {
                try {
                    val request = Request.Builder()
                        .url("$baseUrl/gopro/camera/keep_alive")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Keep-alive response code: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Keep-alive ping error: ${e.message}")
                }
                delay(2500)
            }
        }
    }

    fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    /**
     * Instructs HERO9 to start live UDP MPEG-TS stream on port 8554.
     */
    suspend fun startStream(streamPort: Int = 8554): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/gopro/camera/stream/start?port=$streamPort"
            val request = Request.Builder().url(url).get().build()
            val result = client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Start stream response: code=$code, body=$body")
                code in 200..299 || body.contains("already", ignoreCase = true)
            }
            if (result) {
                startKeepAlive()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream: ${e.message}")
            false
        }
    }

    /**
     * Stops the live UDP stream.
     */
    suspend fun stopStream(): Boolean = withContext(Dispatchers.IO) {
        stopKeepAlive()
        try {
            val url = "$baseUrl/gopro/camera/stream/stop"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop stream: ${e.message}")
            false
        }
    }

    /**
     * Retrospective Clip trigger via HindSight:
     * HERO9 HindSight keeps rolling buffer in RAM. Triggering shutter start and then
     * stopping after buffer commit commits the uncompressed 4K master clip to SD card.
     */
    suspend fun triggerHindSightClip(durationSeconds: Int = 15): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Triggering HindSight clip (Duration: ${durationSeconds}s)...")
            val startRequest = Request.Builder().url("$baseUrl/gopro/camera/shutter/start").get().build()
            val startOk = client.newCall(startRequest).execute().use { it.isSuccessful }

            if (!startOk) return@withContext false

            // Delay for buffer flush + lead-in commit
            delay(2000)

            val stopRequest = Request.Builder().url("$baseUrl/gopro/camera/shutter/stop").get().build()
            client.newCall(stopRequest).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering HindSight clip: ${e.message}")
            false
        }
    }

    /**
     * Query camera media list to find the latest recorded clip.
     */
    suspend fun getLatestMediaFile(): GoProMediaFile? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/gopro/media/list").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val mediaArray = json.getAsJsonArray("media")
                if (mediaArray != null && mediaArray.size() > 0) {
                    val lastDirObj = mediaArray.get(mediaArray.size() - 1).asJsonObject
                    val dirName = lastDirObj.get("d").asString
                    val fileArray = lastDirObj.getAsJsonArray("fs")
                    if (fileArray != null && fileArray.size() > 0) {
                        val lastFileObj = fileArray.get(fileArray.size() - 1).asJsonObject
                        val fileName = lastFileObj.get("n").asString
                        val size = lastFileObj.get("s").asLong
                        val time = if (lastFileObj.has("mod")) lastFileObj.get("mod").asLong else 0L
                        return@withContext GoProMediaFile(dirName, fileName, size, time)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying media list: ${e.message}")
            null
        }
    }

    /**
     * Enables Turbo Transfer on GoPro (boosts transfer speeds up to 10-12 MB/s).
     */
    suspend fun setTurboTransfer(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val state = if (enable) "on" else "off"
            val request = Request.Builder().url("$baseUrl/gopro/media/turbo_transfer/$state").get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Turbo transfer toggle error: ${e.message}")
            false
        }
    }

    /**
     * Downloads a full-resolution clip file from the camera to local destination.
     */
    suspend fun downloadMediaFile(
        mediaFile: GoProMediaFile,
        destinationFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            setTurboTransfer(true)
            val downloadUrl = "$baseUrl/videos/DCIM/${mediaFile.directory}/${mediaFile.filename}"
            Log.d(TAG, "Downloading full-res master from $downloadUrl to ${destinationFile.absolutePath}")

            val request = Request.Builder().url(downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val totalLength = body.contentLength().coerceAtLeast(mediaFile.sizeBytes)

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalLength > 0) {
                                onProgress(totalRead.toFloat() / totalLength)
                            }
                        }
                        output.flush()
                    }
                }
                setTurboTransfer(false)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading media file: ${e.message}")
            setTurboTransfer(false)
            false
        }
    }
}
