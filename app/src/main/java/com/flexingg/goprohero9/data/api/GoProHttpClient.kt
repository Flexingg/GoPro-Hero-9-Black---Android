package com.flexingg.goprohero9.data.api

import android.net.Network
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GoProMediaFile(
    val directory: String,
    val filename: String,
    val sizeBytes: Long,
    val creationTime: Long
)

class GoProHttpClient(
    private val scope: CoroutineScope,
    initialBaseIp: String = "10.5.5.9"
) {
    var baseIp: String = initialBaseIp
        set(value) {
            field = if (value.startsWith("10.5.5.")) value else "10.5.5.9"
        }

    private val port: Int = 8080

    companion object {
        private const val TAG = "GoProHttpClient"
    }

    private var client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _lastHttpLog = MutableStateFlow("Ready")
    val lastHttpLog: StateFlow<String> = _lastHttpLog.asStateFlow()

    fun updateNetwork(network: Network?) {
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
     * Starts periodic HTTP keep-alive ping loop.
     */
    fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting HTTP keep-alive heartbeat loop...")
            while (isActive) {
                // 1. OpenGoPro keep_alive
                try {
                    val request = Request.Builder()
                        .url("$baseUrl/gopro/camera/keep_alive")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful && response.code != 404) {
                            Log.w(TAG, "HTTP keep_alive code: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "HTTP keep_alive note: ${e.message}")
                }

                // 2. Legacy gpControl status ping (keeps session alive on all GoPro firmwares)
                try {
                    val statusReq = Request.Builder()
                        .url("$baseUrl/gp/gpControl/status")
                        .get()
                        .build()
                    client.newCall(statusReq).execute().use { _ -> }
                } catch (_: Exception) {}

                delay(2500)
            }
        }
    }

    fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    /**
     * Instructs HERO9 to start live UDP MPEG-TS stream on port 8554:
     * 1. Forces camera into Video mode (dismisses pairing screen & activates hardware encoder).
     * 2. Calls /stream/stop first to eliminate HTTP 409 Conflict from previous sessions.
     * 3. Tests start endpoints: OpenGoPro ?port=8554, OpenGoPro default, and legacy gpStream.
     * 4. Treats HTTP 200..299, 409 Conflict, or "already" as SUCCESS.
     */
    suspend fun startStream(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 1. Force Video mode so camera dismisses pairing/setup UI and wakes video encoder
        val presetEndpoints = listOf(
            "$baseUrl/gopro/camera/presets/set_group?id=1000",
            "$baseUrl/gp/gpControl/command/sub_mode?mode=0",
            "$baseUrl/gp/gpControl/command/mode?p=0"
        )
        for (url in presetEndpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    Log.d(TAG, "Preset init on $url: code=${resp.code}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Preset command note: ${e.message}")
            }
        }

        // 2. Stop any existing stream to prevent 409 conflicts
        val stopEndpoints = listOf(
            "$baseUrl/gopro/camera/stream/stop",
            "$baseUrl/gp/gpControl/execute?p1=gpStream&c1=stop"
        )
        for (url in stopEndpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    Log.d(TAG, "Stream stop cleanup $url: code=${resp.code}")
                }
            } catch (_: Exception) {}
        }
        delay(300)

        // 3. Start preview stream - try endpoints in order
        val endpoints = listOf(
            "$baseUrl/gopro/camera/stream/start?port=8554",
            "$baseUrl/gopro/camera/stream/start",
            "$baseUrl/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart",
            "$baseUrl/gp/gpControl/execute?p1=gpStream&c1=restart"
        )

        var lastLog = ""
        for (url in endpoints) {
            try {
                val request = Request.Builder().url(url).get().build()
                val (ok, info) = client.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    val endpointName = url.substringAfterLast("/")
                    val summary = "$endpointName -> HTTP $code ${if (code == 409) "(Active)" else body.take(30)}"
                    Log.d(TAG, "Start stream: $summary")
                    val isSuccess = response.isSuccessful || code == 409 || body.contains("already", ignoreCase = true)
                    Pair(isSuccess, summary)
                }

                lastLog = info
                _lastHttpLog.value = info

                if (ok) {
                    startKeepAlive()
                    return@withContext Pair(true, info)
                }
            } catch (e: Exception) {
                lastLog = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Stream start attempt on $url failed: $lastLog")
            }
        }

        _lastHttpLog.value = lastLog
        Pair(false, lastLog)
    }

    /**
     * Stops the live stream.
     */
    suspend fun stopStream(): Boolean = withContext(Dispatchers.IO) {
        stopKeepAlive()
        var ok = false
        val stopEndpoints = listOf(
            "$baseUrl/gopro/camera/stream/stop",
            "$baseUrl/gp/gpControl/execute?p1=gpStream&c1=stop"
        )
        for (url in stopEndpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful || response.code == 409) {
                        ok = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stop stream note on $url: ${e.message}")
            }
        }
        _lastHttpLog.value = "Stream Stopped"
        ok
    }

    /**
     * Retrospective Clip trigger via HindSight.
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
     * Query camera media list.
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
