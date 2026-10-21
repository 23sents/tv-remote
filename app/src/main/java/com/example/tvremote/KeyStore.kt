package com.example.tvremote

import android.content.Context
import com.example.tvremote.core.AdbRsaKey
import java.io.File
import java.security.KeyPair

/** Persists the single RSA keypair the app uses to authenticate to every paired TV. */
class AdbKeyStore(ctx: Context) {
    private val file = File(ctx.filesDir, "adbkey")
    private val wrapper = KeystoreWrapper()

    fun load(): KeyPair {
        if (file.exists()) {
            unwrap(file.readBytes())?.let { return it }
        }
        val kp = AdbRsaKey.generate()
        save(kp)
        return kp
    }

    fun regenerate(): KeyPair {
        val kp = AdbRsaKey.generate()
        save(kp)
        return kp
    }

    private fun save(kp: KeyPair) {
        val priv = AdbRsaKey.encodePrivate(kp.private)
        val pub = AdbRsaKey.encodePublic(kp.public)
        val blob = intToBytes(priv.size) + priv + pub
        writeAtomic(file, wrapper.wrap(blob))
    }

    private fun unwrap(wrapped: ByteArray): KeyPair? {
        val blob = wrapper.unwrap(wrapped) ?: return null
        if (blob.size < 4) return null
        val privLen = bytesToInt(blob)
        if (privLen < 0 || 4 + privLen > blob.size) return null
        val priv = AdbRsaKey.decodePrivate(blob.copyOfRange(4, 4 + privLen))
        val pub = AdbRsaKey.decodePublic(blob.copyOfRange(4 + privLen, blob.size))
        return KeyPair(pub, priv)
    }

    private fun intToBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun bytesToInt(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}

internal fun writeAtomic(file: File, bytes: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
