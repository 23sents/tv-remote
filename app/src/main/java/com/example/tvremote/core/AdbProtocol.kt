package com.example.tvremote.core

import java.util.zip.CRC32

/**
 * Wire framing for the ADB protocol (the same protocol the `adb` desktop tool speaks). A message is a
 * 24-byte header followed by `dataLength` bytes of payload. See AOSP system/core/adb/protocol.txt.
 */
object AdbProtocol {
    const val HEADER_SIZE = 24

    const val CMD_CNXN = 0x4e584e43      // "CNXN"
    const val CMD_AUTH = 0x48545541      // "AUTH"
    const val CMD_OPEN = 0x4e45504f      // "OPEN"
    const val CMD_OKAY = 0x59414b4f      // "OKAY"
    const val CMD_CLSE = 0x45534c43      // "CLSE"
    const val CMD_WRTE = 0x45545257      // "WRTE"

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val CONNECT_VERSION = 0x01000000
    const val CONNECT_MAXDATA = 256 * 1024

    class Header(val cmd: Int, val arg0: Int, val arg1: Int, val dataLength: Int, val dataCrc32: Int, val magic: Int) {
        /** False means the header is corrupt: the independently-transmitted magic field no longer matches cmd. */
        fun magicOk(): Boolean = magic == cmd.inv()
    }

    fun encodeMessage(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        putInt(out, 0, cmd)
        putInt(out, 4, arg0)
        putInt(out, 8, arg1)
        putInt(out, 12, payload.size)
        putInt(out, 16, crc32(payload))
        putInt(out, 20, cmd.inv())
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    fun parseHeader(header: ByteArray): Header {
        require(header.size == HEADER_SIZE)
        return Header(getInt(header, 0), getInt(header, 4), getInt(header, 8), getInt(header, 12), getInt(header, 16), getInt(header, 20))
    }

    fun crc32(data: ByteArray): Int {
        val c = CRC32()
        c.update(data)
        return c.value.toInt()
    }

    fun cmdName(cmd: Int): String {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = ((cmd ushr (8 * i)) and 0xFF).toByte()
        return String(b, Charsets.US_ASCII)
    }

    /** host::\0 banner sent with CNXN; the host side does not need to advertise shell_v2 for one-shot commands. */
    fun connectBanner(): ByteArray = "host::\u0000".toByteArray(Charsets.US_ASCII)

    /** ADB shell service string for a one-shot, non-interactive command. */
    fun shellService(command: String): ByteArray = ("shell:$command\u0000").toByteArray(Charsets.UTF_8)

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
