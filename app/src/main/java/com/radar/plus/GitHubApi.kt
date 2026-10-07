package com.radar.plus

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GitHubException(message: String) : Exception(message)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun parseTime(s: String): Long = runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

object GitHubApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, Pair<Long, Repo>>()

    private fun call(url: String, accept: String = "application/vnd.github+json"): Pair<Int, String> {
        val b = Request.Builder().url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
        val token = Store.ghToken
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        client.newCall(b.build()).execute().use { r ->
            return r.code to r.body?.string().orEmpty()
        }
    }

    private fun ensureOk(code: Int) {
        when {
            code == 403 || code == 429 ->
                throw GitHubException("تم تجاوز حدّ طلبات GitHub. أضف توكن في الإعدادات أو انتظر دقيقة.")
            code == 404 -> throw GitHubException("غير موجود على GitHub.")
            code !in 200..299 -> throw GitHubException("خطأ من GitHub ($code)")
        }
    }

    private fun request(url: String, accept: String = "application/vnd.github+json"): String {
        val (c, b) = call(url, accept)
        ensureOk(c)
        return b
    }

    private fun parse(o: JSONObject): Repo {
        val topicsArr = o.optJSONArray("topics")
        val topics = if (topicsArr == null) emptyList() else (0 until topicsArr.length()).map { topicsArr.getString(it) }
        val license = o.optJSONObject("license")?.str("spdx_id")
            ?.takeIf { it.isNotBlank() && it != "NOASSERTION" }
        val created = parseTime(o.str("created_at"))
        val pushed = parseTime(o.str("pushed_at")).let { if (it == 0L) created else it }
        return Repo(
            fullName = o.getString("full_name"),
            description = o.str("description"),
            url = o.getString("html_url"),
            stars = o.optInt("stargazers_count"),
            forks = o.optInt("forks_count"),
            openIssues = o.optInt("open_issues_count"),
            language = o.str("language").ifBlank { null },
            license = license,
            createdAt = created,
            pushedAt = pushed,
            topics = topics,
            archived = o.optBoolean("archived")
        )
    }

    fun search(query: String, perPage: Int = 20, sort: String = "stars"): List<Repo> {
        val json = JSONObject(request("https://api.github.com/search/repositories?q=${enc(query)}&sort=$sort&order=desc&per_page=$perPage"))
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { parse(items.getJSONObject(it)) }
    }

    fun repo(fullName: String): Repo {
        val now = System.currentTimeMillis()
        cache[fullName]?.let { if (now - it.first < 2 * 3_600_000L) return it.second }
        val r = parse(JSONObject(request("https://api.github.com/repos/$fullName")))
        cache[fullName] = now to r
        return r
    }

    fun readme(fullName: String): String = try {
        request("https://api.github.com/repos/$fullName/readme", "application/vnd.github.raw+json")
    } catch (e: GitHubException) {
        ""
    }

    // ---------- تفاصيل المستودع ----------
    private fun languages(name: String): List<Pair<String, Int>> {
        val o = JSONObject(request("https://api.github.com/repos/$name/languages"))
        val m = HashMap<String, Long>()
        var total = 0L
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.getLong(k)
            m[k] = v
            total += v
        }
        if (total == 0L) return emptyList()
        return m.entries.sortedByDescending { it.value }.take(5).map { it.key to (it.value * 100 / total).toInt() }
    }

    private fun contributors(name: String): List<Pair<String, Int>> {
        val (c, b) = call("https://api.github.com/repos/$name/contributors?per_page=5")
        if (c != 200 || b.isBlank()) return emptyList()
        val arr = JSONArray(b)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.str("login") to o.optInt("contributions")
        }
    }

    private fun commits4w(name: String): Int? {
        val (c, b) = call("https://api.github.com/repos/$name/stats/participation")
        if (c != 200 || b.isBlank()) return null
        val all = JSONObject(b).optJSONArray("all") ?: return null
        if (all.length() < 4) return null
        var sum = 0
        for (i in all.length() - 4 until all.length()) sum += all.getInt(i)
        return sum
    }

    fun latestRelease(name: String): Release? {
        val (c, b) = call("https://api.github.com/repos/$name/releases/latest")
        if (c == 404) return null
        ensureOk(c)
        val o = JSONObject(b)
        return Release(o.str("tag_name"), o.str("name"), o.str("html_url"), parseTime(o.str("published_at")), o.str("body"))
    }

    fun deep(name: String): RepoDeep = RepoDeep(
        languages = runCatching { languages(name) }.getOrDefault(emptyList()),
        contributors = runCatching { contributors(name) }.getOrDefault(emptyList()),
        commits4w = runCatching { commits4w(name) }.getOrNull(),
        release = runCatching { latestRelease(name) }.getOrNull()
    )

    // ---------- المطورون والمنظمات ----------
    fun searchUsers(q: String): List<Dev> {
        val json = JSONObject(request("https://api.github.com/search/users?q=${enc(q)}&sort=followers&order=desc&per_page=15"))
        val items = json.getJSONArray("items")
        return (0 until items.length()).map {
            val o = items.getJSONObject(it)
            Dev(login = o.str("login"), type = o.str("type").ifBlank { "User" }, url = o.str("html_url"))
        }
    }

    fun user(login: String): Dev {
        val o = JSONObject(request("https://api.github.com/users/$login"))
        return Dev(
            login = o.str("login"),
            name = o.str("name"),
            bio = o.str("bio"),
            company = o.str("company"),
            location = o.str("location"),
            followers = o.optInt("followers"),
            publicRepos = o.optInt("public_repos"),
            type = o.str("type").ifBlank { "User" },
            url = o.str("html_url"),
            createdAt = parseTime(o.str("created_at"))
        )
    }

    fun devDeep(login: String): DevDeep {
        val arr = JSONArray(request("https://api.github.com/users/$login/repos?per_page=100&sort=pushed"))
        val repos = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("fork") }
            .map { parse(it) }
        val total = repos.sumOf { it.stars }
        val n = repos.size.coerceAtLeast(1)
        val langs = repos.mapNotNull { it.language }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(5)
            .map { it.key to (it.value * 100 / n) }
        val top = repos.sortedByDescending { it.stars }.take(6).map { Scoring.score(it) }
        return DevDeep(top, total, langs)
    }

    // ---------- الثغرات الأمنية ----------
    fun advisories(ecosystem: String?, severity: String?): List<Advisory> {
        val sb = StringBuilder("https://api.github.com/advisories?type=reviewed&per_page=30&sort=published&direction=desc")
        if (!ecosystem.isNullOrBlank()) sb.append("&ecosystem=").append(enc(ecosystem))
        if (!severity.isNullOrBlank()) sb.append("&severity=").append(enc(severity))
        val arr = JSONArray(request(sb.toString()))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val vulns = o.optJSONArray("vulnerabilities")
            val pkgs = mutableListOf<String>()
            var patched = ""
            if (vulns != null) {
                for (i in 0 until vulns.length()) {
                    val v = vulns.getJSONObject(i)
                    val p = v.optJSONObject("package")
                    if (p != null) pkgs += p.str("ecosystem") + ":" + p.str("name")
                    if (patched.isBlank()) patched = v.str("first_patched_version")
                }
            }
            Advisory(
                id = o.str("ghsa_id"),
                cve = o.str("cve_id"),
                summary = o.str("summary"),
                severity = o.str("severity"),
                url = o.str("html_url"),
                published = parseTime(o.str("published_at")),
                packages = pkgs.distinct().take(4),
                patched = patched
            )
        }
    }
}
