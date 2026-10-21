package com.example.tvremote.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * ADB's authentication: the device sends a random 20-byte token; the host must produce an RSA signature of it
 * using a padding scheme ADB invented (PKCS#1 v1.5 over the SHA-1 DigestInfo prefix + the token, computed as a
 * raw private-key operation rather than a real SHA-1-then-sign, since the token is never actually hashed) and,
 * the first time a key is seen, also send the device the public key in the legacy "mincrypt" RSAPublicKey wire
 * format so the person can approve it once on the TV.
 */
object AdbRsaKey {
    private const val KEY_WORDS = 64          // 2048-bit modulus / 32
    const val STRUCT_SIZE = 4 + 4 + KEY_WORDS * 4 + KEY_WORDS * 4 + 4   // 524 bytes

    // DER prefix for "SHA-1" DigestInfo (RFC 3447 / PKCS#1): identifies the 20 bytes that follow as a SHA-1 hash.
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    ).map { it.toByte() }.toByteArray()

    fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    fun encodePrivate(key: PrivateKey): ByteArray = key.encoded   // PKCS8
    fun encodePublic(key: PublicKey): ByteArray = key.encoded     // X509

    fun decodePrivate(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    fun decodePublic(bytes: ByteArray): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))

    /** Signs a 20-byte device token. Requires an RSA key with CRT parameters (true for any java-generated keypair). */
    fun sign(token: ByteArray, privateKey: PrivateKey): ByteArray {
        require(token.size == 20) { "ADB token must be 20 bytes" }
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)   // private-key "encrypt" == the raw sign operation
        return cipher.doFinal(digestInfo)
    }

    /** Only used by the unit test, to double-check [sign] against the public key. */
    fun recoverSignedMessage(signature: ByteArray, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, publicKey)
        return cipher.doFinal(signature)
    }

    /** The AUTH(RSAPUBLICKEY) payload: base64(mincrypt RSAPublicKey struct) + " " + comment + NUL. */
    fun publicKeyPayload(publicKey: PublicKey, comment: String): ByteArray {
        val struct = mincryptStruct(publicKey)
        val text = Base64.getEncoder().encodeToString(struct) + " " + comment + "\u0000"
        return text.toByteArray(Charsets.US_ASCII)
    }

    /**
     * The legacy "mincrypt" RSAPublicKey wire struct Android's adbd understands:
     *   u32 len (words in modulus) | u32 n0inv | u32 n[64] | u32 rr[64] | u32 exponent
     * n0inv = -(n0^-1) mod 2^32; rr = R^2 mod n where R = 2^2048. Both are little-endian 32-bit words, least
     * significant word first (so the struct, read as one little-endian blob, is NOT the same as a plain
     * big-endian modulus dump).
     */
    fun mincryptStruct(publicKey: PublicKey): ByteArray {
        val rsa = publicKey as java.security.interfaces.RSAPublicKey
        val n = rsa.modulus
        val e = rsa.publicExponent
        require(n.bitLength() <= KEY_WORDS * 32) { "key too large" }

        val b32 = BigInteger.ONE.shiftLeft(32)
        val n0 = n.mod(b32)
        val n0inv = n0.modInverse(b32).let { b32.subtract(it) }.mod(b32)
        val r = BigInteger.ONE.shiftLeft(KEY_WORDS * 32)
        val rr = r.multiply(r).mod(n)

        val out = ByteArray(STRUCT_SIZE)
        putU32(out, 0, KEY_WORDS.toLong())
        putU32(out, 4, n0inv.toLong())
        writeWords(out, 8, n, KEY_WORDS)
        writeWords(out, 8 + KEY_WORDS * 4, rr, KEY_WORDS)
        putU32(out, 8 + KEY_WORDS * 4 * 2, e.toLong())
        return out
    }

    private fun writeWords(out: ByteArray, offset: Int, value: BigInteger, words: Int) {
        var v = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        for (i in 0 until words) {
            putU32(out, offset + i * 4, v.and(mask).toLong())
            v = v.shiftRight(32)
        }
    }

    private fun putU32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
