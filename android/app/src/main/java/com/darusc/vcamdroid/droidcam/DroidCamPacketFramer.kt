package com.darusc.vcamdroid.droidcam

import java.io.OutputStream
import java.nio.ByteBuffer

object DroidCamPacketFramer {

    const val HEADER_SIZE = 12
    const val NO_PTS = -1L // 0xFFFFFFFFFFFFFFFFL in signed 64-bit

    /**
     * Writes a DroidCam packet header + payload to output stream.
     * Header format:
     * - 8 bytes: Big-Endian uint64 pts (presentation timestamp in ms, or NO_PTS for SPS/PPS config)
     * - 4 bytes: Big-Endian uint32 len (payload length)
     */
    fun writePacket(
        outputStream: OutputStream,
        pts: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        val header = ByteArray(HEADER_SIZE)
        // Write 64-bit big endian PTS
        header[0] = (pts ushr 56).toByte()
        header[1] = (pts ushr 48).toByte()
        header[2] = (pts ushr 40).toByte()
        header[3] = (pts ushr 32).toByte()
        header[4] = (pts ushr 24).toByte()
        header[5] = (pts ushr 16).toByte()
        header[6] = (pts ushr 8).toByte()
        header[7] = pts.toByte()

        // Write 32-bit big endian Length
        header[8] = (length ushr 24).toByte()
        header[9] = (length ushr 16).toByte()
        header[10] = (length ushr 8).toByte()
        header[11] = length.toByte()

        outputStream.write(header)
        outputStream.write(payload, offset, length)
        outputStream.flush()
    }

    fun writePacket(
        outputStream: OutputStream,
        pts: Long,
        byteBuffer: ByteBuffer,
        offset: Int,
        length: Int
    ) {
        val header = ByteArray(HEADER_SIZE)
        header[0] = (pts ushr 56).toByte()
        header[1] = (pts ushr 48).toByte()
        header[2] = (pts ushr 40).toByte()
        header[3] = (pts ushr 32).toByte()
        header[4] = (pts ushr 24).toByte()
        header[5] = (pts ushr 16).toByte()
        header[6] = (pts ushr 8).toByte()
        header[7] = pts.toByte()

        header[8] = (length ushr 24).toByte()
        header[9] = (length ushr 16).toByte()
        header[10] = (length ushr 8).toByte()
        header[11] = length.toByte()

        outputStream.write(header)

        val tempArray = ByteArray(length)
        val oldPos = byteBuffer.position()
        byteBuffer.position(offset)
        byteBuffer.get(tempArray, 0, length)
        byteBuffer.position(oldPos)

        outputStream.write(tempArray)
        outputStream.flush()
    }
}
