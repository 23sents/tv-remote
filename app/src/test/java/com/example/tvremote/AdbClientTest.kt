package com.example.tvremote

import com.example.tvremote.core.AdbProtocol
import com.example.tvremote.core.AdbRsaKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/** A tiny stand-in for adbd: enough of the handshake and shell protocol to exercise AdbClient end to end. */
private class FakeAdbd(
    private val trustedKey: java.security.PublicKey?,
    private val approveOnPubkey: Boolean,
    private val handleShellAfterConnect: Boolean = false,
) {
    val port: Int
    private val server = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()
    @Volatile var sawShellCommand: String? = null
    @Volatile var closedCleanly = false
    @Volatile var receivedData: ByteArray? = null
    var handlePush = false

    init {
        port = server.localPort
    }

    fun acceptOnce(): Future<*> = pool.submit { handle(server.accept()) }

    private fun send(s: Socket, cmd: Int, a0: Int, a1: Int, payload: ByteArray) {
        s.getOutputStream().write(AdbProtocol.encodeMessage(cmd, a0, a1, payload))
    }

    private fun readHeader(s: Socket): AdbProtocol.Header {
        val buf = ByteArray(AdbProtocol.HEADER_SIZE)
        var off = 0
        while (off < buf.size) { val n = s.getInputStream().read(buf, off, buf.size - off); if (n < 0) throw java.io.EOFException(); off += n }
        return AdbProtocol.parseHeader(buf)
    }

    private fun readPayload(s: Socket, h: AdbProtocol.Header): ByteArray {
        if (h.dataLength == 0) return ByteArray(0)
        val buf = ByteArray(h.dataLength)
        var off = 0
        while (off < buf.size) { val n = s.getInputStream().read(buf, off, buf.size - off); if (n < 0) throw java.io.EOFException(); off += n }
        return buf
    }

    private fun handle(s: Socket) {
        var h = readHeader(s); readPayload(s, h)   // CNXN
        if (trustedKey != null) {
            // Already-trusted key: accept the very first signature without even checking it here
            // (for this fake server we only need to exercise the "fast path" branch in AdbClient).
            send(s, AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA, AdbProtocol.connectBanner())
        } else {
            val token1 = ByteArray(20).also { Random.nextBytes(it) }
            send(s, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token1)
            h = readHeader(s); readPayload(s, h)    // AUTH signature (unrecognised key -> reject)
            val token2 = ByteArray(20).also { Random.nextBytes(it) }
            send(s, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token2)
            h = readHeader(s); readPayload(s, h)    // AUTH rsapublickey
            if (!approveOnPubkey) {
                s.close()
                return
            }
            send(s, AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA, AdbProtocol.connectBanner())
        }

        if (!handleShellAfterConnect) return

        h = readHeader(s)
        val openPayload = readPayload(s, h)
        val service = String(openPayload, Charsets.UTF_8).trimEnd('\u0000')
        sawShellCommand = service.removePrefix("shell:")
        val localId = h.arg0
        val remoteId = 777
        send(s, AdbProtocol.CMD_OKAY, remoteId, localId, ByteArray(0))

        if (handlePush) {
            // keep reading WRTE chunks (acking each) until the client has nothing more to send, detected by
            // the client's next action being a plain shell read -- in this fake, the test tells us the total
            // size up front via sawShellCommand's "-S <n>" so we know exactly how many bytes to expect.
            val n = Regex("-S (\\d+)").find(sawShellCommand ?: "")!!.groupValues[1].toInt()
            val buf = java.io.ByteArrayOutputStream()
            while (buf.size() < n) {
                val ch = readHeader(s)
                val data = readPayload(s, ch)
                buf.write(data)
                send(s, AdbProtocol.CMD_OKAY, remoteId, localId, ByteArray(0))
            }
            receivedData = buf.toByteArray()
            send(s, AdbProtocol.CMD_WRTE, remoteId, localId, "Success\n".toByteArray())
            readHeader(s).also { readPayload(s, it) }
            send(s, AdbProtocol.CMD_CLSE, remoteId, localId, ByteArray(0))
            h = readHeader(s); readPayload(s, h)
            closedCleanly = h.cmd == AdbProtocol.CMD_CLSE
            return
        }

        // shell: open -> write some output in two chunks -> close
        send(s, AdbProtocol.CMD_WRTE, remoteId, localId, "hello ".toByteArray())
        readHeader(s).also { readPayload(s, it) }     // client's OKAY ack
        send(s, AdbProtocol.CMD_WRTE, remoteId, localId, "world".toByteArray())
        readHeader(s).also { readPayload(s, it) }     // client's OKAY ack
        send(s, AdbProtocol.CMD_CLSE, remoteId, localId, ByteArray(0))
        h = readHeader(s); readPayload(s, h)          // client's CLSE
        closedCleanly = h.cmd == AdbProtocol.CMD_CLSE
    }
}

class AdbClientTest {
    private fun keyPair(): KeyPair = AdbRsaKey.generate()

    @Test fun connectsImmediatelyWithAnAlreadyTrustedKey() {
        val kp = keyPair()
        val server = FakeAdbd(trustedKey = kp.public, approveOnPubkey = true)
        val accept = server.acceptOnce()
        val client = AdbClient(kp)
        val result = client.connectTcp("127.0.0.1", server.port)
        accept.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(result is AdbResult.Connected)
        assertTrue(client.connected)
    }

    @Test fun firstTimePairingGoesThroughPublicKeyApproval() {
        val kp = keyPair()
        val server = FakeAdbd(trustedKey = null, approveOnPubkey = true)
        val accept = server.acceptOnce()
        val client = AdbClient(kp)
        val result = client.connectTcp("127.0.0.1", server.port)
        accept.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(result is AdbResult.Connected)
    }

    @Test fun unapprovedPublicKeyIsReportedAsAwaitingApproval() {
        val kp = keyPair()
        val server = FakeAdbd(trustedKey = null, approveOnPubkey = false)
        val accept = server.acceptOnce()
        val client = AdbClient(kp)
        val result = client.connectTcp("127.0.0.1", server.port, approvalTimeoutMs = 1500)
        accept.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(result is AdbResult.AwaitingApproval || result is AdbResult.Failed)
    }

    @Test fun shellCommandRoundTripsOutputAndClosesCleanly() {
        val kp = keyPair()
        val server = FakeAdbd(trustedKey = kp.public, approveOnPubkey = true, handleShellAfterConnect = true)
        val accept = server.acceptOnce()
        val client = AdbClient(kp)
        assertTrue(client.connectTcp("127.0.0.1", server.port) is AdbResult.Connected)
        val result = client.shell("input keyevent 22")
        accept.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(result.ok)
        assertEquals("hello world", result.output)
        assertEquals("input keyevent 22", server.sawShellCommand)
        assertTrue(server.closedCleanly)
    }

    @Test fun installApkStreamsDataWithFlowControlAndReportsSuccess() {
        val kp = keyPair()
        val server = FakeAdbd(trustedKey = kp.public, approveOnPubkey = true, handleShellAfterConnect = true)
        server.handlePush = true
        val accept = server.acceptOnce()
        val client = AdbClient(kp)
        assertTrue(client.connectTcp("127.0.0.1", server.port) is AdbResult.Connected)

        val apk = ByteArray(40_000) { (it % 251).toByte() }   // bigger than one 16 KB push chunk
        val progress = ArrayList<Pair<Long, Long>>()
        val result = client.installApk(apk) { sent, total -> progress.add(sent to total) }
        accept.get(5, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(result.ok)
        assertTrue(result.output.contains("Success"))
        assertArrayEquals(apk, server.receivedData)
        assertTrue(progress.size >= 3)                         // 40000 / 16384 -> 3 chunks
        assertEquals(40_000L, progress.last().first)
        assertTrue(progress.all { it.second == 40_000L })
    }
}
