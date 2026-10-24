package com.example.tvremote

import com.example.tvremote.core.AdbProtocol
import com.example.tvremote.core.AdbRsaKey
import java.io.IOException
import java.security.KeyPair

sealed class AdbResult {
    object Connected : AdbResult()
    /** The TV is showing (or about to show) its "allow debugging from this computer?" prompt. */
    object AwaitingApproval : AdbResult()
    class Failed(val message: String) : AdbResult()
}

class ShellResult(val ok: Boolean, val output: String)

/**
 * A minimal ADB client: the CNXN/AUTH handshake + one-shot `shell:`/`exec:`/`tcpip:` streams, over any
 * [AdbTransport] (TCP socket or a direct USB-OTG cable). This is the same wire protocol the `adb` desktop tool
 * speaks (see core.AdbProtocol); only what this app needs is implemented (no file sync service, no port
 * forwarding, no interactive shell).
 */
class AdbClient(private val keyPair: KeyPair) {
    private var transport: AdbTransport? = null
    private var nextLocalId = 1
    @Volatile var connected = false
        private set

    @Synchronized
    @Throws(IOException::class)
    fun connectTcp(host: String, port: Int, connectTimeoutMs: Int = 6000, approvalTimeoutMs: Int = 25_000): AdbResult =
        connect(SocketTransport(host, port, connectTimeoutMs), connectTimeoutMs, approvalTimeoutMs)

    @Synchronized
    @Throws(IOException::class)
    fun connectUsb(t: UsbTransport, connectTimeoutMs: Int = 6000, approvalTimeoutMs: Int = 25_000): AdbResult =
        connect(t, connectTimeoutMs, approvalTimeoutMs)

    private fun connect(t: AdbTransport, connectTimeoutMs: Int, approvalTimeoutMs: Int): AdbResult {
        close()
        transport = t
        t.setTimeout(connectTimeoutMs)

        send(t, AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA, AdbProtocol.connectBanner())
        var header = readHeader(t) ?: return fail("Нет ответа от телевизора")
        var payload = readPayload(t, header)

        if (header.cmd == AdbProtocol.CMD_CNXN) {
            connected = true
            return AdbResult.Connected
        }
        if (header.cmd != AdbProtocol.CMD_AUTH || header.arg0 != AdbProtocol.AUTH_TOKEN) {
            return fail("Телевизор ответил неожиданно (" + AdbProtocol.cmdName(header.cmd) + ")")
        }

        // Attempt 1: sign with our stored key (works instantly on a TV that already trusts it).
        val sig = AdbRsaKey.sign(payload, keyPair.private)
        send(t, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, sig)
        header = readHeader(t) ?: return fail("Соединение прервалось во время проверки")
        if (header.cmd == AdbProtocol.CMD_CNXN) {
            connected = true
            return AdbResult.Connected
        }
        if (header.cmd != AdbProtocol.CMD_AUTH || header.arg0 != AdbProtocol.AUTH_TOKEN) {
            return fail("Телевизор отклонил подключение")
        }
        payload = readPayload(t, header)   // a fresh token for the public-key attempt

        // Attempt 2: offer our public key; the TV will show a one-time approval dialog.
        val pub = AdbRsaKey.publicKeyPayload(keyPair.public, "tv-remote")
        send(t, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, pub)
        t.setTimeout(approvalTimeoutMs)
        header = readHeader(t) ?: return AdbResult.AwaitingApproval
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
        val t = transport ?: return ShellResult(false, "не подключено")
        return runStream(t, AdbProtocol.shellService(command), null, timeoutMs, null)
    }

    /** Sends the special `tcpip:<port>` service: the device's adbd restarts listening for network connections
     * on that port — exactly what `adb tcpip <port>` does from a desktop, just issued over this USB link. */
    @Synchronized
    @Throws(IOException::class)
    fun enableTcpip(port: Int, timeoutMs: Int = 8000): ShellResult {
        val t = transport ?: return ShellResult(false, "не подключено")
        val service = ("tcpip:$port\u0000").toByteArray(Charsets.UTF_8)
        return runStream(t, service, null, timeoutMs, null)
    }

    /**
     * Installs an APK already read into memory, streaming it to `cmd package install` over the same connection
     * (no separate ADB "sync" file-push service needed). [onProgress] reports bytes sent so far / total.
     */
    @Synchronized
    @Throws(IOException::class)
    fun installApk(apk: ByteArray, timeoutMs: Int = 60_000, onProgress: ((Long, Long) -> Unit)? = null): ShellResult {
        val t = transport ?: return ShellResult(false, "не подключено")
        val service = ("exec:" + com.example.tvremote.core.ShellCommands.installFromStdin(apk.size.toLong()) + "\u0000")
            .toByteArray(Charsets.UTF_8)
        return runStream(t, service, apk, timeoutMs, onProgress)
    }

    fun close() {
        try { transport?.close() } catch (e: Exception) { }
        transport = null
        connected = false
    }

    // ------------------------------------------------------------------ internals
    private fun fail(msg: String): AdbResult {
        close()
        return AdbResult.Failed(msg)
    }

    /** Opens one `service` stream, optionally pushes [pushData] to its stdin first, then collects whatever the
     * device writes back until it closes the stream. */
    private fun runStream(
        t: AdbTransport, service: ByteArray, pushData: ByteArray?, timeoutMs: Int, onProgress: ((Long, Long) -> Unit)?,
    ): ShellResult {
        t.setTimeout(timeoutMs)
        val localId = nextLocalId++
        send(t, AdbProtocol.CMD_OPEN, localId, 0, service)

        var remoteId = -1
        var pushed = false
        val out = StringBuilder()
        while (true) {
            val header = readHeader(t) ?: return ShellResult(false, out.toString())
            val payload = readPayload(t, header)
            when (header.cmd) {
                AdbProtocol.CMD_OKAY -> {
                    if (remoteId < 0) {
                        remoteId = header.arg0
                        if (pushData != null && !pushed) {
                            pushed = true
                            pushAll(t, localId, remoteId, pushData, onProgress)
                        }
                    }
                }
                AdbProtocol.CMD_WRTE -> {
                    out.append(payload.toString(Charsets.UTF_8))
                    send(t, AdbProtocol.CMD_OKAY, localId, header.arg0, ByteArray(0))
                }
                AdbProtocol.CMD_CLSE -> {
                    send(t, AdbProtocol.CMD_CLSE, localId, if (remoteId >= 0) remoteId else header.arg0, ByteArray(0))
                    return ShellResult(true, out.toString())
                }
                else -> { /* ignore anything unrelated to this stream */ }
            }
        }
    }

    /** Writes [data] as a sequence of WRTE chunks, each acknowledged by the device before the next is sent
     * (the same flow control the real ADB sync/exec file transfer uses), well under any device's advertised
     * max payload so we never need to know what that is. */
    private fun pushAll(t: AdbTransport, localId: Int, remoteId: Int, data: ByteArray, onProgress: ((Long, Long) -> Unit)?) {
        var offset = 0
        val total = data.size.toLong()
        while (offset < data.size) {
            val len = minOf(PUSH_CHUNK, data.size - offset)
            send(t, AdbProtocol.CMD_WRTE, localId, remoteId, data.copyOfRange(offset, offset + len))
            val ackHeader = readHeader(t) ?: throw IOException("Телевизор прервал передачу")
            readPayload(t, ackHeader)
            if (ackHeader.cmd != AdbProtocol.CMD_OKAY) {
                throw IOException("Неожиданный ответ во время передачи (" + AdbProtocol.cmdName(ackHeader.cmd) + ")")
            }
            offset += len
            onProgress?.invoke(offset.toLong(), total)
        }
    }

    private fun send(t: AdbTransport, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        t.write(AdbProtocol.encodeMessage(cmd, arg0, arg1, payload))
    }

    private fun readHeader(t: AdbTransport): AdbProtocol.Header? {
        val buf = ByteArray(AdbProtocol.HEADER_SIZE)
        if (!t.readFully(buf)) return null
        val h = AdbProtocol.parseHeader(buf)
        return if (h.magicOk()) h else null
    }

    private fun readPayload(t: AdbTransport, header: AdbProtocol.Header): ByteArray {
        if (header.dataLength == 0) return ByteArray(0)
        val buf = ByteArray(header.dataLength)
        if (!t.readFully(buf)) return ByteArray(0)
        return buf
    }

    private companion object {
        const val PUSH_CHUNK = 16 * 1024
    }
}
