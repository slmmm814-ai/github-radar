package com.radar.plus

import java.time.LocalDate
import java.util.Locale

object Discovery {
    private fun upsert(map: MutableMap<String, Repo>, name: String, loader: () -> Repo?, change: (Repo) -> Repo) {
        val cur = map[name] ?: runCatching { loader() }.getOrNull() ?: return
        map[name] = change(cur)
    }

    /** يجمع من المصادر المفعّلة، يحسب النمو الحقيقي من لقطات سابقة، ثم يقيّم ويرتّب. */
    fun discover(domains: Set<Domain>, days: Int = 7): List<Repo> {
        val sources = Store.sources
        val hasToken = Store.ghToken.isNotBlank()
        val map = LinkedHashMap<String, Repo>()
        var lastError: GitHubException? = null

        if (Src.NEW in sources) {
            val since = LocalDate.now().minusDays(days.toLong()).toString()
            val perDomain = if (hasToken) 2 else 1
            for (d in domains) {
                for (t in d.topics.take(perDomain)) {
                    try {
                        GitHubApi.search("topic:$t created:>$since stars:>15", 15)
                            .forEach { map.putIfAbsent(it.fullName, it) }
                    } catch (e: GitHubException) {
                        lastError = e
                    }
                }
            }
        }

        if (Src.TRENDING in sources) {
            val since = Store.trendSince
            val tdays = when (since) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
            val items = runCatching { TrendingApi.fetch(since, Store.trendLang) }
                .getOrDefault(emptyList())
                .take(if (hasToken) 15 else 8)
            for (item in items) {
                upsert(map, item.name, { GitHubApi.repo(item.name) }) { r ->
                    r.copy(trendStars = item.stars, trendDays = tdays)
                }
            }
        }

        if (Src.SERVER in sources) {
            val items = runCatching { ServerApi.trending() }
                .getOrDefault(emptyList())
                .take(if (hasToken) 15 else 8)
            for (item in items) {
                val acc = item.accel?.let { "، تسارع ×" + String.format(Locale.US, "%.1f", it) } ?: ""
                val note = "GH Archive: +${item.stars} نجمة و${item.forks} تفرّع خلال ${item.windowHours} ساعة$acc"
                upsert(map, item.repo, { GitHubApi.repo(item.repo) }) { r ->
                    if (r.trendStars == 0) r.copy(trendStars = item.stars, trendDays = 1, note = note)
                    else r.copy(note = note)
                }
            }
        }

        if (Src.HN in sources) {
            for ((name, pts) in runCatching { HackerNewsApi.trendingRepos() }.getOrDefault(emptyMap())) {
                upsert(map, name, { GitHubApi.repo(name) }) { r ->
                    r.copy(buzz = r.buzz + pts, buzzFrom = r.buzzFrom + "Hacker News")
                }
            }
        }

        if (Src.LOBSTERS in sources) {
            for ((name, pts) in runCatching { LobstersApi.hot() }.getOrDefault(emptyMap())) {
                upsert(map, name, { GitHubApi.repo(name) }) { r ->
                    r.copy(buzz = r.buzz + pts, buzzFrom = r.buzzFrom + "Lobsters")
                }
            }
        }

        val err = lastError
        if (map.isEmpty() && err != null) throw err

        val now = System.currentTimeMillis()
        val gap = 3 * 3_600_000L
        val snaps = Store.snapshots().toMutableMap()
        val withGrowth = map.values.map { r ->
            val prev = snaps[r.fullName]
            val out = if (prev != null && now - prev.first >= gap && r.stars > prev.second)
                r.copy(growth = r.stars - prev.second, growthHours = ((now - prev.first) / 3_600_000L).toInt())
            else r
            if (prev == null || now - prev.first >= gap) snaps[r.fullName] = now to r.stars
            out
        }
        Store.saveSnapshots(snaps)

        return withGrowth.map { Scoring.score(it) }.sortedByDescending { it.score }
    }
}
