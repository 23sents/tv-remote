package com.example.tvremote

import com.example.tvremote.core.AdbProtocol
import com.example.tvremote.core.AdbRsaKey
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair

sealed class AdbResult {
    object Connected : AdbResult()
    /** The TV is showing (or about to show) its "allow debugging from this computer?" prompt. */
    object AwaitingApproval : AdbResult()
    class Failed(val message: String) : AdbResult()
}

class ShellResult(val ok: Boolean, val output: String)

/**
 * A minimal ADB client: TCP transport + the CNXN/AUTH handshake + one-shot `shell:` commands. This is the same
 * wire protocol the `adb` desktop tool speaks (see core.AdbProtocol); only what a remote-control app needs is
 * implemented (no file sync, no port forwarding, no interactive shell).
 */
class AdbClient(private val host: String, private val port: Int, private val keyPair: KeyPair) {
    private var socket: Socket? = null
    private var nextLocalId = 1
    @Volatile var connected = false
        private set

    @Synchronized
    @Throws(IOException::class)
    fun connect(connectTimeoutMs: Int = 6000, approvalTimeoutMs: Int = 25_000): AdbResult {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = connectTimeoutMs
        socket = s

        send(s, AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA, AdbProtocol.connectBanner())
        var header = readHeader(s) ?: return fail("Нет ответа от телевизора")
        var payload = readPayload(s, header)

        if (header.cmd == AdbProtocol.CMD_CNXN) {
            connected = true
            return AdbResult.Connected
        }
        if (header.cmd != AdbProtocol.CMD_AUTH || header.arg0 != AdbProtocol.AUTH_TOKEN) {
            return fail("Телевизор ответил неожиданно (" + AdbProtocol.cmdName(header.cmd) + ")")
        }

        // Attempt 1: sign with our stored key (works instantly on a TV that already trusts it).
        val sig = AdbRsaKey.sign(payload, keyPair.private)
        send(s, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, sig)
        header = readHeader(s) ?: return fail("Соединение прервалось во время проверки")
        if (header.cmd == AdbProtocol.CMD_CNXN) {
            connected = true
            return AdbResult.Connected
        }
        if (header.cmd != AdbProtocol.CMD_AUTH || header.arg0 != AdbProtocol.AUTH_TOKEN) {
            return fail("Телевизор отклонил подключение")
        }
        payload = readPayload(s, header)   // a fresh token for the public-key attempt

        // Attempt 2: offer our public key; the TV will show a one-time approval dialog.
        val pub = AdbRsaKey.publicKeyPayload(keyPair.public, "tv-remote")
        send(s, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, pub)
        s.soTimeout = approvalTimeoutMs
        header = readHeader(s) ?: return AdbResult.AwaitingApproval
        return if (header.cmd == AdbProtocol.CMD_CNXN) {
            connected = true
            AdbResult.Connected
        } else {
            fail("Телевизор не подтвердил подключение")
        }
    }

    /** Runs one non-interactive shell command and returns whatever it printed. */
    @Synchronized
    @Throws(IOException::class)
    fun shell(command: String, timeoutMs: Int = 8000): ShellResult {
        val s = socket ?: return ShellResult(false, "не подключено")
        s.soTimeout = timeoutMs
        val localId = nextLocalId++
        send(s, AdbProtocol.CMD_OPEN, localId, 0, AdbProtocol.shellService(command))

        var remoteId = -1
        val out = StringBuilder()
        while (true) {
            val header = readHeader(s) ?: return ShellResult(out.isEmpty(), out.toString())
            val payload = readPayload(s, header)
            when (header.cmd) {
                AdbProtocol.CMD_OKAY -> remoteId = header.arg0
                AdbProtocol.CMD_WRTE -> {
                    out.append(payload.toString(Charsets.UTF_8))
                    send(s, AdbProtocol.CMD_OKAY, localId, header.arg0, ByteArray(0))
                }
                AdbProtocol.CMD_CLSE -> {
                    send(s, AdbProtocol.CMD_CLSE, localId, if (remoteId >= 0) remoteId else header.arg0, ByteArray(0))
                    return ShellResult(true, out.toString())
                }
                else -> { /* ignore anything unrelated to this stream */ }
            }
        }
    }

    fun close() {
        try { socket?.close() } catch (e: IOException) { }
        socket = null
        connected = false
    }

    private fun fail(msg: String): AdbResult {
        close()
        return AdbResult.Failed(msg)
    }

    private fun send(s: Socket, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        s.getOutputStream().write(AdbProtocol.encodeMessage(cmd, arg0, arg1, payload))
    }

    private fun readHeader(s: Socket): AdbProtocol.Header? {
        val buf = ByteArray(AdbProtocol.HEADER_SIZE)
        if (!readFully(s, buf)) return null
        val h = AdbProtocol.parseHeader(buf)
        return if (h.magicOk()) h else null
    }

    private fun readPayload(s: Socket, header: AdbProtocol.Header): ByteArray {
        if (header.dataLength == 0) return ByteArray(0)
        val buf = ByteArray(header.dataLength)
        if (!readFully(s, buf)) return ByteArray(0)
        return buf
    }

    private fun readFully(s: Socket, buf: ByteArray): Boolean {
        var off = 0
        val inp = s.getInputStream()
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
