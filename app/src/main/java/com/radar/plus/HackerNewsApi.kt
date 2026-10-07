package com.radar.plus

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val ghRegex = Regex("""https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""")
private val skipOwners = setOf("sponsors", "orgs", "topics", "features", "about", "settings", "marketplace", "collections")

private fun repoName(url: String): String? {
    val m = ghRegex.find(url) ?: return null
    val owner = m.groupValues[1]
    if (owner in skipOwners) return null
    return owner + "/" + m.groupValues[2].removeSuffix(".git")
}

private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

object HackerNewsApi {
    /** أكثر المستودعات نقاشًا في Hacker News: الاسم -> النقاط */
    fun trendingRepos(days: Int = 3, limit: Int = 8): Map<String, Int> {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        val filter = URLEncoder.encode("created_at_i>$since", "UTF-8")
        val url = "https://hn.algolia.com/api/v1/search?query=github.com&tags=story&numericFilters=$filter&hitsPerPage=60"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return emptyMap()
            val hits = JSONObject(r.body?.string().orEmpty()).getJSONArray("hits")
            val out = HashMap<String, Int>()
            for (i in 0 until hits.length()) {
                val h = hits.getJSONObject(i)
                val name = repoName(h.optString("url")) ?: continue
                out[name] = maxOf(out[name] ?: 0, h.optInt("points"))
            }
            return out.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
        }
    }
}

object LobstersApi {
    fun hot(limit: Int = 8): Map<String, Int> {
        http.newCall(Request.Builder().url("https://lobste.rs/hottest.json").build()).execute().use { r ->
            if (!r.isSuccessful) return emptyMap()
            val arr = JSONArray(r.body?.string().orEmpty())
            val out = HashMap<String, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = repoName(o.optString("url")) ?: continue
                out[name] = maxOf(out[name] ?: 0, o.optInt("score"))
            }
            return out.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
        }
    }
}

object TrendingApi {
    data class Item(val name: String, val stars: Int)

    /** يقرأ صفحة github.com/trending (HTML) — قد يتعطل إن غيّر GitHub تصميم الصفحة. */
    fun fetch(since: String, lang: String): List<Item> {
        val path = if (lang.isBlank()) "" else "/" + URLEncoder.encode(lang.trim().lowercase(), "UTF-8")
        val req = Request.Builder()
            .url("https://github.com/trending$path?since=$since")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Android) RadarPlus")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val html = r.body?.string().orEmpty()
            val nameRx = Regex("""<h2[^>]*>\s*<a[^>]*href="/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)""")
            val starsRx = Regex("""([\d,]+)\s+stars?\s+(?:today|this week|this month)""")
            return html.split("<article").drop(1).mapNotNull { block ->
                val name = nameRx.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                val st = starsRx.find(block)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
                Item(name, st)
            }.distinctBy { it.name }
        }
    }
}
