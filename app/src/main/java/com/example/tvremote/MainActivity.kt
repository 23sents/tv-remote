package com.example.tvremote

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.tvremote.core.AppShortcut
import com.example.tvremote.core.RemoteKeys
import com.example.tvremote.core.ShellQuote
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var keyStore: AdbKeyStore
    private var client: AdbClient? = null

    private lateinit var root: FrameLayout
    private var status: TextView? = null
    private var capture: KeyCaptureView? = null
    private var kbShown = false

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

    override fun onDestroy() {
        client?.close()
        io.shutdownNow()
        super.onDestroy()
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
        status = statusText

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
            val c = AdbClient(host, port, kp)
            val result = try {
                c.connect()
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
                        showRemote()
                    }
                    is AdbResult.AwaitingApproval -> statusText.text =
                        "Посмотрите на экран телевизора и подтвердите подключение обычным пультом, затем нажмите «Подключиться» ещё раз"
                    is AdbResult.Failed -> statusText.text = "Не удалось подключиться: ${result.message}"
                }
            }
        }
    }

    // ------------------------------------------------------------------ remote screen
    private fun showRemote() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18f), dp(22f), dp(18f), dp(18f))
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val st = label("● подключено", 13f, alpha = 200)
        status = st
        topRow.addView(st, LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(Glass.button(this, "Отключить", 0x24FFFFFF) {
            client?.close()
            client = null
            showConnect()
        }.apply { textSize = 13f; setPadding(dp(14f), dp(8f), dp(14f), dp(8f)) })
        col.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        col.addView(dpad(), LinearLayout.LayoutParams(dp(260f), dp(260f)).apply { topMargin = dp(26f) })

        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(Glass.key(this, "◁", 22f) { sendKey(RemoteKeys.BACK) }, rowLp())
        navRow.addView(Glass.key(this, "◯", 22f) { sendKey(RemoteKeys.HOME) }, rowLp())
        navRow.addView(Glass.key(this, "⌨", 20f) { toggleKeyboard() }, rowLp())
        col.addView(navRow, LinearLayout.LayoutParams(MATCH, dp(64f)).apply { topMargin = dp(18f) })

        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        volRow.addView(Glass.key(this, "－", 24f) { sendKey(RemoteKeys.VOLUME_DOWN) }, rowLp())
        volRow.addView(label("Громкость", 13f, alpha = 170).apply { gravity = Gravity.CENTER }, rowLp())
        volRow.addView(Glass.key(this, "＋", 24f) { sendKey(RemoteKeys.VOLUME_UP) }, rowLp())
        col.addView(volRow, LinearLayout.LayoutParams(MATCH, dp(64f)).apply { topMargin = dp(14f) })

        col.addView(label("Приложения", 13f, alpha = 150), lp(22))
        val appsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in prefs.apps) {
            appsRow.addView(
                Glass.button(this, a.label, 0x22FFFFFF, 0x3DFFFFFF) { launchApp(a) }
                    .apply { textSize = 13f },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (appsRow.childCount > 0) marginStart = dp(8f) }
            )
        }
        col.addView(appsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })

        col.addView(
            Glass.button(this, "Настройки", 0x18FFFFFF) { showSettings() }.apply { textSize = 13f },
            lp(22)
        )

        val cap = KeyCaptureView(this, { text -> sendText(text) }, { code -> sendRawKey(code) })
        capture = cap
        val frame = FrameLayout(this)
        frame.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(cap, FrameLayout.LayoutParams(1, 1))
        setScreen(frame)
    }

    private fun dpad(): View {
        val size = dp(260f)
        val btn = dp(84f)
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
        frame.addView(ok, FrameLayout.LayoutParams(dp(92f), dp(92f), Gravity.CENTER))
        return frame
    }

    private fun rowLp() = LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(6f); marginEnd = dp(6f) }

    // ------------------------------------------------------------------ settings
    private fun showSettings() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(26f), dp(20f), dp(20f))
        }
        col.addView(label("Настройки", 26f, bold = true))
        col.addView(
            label(
                "Пакеты YouTube и Netflix проверены для Android TV. RuTube и Кинопоиск отличаются " +
                    "по регионам и устройствам — если кнопка не открывает нужное приложение, впишите " +
                    "точное имя пакета сюда. Узнать его: установите приложение на телевизоре и выполните " +
                    "«adb shell pm list packages | grep <название>» с компьютера, либо посмотрите на сайте приложения.",
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
            Glass.button(this, "Сохранить", 0x553A7BFF, 0x883A7BFF.toInt()) {
                prefs.apps = fields.map { (a, e) -> AppShortcut(a.id, a.label, e.text.toString().trim().ifEmpty { a.pkg }) }
                toast("Сохранено")
                showRemote()
            },
            lp(20)
        )
        col.addView(
            Glass.button(this, "Сбросить пакеты к значениям по умолчанию", 0x18FFFFFF) {
                prefs.apps = com.example.tvremote.core.DefaultApps.defaults()
                showSettings()
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
        col.addView(Glass.button(this, "Назад") { showRemote() }, lp(10))
        setScreen(ScrollView(this).apply { addView(col) })
    }

    // ------------------------------------------------------------------ actions
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

    private fun sendRawKey(code: Int) = sendKey(code)

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

    private fun launchApp(app: AppShortcut) {
        val c = client ?: return
        toast("Открываю ${app.label}…")
        io.execute {
            try {
                val r = c.shell("monkey -p ${app.pkg} -c android.intent.category.LAUNCHER 1")
                val failed = r.output.contains("No activities found") || r.output.contains("Exception") || !r.ok
                runOnUiThread {
                    if (failed) toast("«${app.label}» не найдено на этом ТВ (пакет ${app.pkg}). Поправьте в Настройках.")
                }
            } catch (e: Exception) {
                runOnUiThread { onLost(e) }
            }
        }
    }

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
    }
}
