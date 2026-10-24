package com.example.tvremote

import java.io.IOException

/** A byte pipe AdbClient can speak the ADB protocol over — a TCP socket or a USB bulk connection. */
interface AdbTransport {
    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** Reads exactly `buf.size` bytes, or returns false on EOF/closed connection. */
    @Throws(IOException::class)
    fun readFully(buf: ByteArray): Boolean

    /** Changes the read timeout for subsequent readFully calls (milliseconds). */
    fun setTimeout(timeoutMs: Int)

    fun close()
}

class SocketTransport(host: String, port: Int, connectTimeoutMs: Int) : AdbTransport {
    private val socket = java.net.Socket()

    init {
        socket.tcpNoDelay = true
        socket.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = connectTimeoutMs
    }

    override fun write(data: ByteArray) {
        socket.getOutputStream().write(data)
    }

    override fun readFully(buf: ByteArray): Boolean {
        var off = 0
        val inp = socket.getInputStream()
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    override fun setTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        try { socket.close() } catch (e: IOException) { }
    }
}
