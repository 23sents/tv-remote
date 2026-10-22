package com.example.tvremote.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

class AdbCoreTest {
    @Test fun messageRoundTrip() {
        val payload = "hello adb".toByteArray()
        val wire = AdbProtocol.encodeMessage(AdbProtocol.CMD_OPEN, 7, 0, payload)
        assertEquals(AdbProtocol.HEADER_SIZE + payload.size, wire.size)
        val header = AdbProtocol.parseHeader(wire.copyOf(AdbProtocol.HEADER_SIZE))
        assertEquals(AdbProtocol.CMD_OPEN, header.cmd)
        assertEquals(7, header.arg0)
        assertEquals(0, header.arg1)
        assertEquals(payload.size, header.dataLength)
        assertTrue(header.magicOk())
        assertArrayEquals(payload, wire.copyOfRange(AdbProtocol.HEADER_SIZE, wire.size))
        assertEquals(AdbProtocol.crc32(payload), header.dataCrc32)
    }

    @Test fun cmdNameDecodesFourCc() {
        assertEquals("CNXN", AdbProtocol.cmdName(AdbProtocol.CMD_CNXN))
        assertEquals("AUTH", AdbProtocol.cmdName(AdbProtocol.CMD_AUTH))
        assertEquals("OPEN", AdbProtocol.cmdName(AdbProtocol.CMD_OPEN))
        assertEquals("OKAY", AdbProtocol.cmdName(AdbProtocol.CMD_OKAY))
        assertEquals("CLSE", AdbProtocol.cmdName(AdbProtocol.CMD_CLSE))
        assertEquals("WRTE", AdbProtocol.cmdName(AdbProtocol.CMD_WRTE))
    }

    @Test fun magicDetectsCorruption() {
        val wire = AdbProtocol.encodeMessage(AdbProtocol.CMD_OKAY, 1, 2, ByteArray(0))
        val good = AdbProtocol.parseHeader(wire.copyOf(AdbProtocol.HEADER_SIZE))
        assertTrue(good.magicOk())
        val corrupt = wire.copyOf(AdbProtocol.HEADER_SIZE)
        corrupt[0] = (corrupt[0].toInt() xor 1).toByte()
        assertTrue(!AdbProtocol.parseHeader(corrupt).magicOk())
    }

    @Test fun signRoundTripsThroughPublicKey() {
        val kp = AdbRsaKey.generate()
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val sig = AdbRsaKey.sign(token, kp.private)
        assertEquals(256, sig.size)   // 2048-bit key -> 256-byte signature
        val recovered = AdbRsaKey.recoverSignedMessage(sig, kp.public)
        // recovered = SHA1 DigestInfo prefix (15 bytes) + the original 20-byte token
        assertEquals(35, recovered.size)
        assertArrayEquals(token, recovered.copyOfRange(15, 35))
    }

    @Test fun signatureDependsOnToken() {
        val kp = AdbRsaKey.generate()
        val a = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val b = ByteArray(20).also { SecureRandom().nextBytes(it) }
        assertTrue(!AdbRsaKey.sign(a, kp.private).contentEquals(AdbRsaKey.sign(b, kp.private)))
    }

    @Test fun keyRoundTripsThroughEncoding() {
        val kp = AdbRsaKey.generate()
        val priv2 = AdbRsaKey.decodePrivate(AdbRsaKey.encodePrivate(kp.private))
        val pub2 = AdbRsaKey.decodePublic(AdbRsaKey.encodePublic(kp.public))
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        assertArrayEquals(AdbRsaKey.sign(token, kp.private), AdbRsaKey.sign(token, priv2))
        assertArrayEquals(
            AdbRsaKey.recoverSignedMessage(AdbRsaKey.sign(token, kp.private), kp.public),
            AdbRsaKey.recoverSignedMessage(AdbRsaKey.sign(token, kp.private), pub2)
        )
    }

    @Test fun mincryptStructHasCorrectSizeAndMathInvariants() {
        val kp = AdbRsaKey.generate()
        val struct = AdbRsaKey.mincryptStruct(kp.public)
        assertEquals(AdbRsaKey.STRUCT_SIZE, struct.size)

        fun u32(off: Int): Long {
            return (struct[off].toLong() and 0xFF) or ((struct[off + 1].toLong() and 0xFF) shl 8) or
                ((struct[off + 2].toLong() and 0xFF) shl 16) or ((struct[off + 3].toLong() and 0xFF) shl 24)
        }
        fun words(off: Int, count: Int): BigInteger {
            var v = BigInteger.ZERO
            for (i in count - 1 downTo 0) v = v.shiftLeft(32).or(BigInteger.valueOf(u32(off + i * 4)))
            return v
        }

        val lenWords = u32(0)
        assertEquals(64L, lenWords)
        val n0inv = u32(4) and 0xFFFFFFFFL
        val n = words(8, 64)
        val rr = words(8 + 64 * 4, 64)
        val exponent = u32(8 + 64 * 4 * 2)

        val pub = kp.public as java.security.interfaces.RSAPublicKey
        assertEquals(pub.modulus, n)
        assertEquals(pub.publicExponent, BigInteger.valueOf(exponent))

        // n0 * n0inv == -1 mod 2^32
        val b32 = BigInteger.ONE.shiftLeft(32)
        val n0 = n.mod(b32)
        val check = n0.multiply(BigInteger.valueOf(n0inv)).add(BigInteger.ONE).mod(b32)
        assertEquals(BigInteger.ZERO, check)

        // rr == R^2 mod n where R = 2^2048
        val r = BigInteger.ONE.shiftLeft(64 * 32)
        assertEquals(r.multiply(r).mod(n), rr)
    }

    @Test fun publicKeyPayloadIsParseableBase64PlusComment() {
        val kp = AdbRsaKey.generate()
        val payload = AdbRsaKey.publicKeyPayload(kp.public, "test@host")
        val text = String(payload, Charsets.US_ASCII)
        assertTrue(text.endsWith("\u0000"))
        val body = text.dropLast(1)
        val parts = body.split(" ", limit = 2)
        assertEquals("test@host", parts[1])
        val decoded = Base64.getDecoder().decode(parts[0])
        assertArrayEquals(AdbRsaKey.mincryptStruct(kp.public), decoded)
    }

    @Test fun shellCommandsBuildExpectedText() {
        assertEquals("pm list packages -f -3", ShellCommands.listPackages(false))
        assertEquals("pm list packages -f", ShellCommands.listPackages(true))
        assertEquals("pm uninstall --user 0 'com.foo.bar'", ShellCommands.uninstall("com.foo.bar"))
        assertEquals("pm clear 'com.foo.bar'", ShellCommands.clearData("com.foo.bar"))
        assertEquals("cmd package install -r -t -S 12345 -", ShellCommands.installFromStdin(12345L))
        assertEquals("cmd locale set-locale 'ru-RU'", ShellCommands.setLocale("ru-RU"))
        assertEquals("wm size 1920x1080", ShellCommands.setResolution(1920, 1080))
        assertEquals("wm size reset", ShellCommands.resetResolution())
        assertEquals("wm density 320", ShellCommands.setDensity(320))
    }

    @Test fun parsePackageLineHandlesPathAndPlainForms() {
        val a = ShellCommands.parsePackageLine("package:/data/app/~~x==/com.foo.bar-1/base.apk=com.foo.bar")
        assertEquals("com.foo.bar", a!!.pkg)
        assertEquals("/data/app/~~x==/com.foo.bar-1/base.apk", a.apkPath)
        val b = ShellCommands.parsePackageLine("package:com.foo.bar")
        assertEquals("com.foo.bar", b!!.pkg)
        assertEquals("", b.apkPath)
        assertEquals(null, ShellCommands.parsePackageLine("package:"))
    }

    @Test fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'hello'", ShellQuote.single("hello"))
        assertEquals("'it'\\''s'", ShellQuote.single("it's"))
        assertEquals("''", ShellQuote.single(""))
    }
}
