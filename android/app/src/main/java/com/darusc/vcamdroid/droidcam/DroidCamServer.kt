package com.darusc.vcamdroid.droidcam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.darusc.vcamdroid.util.Logger
import kotlinx.coroutines.*
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class DroidCamServer(
    private val context: Context,
    private val port: Int = 4747,
    private val listener: Listener
) {

    data class SessionState(
        val status: String,
        val isStreaming: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int,
        val format: String,
        val lens: String,
        val tally: String
    )

    interface Listener {
        fun onVideoStreamStarted(format: String, width: Int, height: Int)
        fun onVideoStreamStopped()
        fun onTallyChanged(tallyState: String) // "program", "preview", "idle"
        fun onClientConnected(remoteAddress: String)
        fun onClientDisconnected()
        fun getSessionState(): SessionState
        fun onSessionUpdateRequested(width: Int?, height: Int?, fps: Int?, format: String?, lens: String?): Boolean
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var activeVideoSocket: Socket? = null
    @Volatile
    private var activeVideoOutputStream: BufferedOutputStream? = null

    val isStreaming: Boolean
        get() = activeVideoSocket != null && activeVideoSocket?.isConnected == true

    fun start() {
        if (isRunning.getAndSet(true)) return

        serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverScope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Logger.log("DROIDCAM_SERVER", "Listening on port $port for OBS connections")

                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true // Disable Nagle's algorithm for lowest latency
                    client.sendBufferSize = 65536 * 4
                    launch { handleClientConnection(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Logger.log("DROIDCAM_SERVER", "ServerSocket error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            serverSocket?.close()
        } catch (_: Exception) { }
        serverSocket = null

        closeActiveVideoStream()
        serverScope.cancel()
        Logger.log("DROIDCAM_SERVER", "Server stopped")
    }

    private fun handleClientConnection(socket: Socket) {
        val remote = socket.inetAddress.hostAddress ?: "unknown"
        Logger.log("DROIDCAM_SERVER", "Accepted connection from $remote")

        try {
            val inStream = socket.getInputStream()
            val buffer = ByteArray(2048)
            val bytesRead = inStream.read(buffer)
            if (bytesRead <= 0) {
                socket.close()
                return
            }

            val request = String(buffer, 0, bytesRead, Charsets.US_ASCII)
            val firstLine = request.lines().firstOrNull()?.trim() ?: ""

            Logger.log("DROIDCAM_SERVER", "Request: $firstLine")

            when {
                firstLine.startsWith("GET /ping") -> {
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.startsWith("GET /resolutions") || firstLine.startsWith("GET /probe") || firstLine.startsWith("GET /v1/resolutions") -> {
                    // FAANG: now uses full capability probe - real HAL values with FPS, formats, MP
                    val report = com.darusc.vcamdroid.capabilities.CameraCapabilityProbe.probeSync(context)
                    val backNode = report.primaryBack
                    val frontNode = report.primaryFront
                    val backRes = backNode?.streams?.allProfiles?.map { "${it.width}x${it.height}@${it.maxFps ?: 30}fps" } ?: emptyList()
                    val frontRes = frontNode?.streams?.allProfiles?.map { "${it.width}x${it.height}@${it.maxFps ?: 30}fps" } ?: emptyList()
                    val backStr = backRes.joinToString(",") { "\"$it\"" }
                    val frontStr = frontRes.joinToString(",") { "\"$it\"" }
                    // Backward compat simple format + new detailed fields
                    val body = "{\"back\":[$backStr],\"front\":[$frontStr],\"device\":\"${report.manufacturer} ${report.model}\",\"cameras\":${report.cameras.size},\"fullReport\":true}"
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().write(bodyBytes)
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.startsWith("GET /capabilities") || firstLine.startsWith("GET /v1/capabilities") || firstLine.startsWith("GET /hal") -> {
                    val report = com.darusc.vcamdroid.capabilities.CameraCapabilityProbe.probeSync(context)
                    val body = report.toJson()
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().write(bodyBytes)
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.startsWith("GET /v1/session") -> {
                    val state = listener.getSessionState()
                    val battery = getBatteryPercentage()
                    val body = """{"status":"${state.status}","streaming":${state.isStreaming},"width":${state.width},"height":${state.height},"fps":${state.fps},"format":"${state.format}","lens":"${state.lens}","battery":$battery,"tally":"${state.tally}"}"""
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().write(bodyBytes)
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.startsWith("PUT /v1/session") || firstLine.startsWith("POST /v1/session") -> {
                    // Pattern: PUT /v1/session?width=1920&height=1080&fps=30&lens=back&format=avc
                    var width: Int? = null
                    var height: Int? = null
                    var fps: Int? = null
                    var format: String? = null
                    var lens: String? = null

                    val parts = firstLine.split(" ")
                    if (parts.size >= 2) {
                        val path = parts[1]
                        val queryIdx = path.indexOf('?')
                        val query = if (queryIdx != -1) path.substring(queryIdx + 1) else ""
                        if (query.isNotEmpty()) {
                            query.split("&").forEach { param ->
                                val kv = param.split("=")
                                if (kv.size == 2) {
                                    val key = kv[0].trim().lowercase()
                                    val value = kv[1].trim()
                                    when (key) {
                                        "width", "w" -> width = value.toIntOrNull()
                                        "height", "h" -> height = value.toIntOrNull()
                                        "fps" -> fps = value.toIntOrNull()
                                        "format", "fmt" -> format = value.lowercase()
                                        "lens", "camera" -> lens = value.lowercase()
                                    }
                                }
                            }
                        }
                    }

                    // Also check if dimensions were passed as "1920x1080" anywhere in path
                    if (width == null && height == null && parts.size >= 2) {
                        val resRegex = Regex("""(\d{3,4})x(\d{3,4})""", RegexOption.IGNORE_CASE)
                        val match = resRegex.find(parts[1])
                        if (match != null) {
                            width = match.groupValues[1].toIntOrNull()
                            height = match.groupValues[2].toIntOrNull()
                        }
                    }

                    val success = listener.onSessionUpdateRequested(width, height, fps, format, lens)
                    val state = listener.getSessionState()
                    val battery = getBatteryPercentage()
                    val body = """{"status":"${state.status}","success":$success,"streaming":${state.isStreaming},"width":${state.width},"height":${state.height},"fps":${state.fps},"format":"${state.format}","lens":"${state.lens}","battery":$battery,"tally":"${state.tally}"}"""
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().write(bodyBytes)
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.startsWith("GET /battery") -> {
                    val batteryLevel = getBatteryPercentage()
                    val body = "$batteryLevel"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.startsWith("PUT /v1/tally/") -> {
                    // Pattern: PUT /v1/tally/<program|preview|idle>/ HTTP/1.1
                    val parts = firstLine.split(" ")
                    if (parts.size >= 2) {
                        val path = parts[1]
                        val tallyStatus = path.removePrefix("/v1/tally/").trimEnd('/')
                        Logger.log("DROIDCAM_SERVER", "Tally updated: $tallyStatus")
                        listener.onTallyChanged(tallyStatus)
                    }
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    socket.close()
                }

                firstLine.contains("/video/") || firstLine.contains("/video") -> {
                    // Pattern: GET /v5/video/<format>/<width>x<height>/... or /video/<width>x<height>
                    handleVideoStreamRequest(socket, firstLine)
                }

                firstLine.startsWith("GET /v2/audio") -> {
                    // Audio stream connection request: close or return 404 if not enabled
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    socket.close()
                }

                else -> {
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Logger.log("DROIDCAM_SERVER", "Client connection exception: ${e.message}")
        }
    }

    private fun handleVideoStreamRequest(socket: Socket, requestLine: String) {
        val settings = DroidCamSettings(context)
        var format = if (requestLine.contains("hevc", ignoreCase = true) || requestLine.contains("h265", ignoreCase = true)) "hevc" else "avc"
        var width = settings.targetResolutionWidth
        var height = settings.targetResolutionHeight

        try {
            val resRegex = Regex("""(\d{3,4})x(\d{3,4})""", RegexOption.IGNORE_CASE)
            val match = resRegex.find(requestLine)
            if (match != null) {
                width = match.groupValues[1].toIntOrNull() ?: width
                height = match.groupValues[2].toIntOrNull() ?: height
            } else {
                when {
                    requestLine.contains("4k", ignoreCase = true) || requestLine.contains("2160p", ignoreCase = true) -> {
                        width = 3840; height = 2160
                    }
                    requestLine.contains("1440p", ignoreCase = true) -> {
                        width = 2560; height = 1440
                    }
                    requestLine.contains("1080p", ignoreCase = true) -> {
                        width = 1920; height = 1080
                    }
                    requestLine.contains("720p", ignoreCase = true) -> {
                        width = 1280; height = 720
                    }
                    requestLine.contains("480p", ignoreCase = true) -> {
                        width = 640; height = 480
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log("DROIDCAM_SERVER", "Error parsing video request parameters: ${e.message}")
        }

        Logger.log("DROIDCAM_SERVER", "Starting video stream: format=$format, ${width}x${height}")

        closeActiveVideoStream()
        activeVideoSocket = socket
        activeVideoOutputStream = BufferedOutputStream(socket.getOutputStream(), 65536 * 2)

        val clientIp = socket.inetAddress.hostAddress ?: "unknown"
        listener.onClientConnected(clientIp)
        listener.onVideoStreamStarted(format, width, height)

        // Wait for socket to disconnect
        try {
            val inStream = socket.getInputStream()
            val dummyBuffer = ByteArray(1024)
            while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                val read = inStream.read(dummyBuffer)
                if (read == -1) break
            }
        } catch (_: Exception) { }

        Logger.log("DROIDCAM_SERVER", "Video client disconnected")
        closeActiveVideoStream()
        listener.onVideoStreamStopped()
        listener.onClientDisconnected()
    }

    fun sendConfigPacket(configData: ByteArray) {
        val out = activeVideoOutputStream ?: return
        try {
            synchronized(out) {
                DroidCamPacketFramer.writePacket(out, DroidCamPacketFramer.NO_PTS, configData)
            }
        } catch (e: Exception) {
            Logger.log("DROIDCAM_SERVER", "Error sending config packet: ${e.message}")
            closeActiveVideoStream()
        }
    }

    fun sendVideoPacket(ptsMs: Long, buffer: ByteBuffer, offset: Int, length: Int) {
        val out = activeVideoOutputStream ?: return
        try {
            synchronized(out) {
                DroidCamPacketFramer.writePacket(out, ptsMs, buffer, offset, length)
            }
        } catch (e: Exception) {
            Logger.log("DROIDCAM_SERVER", "Error sending video packet: ${e.message}")
            closeActiveVideoStream()
        }
    }

    private fun closeActiveVideoStream() {
        try {
            activeVideoOutputStream?.flush()
        } catch (_: Exception) { }
        try {
            activeVideoSocket?.close()
        } catch (_: Exception) { }
        activeVideoSocket = null
        activeVideoOutputStream = null
    }

    private fun getBatteryPercentage(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            100
        }
    }
}
