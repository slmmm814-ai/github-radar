package com.radar.plus

data class Repo(
    val fullName: String,
    val description: String,
    val url: String,
    val stars: Int,
    val forks: Int,
    val openIssues: Int,
    val language: String?,
    val license: String?,
    val createdAt: Long,
    val pushedAt: Long,
    val topics: List<String>,
    val archived: Boolean = false,
    val trendStars: Int = 0,
    val trendDays: Int = 0,
    val buzz: Int = 0,
    val buzzFrom: List<String> = emptyList(),
    val growth: Int = 0,
    val growthHours: Int = 0,
    val note: String = "",
    val score: Int = 0,
    val reasons: List<String> = emptyList()
)

data class Release(val tag: String, val name: String, val url: String, val publishedAt: Long, val body: String)

data class RepoDeep(
    val languages: List<Pair<String, Int>> = emptyList(),
    val contributors: List<Pair<String, Int>> = emptyList(),
    val commits4w: Int? = null,
    val release: Release? = null
)

data class Dev(
    val login: String,
    val name: String = "",
    val bio: String = "",
    val company: String = "",
    val location: String = "",
    val followers: Int = 0,
    val publicRepos: Int = 0,
    val type: String = "User",
    val url: String = "",
    val createdAt: Long = 0
)

data class DevDeep(val topRepos: List<Repo>, val totalStars: Int, val languages: List<Pair<String, Int>>)

data class Advisory(
    val id: String,
    val cve: String,
    val summary: String,
    val severity: String,
    val url: String,
    val published: Long,
    val packages: List<String>,
    val patched: String
)

data class Fav(val name: String, val desc: String)

enum class Src(val label: String) {
    TRENDING("GitHub Trending"),
    NEW("جديد حسب المجال"),
    HN("Hacker News"),
    LOBSTERS("Lobsters"),
    SERVER("خادم الرادار (GH Archive)")
}

enum class Domain(val label: String, val topics: List<String>) {
    AI("ذكاء اصطناعي", listOf("llm", "machine-learning")),
    AGENTS("وكلاء ذكاء اصطناعي", listOf("ai-agents", "mcp")),
    SECURITY("أمن سيبراني", listOf("security", "pentesting")),
    WEB("تطوير الويب", listOf("react", "nextjs")),
    MOBILE("تطبيقات الجوال", listOf("android", "flutter")),
    DEVTOOLS("أدوات المطورين", listOf("cli", "developer-tools")),
    DATA("بيانات وتحليل", listOf("data-science", "data-engineering")),
    SYSTEMS("أنظمة وRust", listOf("rust", "systems-programming"))
}

fun fmtDate(ms: Long): String =
    if (ms <= 0) "—" else java.time.Instant.ofEpochMilli(ms).toString().take(10)
