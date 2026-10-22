package com.example.tvremote

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.tvremote.core.ShellCommands

/** Language and display-format controls. Locale changes are safe and reversible. Resolution/density changes
 * can leave a TV with an unreadable or oversized picture if the wrong value is picked, so those are clearly
 * marked, offer a one-tap reset, and the exact HDMI output format (as opposed to Android's internal render
 * resolution) usually isn't reachable from shell at all — for that we just jump to the TV's own menu. */
class TvSettingsScreen(private val host: MainActivity) : FrameLayout(host) {
    private val col = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(16f), host.dp(18f), host.dp(16f), host.dp(16f))
    }

    private val locales = listOf(
        "Русский" to "ru-RU", "English (US)" to "en-US", "English (UK)" to "en-GB",
        "Українська" to "uk-UA", "Қазақша" to "kk-KZ", "Deutsch" to "de-DE", "Español" to "es-ES",
    )
    private val resolutions = listOf("3840×2160" to (3840 to 2160), "1920×1080" to (1920 to 1080), "1280×720" to (1280 to 720))
    private val densities = listOf("Меньше" to 160, "Обычно" to 213, "Крупнее" to 240, "Очень крупно" to 320)

    init {
        addView(ScrollView(host).apply { addView(col) }, LayoutParams(MATCH, MATCH))
        build()
    }

    private fun build() {
        col.removeAllViews()
        col.addView(label("Настройки ТВ", 26f, bold = true))
        col.addView(label("Команды выполняются прямо на телевизоре через то же соединение, что и пульт.", 13f, alpha = 160), lp(4))

        section("Язык")
        col.addView(flowRow { row -> for ((name, tag) in locales) row.addView(chip(name) { setLocale(tag) }) }, lp(8))
        col.addView(
            Glass.button(host, "Свой код языка (например kk-KZ)", 0x18FFFFFF) { customLocaleDialog() },
            lp(10)
        )

        section("Разрешение экрана (внутренний рендер Android, не всегда меняет формат сигнала HDMI)")
        col.addView(flowRow { row -> for ((name, wh) in resolutions) row.addView(chip(name) { setResolution(wh.first, wh.second) }) }, lp(8))
        col.addView(Glass.button(host, "Сбросить разрешение", 0x18FFFFFF) { run(ShellCommands.resetResolution()) }, lp(10))

        section("Масштаб интерфейса")
        col.addView(flowRow { row -> for ((name, dpi) in densities) row.addView(chip(name) { setDensity(dpi) }) }, lp(8))
        col.addView(Glass.button(host, "Сбросить масштаб", 0x18FFFFFF) { run(ShellCommands.resetDensity()) }, lp(10))

        col.addView(
            label(
                "Если после смены разрешения или масштаба картинка стала нечитаемой — зайдите на эту вкладку " +
                    "ещё раз и нажмите «Сбросить», пульт продолжит работать даже при неправильном разрешении.",
                12f, alpha = 150
            ),
            lp(14)
        )

        section("Настоящий формат вывода HDMI и другие системные экраны")
        col.addView(
            Glass.button(host, "Открыть «Экран» на телевизоре", 0x18FFFFFF) { run(ShellCommands.openDisplaySettings()) },
            lp(8)
        )
        col.addView(
            label("Формат HDMI (разрешение/частоту реального сигнала) на большинстве ТВ нельзя переключить одной командой — она отличается у каждого производителя. Эта кнопка открывает настоящее системное меню на экране ТВ, дальше выбирайте обычным пультом.", 12f, alpha = 150),
            lp(6)
        )
    }

    private fun section(title: String) = col.addView(label(title.uppercase(), 12f, alpha = 150).apply { letterSpacing = 0.08f }, lp(22))

    private fun customLocaleDialog() {
        val input = EditText(host).apply {
            hint = "ru-RU"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(host)
            .setTitle("Код языка (BCP-47)")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                val tag = input.text.toString().trim()
                if (tag.isNotEmpty()) setLocale(tag)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setLocale(tag: String) = run(ShellCommands.setLocale(tag), "Язык изменён на $tag")
    private fun setResolution(w: Int, h: Int) = run(ShellCommands.setResolution(w, h), "Разрешение: ${w}×$h")
    private fun setDensity(dpi: Int) = run(ShellCommands.setDensity(dpi), "Масштаб изменён")

    private fun run(cmd: String, okMessage: String? = null) {
        host.runShell(cmd, 10_000) { r ->
            toast(if (r.ok) (okMessage ?: "Готово") else "Не удалось: ${r.output.trim().ifEmpty { "нет ответа" }}")
        }
    }

    // ------------------------------------------------------------------ small helpers
    /** A horizontally scrolling row of chips (so they don't overflow narrow phones). */
    private fun flowRow(fill: (LinearLayout) -> Unit): android.widget.HorizontalScrollView {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        fill(row)
        return android.widget.HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    private fun chip(text: String, onClick: () -> Unit) = Glass.button(host, text, 0x22FFFFFF, 0x40FFFFFF) { onClick() }.apply {
        textSize = 13f
        setPadding(d(14f), d(9f), d(14f), d(9f))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = d(8f) }
    }

    private fun label(text: String, sp: Float, alpha: Int = 255, bold: Boolean = false): TextView = TextView(host).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.argb(alpha, 255, 255, 255))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(top.toFloat()) }
    private fun d(v: Float) = context.dp(v)
    private fun toast(s: String) = Toast.makeText(host, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
