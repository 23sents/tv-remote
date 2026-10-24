package com.example.tvremote

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.tvremote.core.AppShortcut
import com.example.tvremote.core.DefaultApps
import com.example.tvremote.core.RemoteKeys
import com.example.tvremote.core.ShellCommands
import com.example.tvremote.core.ShellQuote
import java.util.concurrent.Executors

/** A progress dialog with a settable bar, used while pushing an APK to the TV. */
private class InstallDialog(private val dialog: Dialog, private val bar: ProgressBar) {
    fun setProgress(percent: Int) { bar.progress = percent }
    fun dismiss() = dialog.dismiss()
}

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var keyStore: AdbKeyStore
    private var client: AdbClient? = null

    private lateinit var root: FrameLayout
    private var capture: KeyCaptureView? = null
    private var kbShown = false

    // tabs (built once the TV is connected)
    private var tabHost: FrameLayout? = null
    private var tabBarViews: Array<TextView>? = null
    private var remoteScreen: View? = null
    private var appsScreen: AppsScreen? = null
    private var tvSettingsScreen: TvSettingsScreen? = null
    private var moreScreen: View? = null
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        keyStore = AdbKeyStore(this)
        window.statusBarColor = Color.rgb(8, 12, 28)
        window.navigationBarColor = Color.rgb(20, 14, 46)
        root = FrameLayout(this)
        root.addView(BackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        showConnect()
    }

    private val usbPairing by lazy { UsbPairing(this, io) }

    override fun onDestroy() {
        client?.close()
        usbPairing.dispose()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startUsbPairing(statusText: TextView, hostField: EditText, portField: EditText) {
        val port = portField.text.toString().trim().toIntOrNull() ?: 5555
        statusText.text = "Ищу телевизор на USB…"
        usbPairing.start(keyStore.load(), port, object : UsbPairing.Listener {
            override fun onStatus(message: String) {
                runOnUiThread { statusText.text = message }
            }

            override fun onReadyForWifi(ip: String?, readyPort: Int) {
                runOnUiThread {
                    portField.setText(readyPort.toString())
                    if (ip != null) {
                        hostField.setText(ip)
                        statusText.text = "Сеть включена на $ip — подключаюсь…"
                        connectTo(ip, readyPort, statusText)
                    } else {
                        statusText.text = "Сеть на телевизоре включена, но определить его IP не удалось. " +
                            "Введите IP вручную (см. Настройки → Сеть на ТВ) и нажмите «Подключиться»."
                    }
                }
            }

            override fun onFailed(message: String) {
                runOnUiThread { statusText.text = "USB: $message" }
            }
        })
    }

    private fun setScreen(v: View) {
        root.removeAllViews()
        root.addView(BackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------------ connect screen
    private fun showConnect() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(36f), dp(24f), dp(24f))
        }
        col.addView(label("Пульт для Android TV", 28f, bold = true))
        col.addView(
            label(
                "Введите IP телевизора. На телевизоре должна быть включена отладка по сети " +
                    "(Настройки → Для разработчиков → Отладка по сети/ADB). При первом подключении " +
                    "телевизор один раз спросит разрешение — подтвердите его обычным пультом.",
                14f, alpha = 180
            ),
            lp(10)
        )
        val host = EditText(this).apply {
            hint = "192.168.1.23"
            setText(prefs.lastHost)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(110, 255, 255, 255))
            textSize = 18f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = Glass.drawable(this@MainActivity, 20f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        col.addView(host, lp(20))
        val port = EditText(this).apply {
            hint = "Порт (обычно 5555)"
            setText(prefs.lastPort.toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(110, 255, 255, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            background = Glass.drawable(this@MainActivity, 20f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        col.addView(port, lp(10))

        val statusText = label("", 14f, alpha = 200).apply { gravity = Gravity.CENTER }

        col.addView(
            Glass.button(this, "Подключиться", 0x553A7BFF, 0x883A7BFF.toInt()) {
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull() ?: 5555
                if (h.isEmpty()) {
                    statusText.text = "Введите IP телевизора"
                } else {
                    connectTo(h, p, statusText)
                }
            },
            lp(20)
        )
        col.addView(statusText, lp(14))

        col.addView(
            Glass.button(this, "Подключить по USB (без компьютера)", 0x18FFFFFF) { startUsbPairing(statusText, host, port) },
            lp(14)
        )
        col.addView(
            label(
                "Нужен OTG-кабель/переходник и порт ТВ, который умеет работать как USB-устройство — " +
                    "это зависит от конкретной модели и заранее не определить, кроме как попробовав.",
                11f, alpha = 130
            ),
            lp(4)
        )

        if (prefs.recent.isNotEmpty()) {
            col.addView(label("Недавние", 13f, alpha = 150), lp(22))
            val list = ListView(this)
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, prefs.recent)
            list.setOnItemClickListener { _, _, pos, _ ->
                val hp = prefs.recent[pos].split(":")
                host.setText(hp.getOrNull(0) ?: "")
                port.setText(hp.getOrNull(1) ?: "5555")
            }
            col.addView(list, LinearLayout.LayoutParams(MATCH, dp(160f)).apply { topMargin = dp(6f) })
        }

        val scroll = ScrollView(this).apply { addView(col) }
        setScreen(scroll)
    }

    private fun connectTo(host: String, port: Int, statusText: TextView) {
        statusText.text = "Подключение…"
        io.execute {
            val kp = keyStore.load()
            val c = AdbClient(kp)
            val result = try {
                c.connectTcp(host, port)
            } catch (e: Exception) {
                AdbResult.Failed(e.message ?: "Ошибка сети")
            }
            runOnUiThread {
                when (result) {
                    is AdbResult.Connected -> {
                        client = c
                        prefs.lastHost = host
                        prefs.lastPort = port
                        prefs.rememberHost("$host:$port")
                        showTabs()
                    }
                    is AdbResult.AwaitingApproval -> statusText.text =
                        "Посмотрите на экран телевизора и подтвердите подключение обычным пультом, затем нажмите «Подключиться» ещё раз"
                    is AdbResult.Failed -> statusText.text = "Не удалось подключиться: ${result.message}"
                }
            }
        }
    }

    // ------------------------------------------------------------------ tabs
    private fun showTabs() {
        remoteScreen = null
        appsScreen = null
        tvSettingsScreen = null
        moreScreen = null

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = FrameLayout(this)
        tabHost = host
        column.addView(host, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(
            tabBar(),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14f), dp(6f), dp(14f), dp(10f)) }
        )
        setScreen(column)
        showTab(0)
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.drawable(this@MainActivity, 26f, 0x33FFFFFF)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        val labels = listOf("🎮\nПульт", "📦\nПриложения", "📺\nТВ", "⋯\nЕщё")
        val views = Array(4) { i ->
            TextView(this).apply {
                text = labels[i]
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, dp(6f))
                setOnClickListener { showTab(i) }
            }
        }
        tabBarViews = views
        for (v in views) bar.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun showTab(i: Int) {
        currentTab = i
        val host = tabHost ?: return
        host.removeAllViews()
        val view = when (i) {
            0 -> remoteScreen ?: buildRemoteScreen().also { remoteScreen = it }
            1 -> appsScreen ?: AppsScreen(this).also { appsScreen = it }
            2 -> tvSettingsScreen ?: TvSettingsScreen(this).also { tvSettingsScreen = it }
            else -> moreScreen ?: buildMoreScreen().also { moreScreen = it }
        }
        host.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        tabBarViews?.forEachIndexed { k, v -> v.background = if (k == i) Glass.drawable(this, 20f, 0x40FFFFFF) else null }
    }

    // ------------------------------------------------------------------ remote tab
    private fun buildRemoteScreen(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18f), dp(20f), dp(18f), dp(10f))
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(label("● подключено", 13f, alpha = 200), LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(Glass.button(this, "Отключить", 0x24FFFFFF) {
            client?.close()
            client = null
            showConnect()
        }.apply { textSize = 13f; setPadding(dp(14f), dp(8f), dp(14f), dp(8f)) })
        col.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        col.addView(dpad(), LinearLayout.LayoutParams(dp(250f), dp(250f)).apply { topMargin = dp(20f) })

        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(Glass.key(this, "◁", 22f) { sendKey(RemoteKeys.BACK) }, rowLp())
        navRow.addView(Glass.key(this, "◯", 22f) { sendKey(RemoteKeys.HOME) }, rowLp())
        navRow.addView(Glass.key(this, "⌨", 20f) { toggleKeyboard() }, rowLp())
        col.addView(navRow, LinearLayout.LayoutParams(MATCH, dp(60f)).apply { topMargin = dp(16f) })

        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        volRow.addView(Glass.key(this, "－", 24f) { sendKey(RemoteKeys.VOLUME_DOWN) }, rowLp())
        volRow.addView(label("Громкость", 13f, alpha = 170).apply { gravity = Gravity.CENTER }, rowLp())
        volRow.addView(Glass.key(this, "＋", 24f) { sendKey(RemoteKeys.VOLUME_UP) }, rowLp())
        col.addView(volRow, LinearLayout.LayoutParams(MATCH, dp(60f)).apply { topMargin = dp(12f) })

        col.addView(label("Приложения", 13f, alpha = 150), lp(18))
        val appsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in prefs.apps) {
            appsRow.addView(
                Glass.button(this, a.label, 0x22FFFFFF, 0x3DFFFFFF) { launchApp(a.pkg, a.label) }.apply { textSize = 13f },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (appsRow.childCount > 0) marginStart = dp(8f) }
            )
        }
        col.addView(appsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })

        val cap = KeyCaptureView(this, { text -> sendText(text) }, { code -> sendKey(code) })
        capture = cap
        val frame = FrameLayout(this)
        frame.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(cap, FrameLayout.LayoutParams(1, 1))
        return frame
    }

    private fun dpad(): View {
        val size = dp(250f)
        val btn = dp(80f)
        val frame = FrameLayout(this)
        frame.layoutParams = ViewGroup.LayoutParams(size, size)
        frame.background = Glass.circle(this, 0x14FFFFFF, 0x30FFFFFF)
        fun place(v: View, g: Int) = frame.addView(v, FrameLayout.LayoutParams(btn, btn, g))
        place(Glass.key(this, "▲", 26f) { sendKey(RemoteKeys.UP) }, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        place(Glass.key(this, "▼", 26f) { sendKey(RemoteKeys.DOWN) }, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        place(Glass.key(this, "◀", 26f) { sendKey(RemoteKeys.LEFT) }, Gravity.START or Gravity.CENTER_VERTICAL)
        place(Glass.key(this, "▶", 26f) { sendKey(RemoteKeys.RIGHT) }, Gravity.END or Gravity.CENTER_VERTICAL)
        val ok = TextView(this).apply {
            text = "OK"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = Glass.circle(this@MainActivity, 0x3A5EA0FF, 0x80FFFFFF.toInt())
            isClickable = true
            setOnClickListener { sendKey(RemoteKeys.CENTER) }
        }
        frame.addView(ok, FrameLayout.LayoutParams(dp(88f), dp(88f), Gravity.CENTER))
        return frame
    }

    private fun rowLp() = LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(6f); marginEnd = dp(6f) }

    // ------------------------------------------------------------------ "more" tab (app shortcuts + key)
    private fun buildMoreScreen(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(22f), dp(20f), dp(16f))
        }
        col.addView(label("Ещё", 26f, bold = true))
        col.addView(
            label(
                "Пакеты YouTube и Netflix проверены для Android TV. RuTube и Кинопоиск отличаются " +
                    "по регионам и устройствам — если кнопка не открывает нужное приложение, впишите " +
                    "точное имя пакета сюда. Узнать его можно и на вкладке «Приложения».",
                13f, alpha = 170
            ),
            lp(8)
        )
        val fields = prefs.apps.map { a ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(label(a.label, 15f, bold = true), lp(14))
            val e = EditText(this).apply {
                setText(a.pkg)
                setTextColor(Color.WHITE)
                textSize = 14f
                background = Glass.drawable(this@MainActivity, 16f)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            }
            row.addView(e, lp(4))
            col.addView(row)
            a to e
        }
        col.addView(
            Glass.button(this, "Сохранить ярлыки приложений", 0x553A7BFF, 0x883A7BFF.toInt()) {
                prefs.apps = fields.map { (a, e) -> AppShortcut(a.id, a.label, e.text.toString().trim().ifEmpty { a.pkg }) }
                toast("Сохранено")
                remoteScreen = null
                showTab(0)
            },
            lp(20)
        )
        col.addView(
            Glass.button(this, "Сбросить пакеты к значениям по умолчанию", 0x18FFFFFF) {
                prefs.apps = DefaultApps.defaults()
                moreScreen = null
                showTab(3)
            },
            lp(10)
        )
        col.addView(
            Glass.button(this, "Забыть ключ (переподключаться заново на всех ТВ)", 0x33FF453A, 0x66FF453A) {
                keyStore.regenerate()
                toast("Ключ обновлён — на каждом телевизоре потребуется подтвердить заново")
            },
            lp(24)
        )
        return ScrollView(this).apply { addView(col) }
    }

    // ------------------------------------------------------------------ shared actions (used by the tabs)
    private fun sendKey(code: Int) {
        val c = client ?: return
        io.execute {
            try {
                c.shell("input keyevent $code")
            } catch (e: Exception) {
                runOnUiThread { onLost(e) }
            }
        }
    }

    private fun sendText(text: String) {
        val c = client ?: return
        io.execute {
            try {
                c.shell("input text " + ShellQuote.single(text))
            } catch (e: Exception) {
                runOnUiThread { onLost(e) }
            }
        }
    }

    /** Runs an arbitrary shell command on the TV and delivers the result on the UI thread. Used by the Apps
     * and TV-settings tabs. */
    fun runShell(command: String, timeoutMs: Int = 8000, onResult: (ShellResult) -> Unit) {
        val c = client
        if (c == null) {
            onResult(ShellResult(false, "не подключено"))
            return
        }
        io.execute {
            val r = try {
                c.shell(command, timeoutMs)
            } catch (e: Exception) {
                runOnUiThread { onLost(e) }
                ShellResult(false, e.message ?: "ошибка сети")
            }
            runOnUiThread { onResult(r) }
        }
    }

    fun launchApp(pkg: String, label: String? = null) {
        toast("Открываю ${label ?: pkg}…")
        runShell(ShellCommands.launch(pkg)) { r ->
            val failed = r.output.contains("No activities found") || r.output.contains("Exception") || !r.ok
            if (failed) toast("«${label ?: pkg}» не найдено на этом ТВ (пакет $pkg).")
        }
    }

    // ------------------------------------------------------------------ APK install
    fun pickApkToInstall() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
            .setType("application/vnd.android.package-archive")
            .addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_APK)
        } catch (e: Exception) {
            toast("Не найдено приложение для выбора файла")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        installApk(uri)
    }

    private fun installApk(uri: Uri) {
        val c = client ?: return
        val dialog = installProgressDialog()
        io.execute {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (bytes == null) {
                runOnUiThread { dialog.dismiss(); toast("Не удалось прочитать файл") }
                return@execute
            }
            if (bytes.size > MAX_APK_BYTES) {
                runOnUiThread { dialog.dismiss(); toast("Файл слишком большой (>${MAX_APK_BYTES / 1_000_000} МБ)") }
                return@execute
            }
            try {
                val r = c.installApk(bytes) { sent, total ->
                    runOnUiThread { dialog.setProgress((sent * 100 / total).toInt()) }
                }
                runOnUiThread {
                    dialog.dismiss()
                    if (r.output.contains("Success")) {
                        toast("Установлено")
                        appsScreen?.refresh()
                    } else {
                        toast("Ошибка установки: ${r.output.trim().ifEmpty { "нет ответа от ТВ" }}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { dialog.dismiss(); onLost(e) }
            }
        }
    }

    private fun installProgressDialog(): InstallDialog {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
            addView(label("Устанавливаю на телевизор…", 15f))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14f) })
        }
        val dialog = Dialog(this)
        dialog.setContentView(col)
        dialog.setCancelable(false)
        dialog.show()
        return InstallDialog(dialog, bar)
    }

    // ------------------------------------------------------------------ misc
    private fun toggleKeyboard() {
        val cap = capture ?: return
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (kbShown) {
            imm.hideSoftInputFromWindow(cap.windowToken, 0)
            kbShown = false
        } else {
            cap.requestFocus()
            cap.post { imm.showSoftInput(cap, 0) }
            kbShown = true
        }
    }

    private fun onLost(e: Exception) {
        toast("Соединение потеряно: ${e.message ?: "ошибка сети"}")
        client?.close()
        client = null
        showConnect()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun label(text: String, sp: Float, alpha: Int = 255, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.argb(alpha, 255, 255, 255))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top.toFloat()) }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_APK = 31
        const val MAX_APK_BYTES = 300 * 1_000_000
    }
}
