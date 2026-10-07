package com.radar.plus

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** عميل خادم الرادار (GH Archive): نجوم وتفرّعات آخر 24 ساعة مع تسارعها. */
object ServerApi {
    data class Item(val repo: String, val stars: Int, val forks: Int, val accel: Double?, val windowHours: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun trending(window: Int = 24, limit: Int = 20): List<Item> {
        val base = Store.serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        // رابط ملف JSON ثابت (مثل raw.githubusercontent.com/.../trending.json) أو خادم الرادار
        val url = if (base.endsWith(".json")) base else "$base/trending?window=$window&limit=$limit&min_stars=10&sort=heat"
        val b = Request.Builder().url(url)
        if (Store.serverKey.isNotBlank()) b.header("X-Api-Key", Store.serverKey)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = JSONObject(r.body?.string().orEmpty()).getJSONArray("items")
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(
                    repo = o.getString("repo"),
                    stars = o.optInt("stars"),
                    forks = o.optInt("forks"),
                    accel = if (o.isNull("accel")) null else o.getDouble("accel"),
                    windowHours = window
                )
            }
        }
    }
}
