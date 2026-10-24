package com.example.tvremote.core

/** Builders for the `pm`/`wm`/`cmd` shell commands the Apps and TV-settings screens use. Pure string
 * construction so the exact command text is unit-testable without a real device. */
object ShellCommands {
    fun listPackages(includeSystem: Boolean): String =
        if (includeSystem) "pm list packages -f" else "pm list packages -f -3"

    fun launch(pkg: String): String = "monkey -p ${ShellQuote.single(pkg)} -c android.intent.category.LAUNCHER 1"

    fun uninstall(pkg: String): String = "pm uninstall --user 0 ${ShellQuote.single(pkg)}"

    fun disable(pkg: String): String = "pm disable-user --user 0 ${ShellQuote.single(pkg)}"

    fun enable(pkg: String): String = "pm enable ${ShellQuote.single(pkg)}"

    fun clearData(pkg: String): String = "pm clear ${ShellQuote.single(pkg)}"

    fun appInfo(pkg: String): String = "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${pkg}"

    /** Streaming install: `pm install -S <bytes>` reads the APK from stdin, which is what we push after this. */
    fun installFromStdin(apkSize: Long): String = "cmd package install -r -t -S $apkSize -"

    fun setLocale(bcp47Tag: String): String = "cmd locale set-locale ${ShellQuote.single(bcp47Tag)}"

    fun getLocale(): String = "getprop persist.sys.locale"

    fun setResolution(width: Int, height: Int): String = "wm size ${width}x${height}"
    fun resetResolution(): String = "wm size reset"

    fun setDensity(dpi: Int): String = "wm density $dpi"
    fun resetDensity(): String = "wm density reset"

    fun openDisplaySettings(): String = "am start -a android.settings.DISPLAY_SETTINGS"

    /** Asks the kernel which local address it would use to reach the internet — the simplest reliable way to
     * find the device's own LAN IP regardless of whether it's on wlan0, eth0, or something OEM-specific. */
    fun detectIp(): String = "ip route get 1.1.1.1"

    private val SRC_IP = Regex("""src (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")

    /** Pulls the "src X.X.X.X" address out of `ip route get` output, e.g.
     * "1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.42 uid 2000" -> "192.168.1.42". */
    fun parseDetectedIp(output: String): String? = SRC_IP.find(output)?.groupValues?.get(1)

    /** Parses one line of `pm list packages -f`: "package:/data/app/.../base.apk=com.example.app". */
    fun parsePackageLine(line: String): InstalledApp? {
        val body = line.removePrefix("package:").trim()
        if (body.isEmpty()) return null
        val eq = body.lastIndexOf('=')
        return if (eq < 0) InstalledApp(body, "") else InstalledApp(body.substring(eq + 1), body.substring(0, eq))
    }
}

class InstalledApp(val pkg: String, val apkPath: String)
