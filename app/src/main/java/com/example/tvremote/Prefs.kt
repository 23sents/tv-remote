package com.example.tvremote

import android.content.Context
import com.example.tvremote.core.AppShortcut
import com.example.tvremote.core.DefaultApps
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tvremote", Context.MODE_PRIVATE)

    var lastHost: String
        get() = sp.getString("host", "") ?: ""
        set(v) { sp.edit().putString("host", v).apply() }

    var lastPort: Int
        get() = sp.getInt("port", 5555)
        set(v) { sp.edit().putInt("port", v).apply() }

    var recent: List<String>
        get() = (sp.getString("recent", null)?.split("|") ?: emptyList()).filter { it.isNotBlank() }
        set(v) { sp.edit().putString("recent", v.distinct().take(6).joinToString("|")).apply() }

    fun rememberHost(hostPort: String) {
        recent = listOf(hostPort) + recent.filter { it != hostPort }
    }

    var apps: List<AppShortcut>
        get() {
            val raw = sp.getString("apps", null) ?: return DefaultApps.defaults()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    AppShortcut(o.getString("id"), o.getString("label"), o.getString("pkg"))
                }
            } catch (e: Exception) {
                DefaultApps.defaults()
            }
        }
        set(v) {
            val arr = JSONArray()
            for (a in v) arr.put(JSONObject().put("id", a.id).put("label", a.label).put("pkg", a.pkg))
            sp.edit().putString("apps", arr.toString()).apply()
        }
}
