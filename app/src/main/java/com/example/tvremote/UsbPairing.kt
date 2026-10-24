package com.example.tvremote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.tvremote.core.ShellCommands
import java.util.concurrent.ExecutorService

/**
 * Drives the whole "no computer needed" USB flow: find a USB-attached device that exposes an ADB interface,
 * ask for permission to use it, run the normal ADB handshake over it (same one-time on-TV approval as over
 * Wi-Fi), tell the TV to start listening on the network (`tcpip:`), try to read back its own IP address, and
 * hand control back once the caller can switch to the regular Wi-Fi [AdbClient].
 */
class UsbPairing(private val context: Context, private val io: ExecutorService) {
    interface Listener {
        fun onStatus(message: String)
        /** [ip] is null if the TV answered but we could not parse its address — the person must type it in. */
        fun onReadyForWifi(ip: String?, port: Int)
        fun onFailed(message: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var receiver: BroadcastReceiver? = null

    fun candidateDevices(): List<UsbDevice> = usbManager.deviceList.values.filter { UsbTransport.findAdbInterface(it) != null }

    fun start(keyPair: java.security.KeyPair, port: Int, listener: Listener) {
        val devices = candidateDevices()
        if (devices.isEmpty()) {
            listener.onFailed(
                "USB-устройство с интерфейсом ADB не найдено. Либо кабель/переходник OTG не подключён, " +
                    "либо порт телевизора не поддерживает режим устройства (это ограничение конкретной модели)."
            )
            return
        }
        val device = devices.first()
        if (usbManager.hasPermission(device)) {
            proceed(device, keyPair, port, listener)
        } else {
            requestPermission(device, keyPair, port, listener)
        }
    }

    private fun requestPermission(device: UsbDevice, keyPair: java.security.KeyPair, port: Int, listener: Listener) {
        listener.onStatus("Запрашиваю доступ к USB-устройству…")
        val action = "${context.packageName}.USB_PERMISSION"
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 0, Intent(action), flags)
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                context.unregisterReceiver(this)
                receiver = null
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) proceed(device, keyPair, port, listener)
                else listener.onFailed("Доступ к USB не разрешён")
            }
        }
        receiver = r
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, IntentFilter(action))
        }
        usbManager.requestPermission(device, pending)
    }

    fun dispose() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (e: Exception) { } }
        receiver = null
    }

    private fun proceed(device: UsbDevice, keyPair: java.security.KeyPair, port: Int, listener: Listener) {
        listener.onStatus("Подключаюсь по USB…")
        io.execute {
            val transport = UsbTransport.open(usbManager, device)
            if (transport == null) {
                listener.onFailed("Не удалось открыть USB-соединение с устройством")
                return@execute
            }
            val client = AdbClient(keyPair)
            val result = try {
                client.connectUsb(transport)
            } catch (e: Exception) {
                AdbResult.Failed(e.message ?: "ошибка USB")
            }
            when (result) {
                is AdbResult.AwaitingApproval -> listener.onStatus(
                    "Посмотрите на экран телевизора и подтвердите подключение обычным пультом"
                )
                is AdbResult.Failed -> {
                    client.close()
                    listener.onFailed(result.message)
                }
                is AdbResult.Connected -> finish(client, port, listener)
            }
        }
    }

    private fun finish(client: AdbClient, port: Int, listener: Listener) {
        listener.onStatus("Включаю сеть на телевизоре…")
        val tcpip = try {
            client.enableTcpip(port)
        } catch (e: Exception) {
            client.close()
            listener.onFailed("Не удалось включить сеть: ${e.message}")
            return
        }
        if (!tcpip.ok) {
            client.close()
            listener.onFailed("Телевизор отклонил включение сети: ${tcpip.output.trim()}")
            return
        }
        val ipResult = try {
            client.shell(ShellCommands.detectIp())
        } catch (e: Exception) {
            null
        }
        client.close()
        val ip = ipResult?.let { ShellCommands.parseDetectedIp(it.output) }
        listener.onReadyForWifi(ip, port)
    }
}
