package com.example.tvremote

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.tvremote.core.InstalledApp
import com.example.tvremote.core.ShellCommands

/** Lists installed apps on the TV (launch / uninstall-or-disable / clear data) and installs new APKs. The
 * TV only tells us package ids and apk paths over shell — not icons or human-readable labels. */
class AppsScreen(private val host: MainActivity) : FrameLayout(host) {
    private val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private var showSystem = false
    private var loading = false

    init {
        addView(ScrollView(host).apply { addView(body) }, LayoutParams(MATCH, MATCH))
        render(emptyList(), loadingNow = true)
        refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        render(emptyList(), loadingNow = true)
        host.runShell(ShellCommands.listPackages(showSystem), 15_000) { result ->
            loading = false
            val apps = result.output.lineSequence()
                .mapNotNull { ShellCommands.parsePackageLine(it.trim()) }
                .sortedBy { it.pkg }
                .toList()
            render(apps, loadingNow = false, failed = !result.ok && apps.isEmpty())
        }
    }

    private fun render(apps: List<InstalledApp>, loadingNow: Boolean, failed: Boolean = false) {
        body.removeAllViews()
        body.setPadding(d(16f), d(18f), d(16f), d(16f))

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Приложения", 26f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(round("⟳") { refresh() })
        header.addView(round("＋") { host.pickApkToInstall() }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = d(8f) })
        body.addView(header)

        body.addView(
            switchRow("Показывать системные", showSystem) { showSystem = it; refresh() },
            lp(10)
        )

        if (loadingNow) {
            body.addView(
                LinearLayout(host).apply {
                    gravity = Gravity.CENTER
                    addView(ProgressBar(host))
                },
                LinearLayout.LayoutParams(MATCH, d(80f)).apply { topMargin = d(20f) }
            )
            return
        }
        if (failed) {
            body.addView(label("Не удалось получить список приложений", 14f, alpha = 170).apply { gravity = Gravity.CENTER }, lp(30))
            return
        }
        if (apps.isEmpty()) {
            body.addView(label("Ничего не найдено", 14f, alpha = 150).apply { gravity = Gravity.CENTER }, lp(30))
            return
        }
        for (a in apps) {
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.drawable(host, 18f)
                setPadding(d(14f), d(12f), d(14f), d(12f))
            }
            card.addView(label(a.pkg, 15f, bold = true))
            if (a.apkPath.isNotEmpty()) card.addView(label(a.apkPath, 11f, alpha = 130), lp(2))
            val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallButton("Открыть") { host.launchApp(a.pkg) }, rowLp())
            row.addView(smallButton("Удалить") { confirmRemove(a) }, rowLp())
            row.addView(smallButton("⋮") { moreActions(a) }, rowLp(0.5f))
            card.addView(row, lp(10))
            body.addView(card, lp(10))
        }
    }

    private fun confirmRemove(a: InstalledApp) {
        AlertDialog.Builder(host)
            .setTitle(a.pkg)
            .setMessage("Удалить это приложение с телевизора? Если это системное приложение, вместо удаления оно будет отключено.")
            .setPositiveButton("Удалить") { _, _ ->
                host.runShell(ShellCommands.uninstall(a.pkg)) { r ->
                    if (!r.ok || r.output.contains("Failure")) {
                        host.runShell(ShellCommands.disable(a.pkg)) { r2 ->
                            toast(if (r2.output.contains("new state")) "Отключено (системное приложение)" else "Не удалось: ${r2.output.trim()}")
                            refresh()
                        }
                    } else {
                        toast("Удалено")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun moreActions(a: InstalledApp) {
        val items = arrayOf("Очистить данные", "Включить (если отключено)", "Экран сведений об приложении на ТВ")
        AlertDialog.Builder(host)
            .setTitle(a.pkg)
            .setItems(items) { _, which ->
                val cmd = when (which) {
                    0 -> ShellCommands.clearData(a.pkg)
                    1 -> ShellCommands.enable(a.pkg)
                    else -> ShellCommands.appInfo(a.pkg)
                }
                host.runShell(cmd) { r -> toast(if (r.ok) "Готово" else "Не удалось") }
            }
            .show()
    }

    // ------------------------------------------------------------------ small helpers
    private fun round(symbol: String, onClick: () -> Unit) = TextView(host).apply {
        text = symbol
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = Glass.circle(host, 0x22FFFFFF)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(d(42f), d(42f))
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Glass.button(host, text, 0x1FFFFFFF, 0x3DFFFFFF) { onClick() }
        .apply { textSize = 13f; setPadding(d(10f), d(8f), d(10f), d(8f)) }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(host).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        isChecked = checked
        background = Glass.drawable(host, 18f)
        setPadding(d(14f), d(10f), d(14f), d(10f))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun label(text: String, sp: Float, alpha: Int = 255, bold: Boolean = false): TextView = TextView(host).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.argb(alpha, 255, 255, 255))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(top.toFloat()) }
    private fun rowLp(weight: Float = 1f) = LinearLayout.LayoutParams(0, WRAP, weight).apply { marginEnd = d(6f) }
    private fun d(v: Float) = context.dp(v)
    private fun toast(s: String) = Toast.makeText(host, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
