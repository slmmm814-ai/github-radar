package com.radar.plus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** التبويبات: 0 اكتشف، 1 ابحث، 2 حلّل، 3 أمان، 4 تابع، 5 الإعدادات */
class AppViewModel : ViewModel() {
    var tab by mutableIntStateOf(0)

    // اكتشف
    var repos by mutableStateOf<List<Repo>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var digest by mutableStateOf<String?>(null)
    var digestLoading by mutableStateOf(false)
    var activeDomains by mutableStateOf(Store.domains)

    // ابحث
    var searchMode by mutableIntStateOf(0)
    var searchQuery by mutableStateOf("")
    var searchLang by mutableStateOf("")
    var searchMin by mutableStateOf("")
    var searchSortUpdated by mutableStateOf(false)
    var searchRepos by mutableStateOf<List<Repo>>(emptyList())
    var searchDevs by mutableStateOf<List<Dev>>(emptyList())
    var searching by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)

    // حلّل
    var analyzeInput by mutableStateOf("")
    var compareInput by mutableStateOf("")
    var learnMode by mutableStateOf(false)
    var analyzing by mutableStateOf(false)
    var analyzeError by mutableStateOf<String?>(null)
    var analyzeText by mutableStateOf<String?>(null)
    var analyzeRepo by mutableStateOf<Repo?>(null)
    var analyzeDeep by mutableStateOf<RepoDeep?>(null)
    var compareRepo by mutableStateOf<Repo?>(null)
    var compareDeep by mutableStateOf<RepoDeep?>(null)
    var analyzeDev by mutableStateOf<Dev?>(null)
    var analyzeDevDeep by mutableStateOf<DevDeep?>(null)

    // أمان
    var advisories by mutableStateOf<List<Advisory>>(emptyList())
    var advLoading by mutableStateOf(false)
    var advError by mutableStateOf<String?>(null)
    var advEco by mutableStateOf("")
    var advSev by mutableStateOf("critical")
    var advSummary by mutableStateOf<String?>(null)

    // تابع
    var favs by mutableStateOf(Store.favorites())
    var releases by mutableStateOf<Map<String, Release?>>(emptyMap())
    var relLoading by mutableStateOf(false)
    var relError by mutableStateOf<String?>(null)
    var relSummaryFor by mutableStateOf<String?>(null)
    var relSummary by mutableStateOf<String?>(null)

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "تعذّر الاتصال. تحقق من الإنترنت."
        else -> e.message ?: "خطأ غير متوقع"
    }

    // ---------- اكتشف ----------
    fun refresh() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                repos = withContext(Dispatchers.IO) { Discovery.discover(activeDomains) }
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                loading = false
            }
        }
    }

    fun toggleDomain(d: Domain) {
        val n = if (d in activeDomains) activeDomains - d else activeDomains + d
        if (n.isEmpty()) return
        activeDomains = n
        Store.domains = n
        refresh()
    }

    fun makeDigest() {
        viewModelScope.launch {
            digestLoading = true; error = null
            try {
                val top = repos.take(6)
                digest = withContext(Dispatchers.IO) { Reports.digest(top) }
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                digestLoading = false
            }
        }
    }

    // ---------- ابحث ----------
    fun search() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            searching = true; searchError = null
            try {
                if (searchMode == 0) {
                    val full = buildString {
                        append(q)
                        if (searchLang.isNotBlank()) append(" language:").append(searchLang.trim())
                        val m = searchMin.trim().toIntOrNull()
                        if (m != null && m > 0) append(" stars:>=").append(m)
                    }
                    val sort = if (searchSortUpdated) "updated" else "stars"
                    searchRepos = withContext(Dispatchers.IO) {
                        GitHubApi.search(full, 20, sort).map { Scoring.score(it) }
                    }
                } else {
                    searchDevs = withContext(Dispatchers.IO) { GitHubApi.searchUsers(q) }
                }
            } catch (e: Exception) {
                searchError = friendly(e)
            } finally {
                searching = false
            }
        }
    }

    // ---------- حلّل ----------
    private fun parseRepoName(s: String): String? {
        val t = s.trim().let { if ("github.com/" in it) it.substringAfter("github.com/") else it }
        val m = Regex("""([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""").find(t) ?: return null
        return m.groupValues[1] + "/" + m.groupValues[2].removeSuffix(".git")
    }

    private fun parseLogin(s: String): String? {
        val t = s.trim().removePrefix("@").let { if ("github.com/" in it) it.substringAfter("github.com/") else it }
        val login = t.substringBefore("/").trim()
        return if (Regex("""[A-Za-z0-9-]{1,39}""").matches(login)) login else null
    }

    fun analyze(input: String = analyzeInput) {
        val repoName = parseRepoName(input)
        val login = if (repoName == null) parseLogin(input) else null
        if (repoName == null && login == null) {
            analyzeError = "اكتب رابط مستودع أو owner/repo، أو اسم مطوّر/منظمة."
            return
        }
        val cmpName = if (repoName != null) parseRepoName(compareInput) else null
        val learn = learnMode

        tab = 2
        analyzing = true
        analyzeError = null
        analyzeText = null
        analyzeRepo = null; analyzeDeep = null
        compareRepo = null; compareDeep = null
        analyzeDev = null; analyzeDevDeep = null
        analyzeInput = repoName ?: login.orEmpty()

        viewModelScope.launch {
            try {
                if (repoName != null && cmpName != null) {
                    val (a, b) = withContext(Dispatchers.IO) {
                        Triple(GitHubApi.repo(repoName), GitHubApi.deep(repoName), GitHubApi.readme(repoName)) to
                            Triple(GitHubApi.repo(cmpName), GitHubApi.deep(cmpName), GitHubApi.readme(cmpName))
                    }
                    val sa = Scoring.score(a.first)
                    val sb = Scoring.score(b.first)
                    analyzeRepo = sa; analyzeDeep = a.second
                    compareRepo = sb; compareDeep = b.second
                    analyzeText = withContext(Dispatchers.IO) {
                        Reports.compare(sa, a.second, a.third, sb, b.second, b.third)
                    }
                } else if (repoName != null) {
                    val (repo, deep, readme) = withContext(Dispatchers.IO) {
                        Triple(GitHubApi.repo(repoName), GitHubApi.deep(repoName), GitHubApi.readme(repoName))
                    }
                    val scored = Scoring.score(repo)
                    analyzeRepo = scored; analyzeDeep = deep
                    analyzeText = withContext(Dispatchers.IO) { Reports.analyze(scored, deep, readme, learn) }
                } else if (login != null) {
                    val (dev, deep) = withContext(Dispatchers.IO) { GitHubApi.user(login) to GitHubApi.devDeep(login) }
                    analyzeDev = dev; analyzeDevDeep = deep
                    analyzeText = withContext(Dispatchers.IO) { Reports.dev(dev, deep) }
                }
            } catch (e: Exception) {
                analyzeError = friendly(e)
            } finally {
                analyzing = false
            }
        }
    }

    // ---------- أمان ----------
    fun loadAdvisories() {
        viewModelScope.launch {
            advLoading = true; advError = null; advSummary = null
            try {
                advisories = withContext(Dispatchers.IO) { GitHubApi.advisories(advEco, advSev) }
            } catch (e: Exception) {
                advError = friendly(e)
            } finally {
                advLoading = false
            }
        }
    }

    fun summarizeAdvisories() {
        val top = advisories.take(10)
        if (top.isEmpty()) return
        viewModelScope.launch {
            advLoading = true; advError = null
            try {
                advSummary = withContext(Dispatchers.IO) { Reports.advisories(top) }
            } catch (e: Exception) {
                advError = friendly(e)
            } finally {
                advLoading = false
            }
        }
    }

    // ---------- تابع ----------
    fun toggleFav(r: Repo) { favs = Store.toggleFav(r.fullName, r.description) }
    fun removeFav(name: String) { favs = Store.toggleFav(name, "") }

    fun loadReleases() {
        val names = favs.take(25).map { it.name }
        viewModelScope.launch {
            relLoading = true; relError = null
            try {
                releases = withContext(Dispatchers.IO) {
                    names.associateWith { runCatching { GitHubApi.latestRelease(it) }.getOrNull() }
                }
            } catch (e: Exception) {
                relError = friendly(e)
            } finally {
                relLoading = false
            }
        }
    }

    fun summarizeRelease(name: String) {
        val rel = releases[name] ?: return
        viewModelScope.launch {
            relLoading = true; relError = null
            try {
                relSummary = withContext(Dispatchers.IO) { Reports.release(name, rel) }
                relSummaryFor = name
            } catch (e: Exception) {
                relError = friendly(e)
            } finally {
                relLoading = false
            }
        }
    }
}
