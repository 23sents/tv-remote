package com.example.tvremote

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * ADB over a direct USB-OTG cable: the phone acts as USB host, the TV as a USB peripheral exposing the
 * standard ADB interface (class 0xFF "vendor specific", subclass 0x42, protocol 0x01 — the same interface
 * `adb` on a desktop talks to). Message framing is identical to the TCP transport; only how bytes move differs.
 *
 * This only works if the TV's USB port can act as a USB *device* when something else is the host. Many smart
 * TVs' main USB-A ports are host-only (for flash drives) and will never show up here — that is a hardware
 * limitation, not something this code can work around.
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AdbTransport {
    private var timeoutMs = 6000

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(MAX_PACKET, data.size - off)
            val n = connection.bulkTransfer(epOut, data, off, chunk, timeoutMs)
            if (n < 0) throw IOException("Ошибка записи по USB")
            off += n
        }
        if (data.isEmpty()) {
            connection.bulkTransfer(epOut, data, 0, 0, timeoutMs)
        }
    }

    override fun readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val chunk = minOf(MAX_PACKET, buf.size - off)
            val tmp = ByteArray(chunk)
            val n = connection.bulkTransfer(epIn, tmp, chunk, timeoutMs)
            if (n < 0) return false
            System.arraycopy(tmp, 0, buf, off, n)
            off += n
        }
        return true
    }

    override fun setTimeout(timeoutMs: Int) {
        this.timeoutMs = timeoutMs
    }

    override fun close() {
        try { connection.releaseInterface(usbInterface) } catch (e: Exception) { }
        try { connection.close() } catch (e: Exception) { }
    }

    companion object {
        private const val MAX_PACKET = 16 * 1024
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        /** The ADB interface of [device], if it exposes one (null otherwise — not every USB device does). */
        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == ADB_CLASS && iface.interfaceSubclass == ADB_SUBCLASS &&
                    iface.interfaceProtocol == ADB_PROTOCOL
                ) return iface
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbTransport? {
            val iface = findAdbInterface(device) ?: return null
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) return null
            val conn = manager.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return null
            }
            return UsbTransport(conn, iface, inEp, outEp)
        }
    }
}
