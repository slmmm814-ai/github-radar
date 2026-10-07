package com.radar.plus

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) {
            sp = c.applicationContext.getSharedPreferences("radar", Context.MODE_PRIVATE)
        }
    }

    var ghToken: String
        get() = sp.getString("gh", "") ?: ""
        set(v) { sp.edit().putString("gh", v.trim()).apply() }

    var claudeKey: String
        get() = sp.getString("claude", "") ?: ""
        set(v) { sp.edit().putString("claude", v.trim()).apply() }

    /** "anthropic" (Claude) أو "openai" (OpenRouter وأي مزوّد متوافق مع OpenAI) */
    var apiFormat: String
        get() = sp.getString("apifmt", "anthropic") ?: "anthropic"
        set(v) { sp.edit().putString("apifmt", v).apply() }

    var apiBase: String
        get() = (sp.getString("apibase", "") ?: "").ifBlank {
            if (apiFormat == "openai") "https://openrouter.ai/api/v1" else "https://api.anthropic.com"
        }
        set(v) { sp.edit().putString("apibase", v.trim()).apply() }

    var model: String
        get() = (sp.getString("model", "") ?: "").ifBlank { "claude-sonnet-5-5" }
        set(v) { sp.edit().putString("model", v.trim()).apply() }

    var notify: Boolean
        get() = sp.getBoolean("notify", false)
        set(v) { sp.edit().putBoolean("notify", v).apply() }

    var notifyReleases: Boolean
        get() = sp.getBoolean("notifyRel", false)
        set(v) { sp.edit().putBoolean("notifyRel", v).apply() }

    var trendSince: String
        get() = sp.getString("tsince", "daily") ?: "daily"
        set(v) { sp.edit().putString("tsince", v).apply() }

    var trendLang: String
        get() = sp.getString("tlang", "") ?: ""
        set(v) { sp.edit().putString("tlang", v.trim()).apply() }

    var serverUrl: String
        get() = sp.getString("srvurl", "") ?: ""
        set(v) { sp.edit().putString("srvurl", v.trim()).apply() }

    var serverKey: String
        get() = sp.getString("srvkey", "") ?: ""
        set(v) { sp.edit().putString("srvkey", v.trim()).apply() }

    var sources: Set<Src>
        get() = (sp.getStringSet("src", setOf("TRENDING", "NEW", "HN")) ?: emptySet())
            .mapNotNull { runCatching { Src.valueOf(it) }.getOrNull() }
            .toSet()
            .ifEmpty { setOf(Src.NEW) }
        set(v) { sp.edit().putStringSet("src", v.map { it.name }.toSet()).apply() }

    var domains: Set<Domain>
        get() = (sp.getStringSet("domains", setOf("AI")) ?: setOf("AI"))
            .mapNotNull { runCatching { Domain.valueOf(it) }.getOrNull() }
            .toSet()
            .ifEmpty { setOf(Domain.AI) }
        set(v) { sp.edit().putStringSet("domains", v.map { it.name }.toSet()).apply() }

    var seen: Set<String>
        get() = sp.getStringSet("seen", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("seen", v.toList().takeLast(500).toSet()).apply() }

    fun favorites(): List<Fav> {
        val arr = runCatching { JSONArray(sp.getString("favs", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Fav(o.getString("n"), o.optString("d"))
        }
    }

    fun toggleFav(name: String, desc: String): List<Fav> {
        val cur = favorites().toMutableList()
        if (!cur.removeAll { it.name == name }) cur.add(0, Fav(name, desc))
        val arr = JSONArray()
        cur.forEach { arr.put(JSONObject().put("n", it.name).put("d", it.desc)) }
        sp.edit().putString("favs", arr.toString()).apply()
        return cur
    }

    /** لقطات عدد النجوم لحساب النمو الحقيقي بين فحصين. */
    fun snapshots(): Map<String, Pair<Long, Int>> {
        val o = runCatching { JSONObject(sp.getString("snap", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val out = HashMap<String, Pair<Long, Int>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = o.getJSONObject(k)
            out[k] = e.getLong("t") to e.getInt("s")
        }
        return out
    }

    fun saveSnapshots(m: Map<String, Pair<Long, Int>>) {
        val o = JSONObject()
        m.entries.sortedByDescending { it.value.first }.take(600).forEach {
            o.put(it.key, JSONObject().put("t", it.value.first).put("s", it.value.second))
        }
        sp.edit().putString("snap", o.toString()).apply()
    }

    fun releaseSeen(name: String): String? {
        val o = runCatching { JSONObject(sp.getString("rel", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return o.optString(name, "").ifBlank { null }
    }

    fun markRelease(name: String, tag: String) {
        val o = runCatching { JSONObject(sp.getString("rel", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.put(name, tag)
        sp.edit().putString("rel", o.toString()).apply()
    }

    fun clearHistory() {
        sp.edit().remove("seen").remove("snap").remove("rel").apply()
    }
}
