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

    interface Listener {
        fun onVideoStreamStarted(format: String, width: Int, height: Int)
        fun onVideoStreamStopped()
        fun onTallyChanged(tallyState: String) // "program", "preview", "idle"
        fun onClientConnected(remoteAddress: String)
        fun onClientDisconnected()
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

                firstLine.contains("/video/") -> {
                    // Pattern: GET /v5/video/<format>/<width>x<height>/port/<port>/os/...
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
        // Parse format & resolution
        // e.g., "GET /v5/video/avc/1920x1080/port/4747/os/windows/..."
        var format = "avc" // or "hevc"
        var width = 1920
        var height = 1080

        try {
            val parts = requestLine.split("/")
            // parts[0] = "GET "
            // parts[1] = "v5"
            // parts[2] = "video"
            // parts[3] = <format> ("avc", "hevc", "jpg")
            // parts[4] = <resolution> ("1920x1080")
            if (parts.size >= 5) {
                format = parts[3].lowercase()
                val resParts = parts[4].split("x")
                if (resParts.size == 2) {
                    width = resParts[0].toIntOrNull() ?: 1920
                    height = resParts[1].toIntOrNull() ?: 1080
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
