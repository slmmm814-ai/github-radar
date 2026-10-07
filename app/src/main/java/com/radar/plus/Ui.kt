package com.radar.plus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Locale

private fun fmtNum(n: Int) =
    if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000.0) else n.toString()

private fun openUrl(ctx: Context, url: String) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(i, null))
}

@Composable
private fun Title(t: String) =
    Text(t, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun ErrorLine(e: String?) {
    if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun TextCard(text: String) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer { Text(text) }
            TextButton(onClick = { shareText(ctx, text) }) { Text("مشاركة") }
        }
    }
}

@Composable
fun RadarApp(vm: AppViewModel) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                BackHandler(enabled = vm.tab == 5) { vm.tab = 0 }
                val tabs = listOf(
                    "اكتشف" to Icons.Filled.Star,
                    "ابحث" to Icons.Filled.Search,
                    "حلّل" to Icons.Filled.Info,
                    "أمان" to Icons.Filled.Lock,
                    "تابع" to Icons.Filled.Favorite
                )
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = vm.tab == i,
                                    onClick = { vm.tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (vm.tab) {
                            0 -> DiscoverScreen(vm)
                            1 -> SearchScreen(vm)
                            2 -> AnalyzeScreen(vm)
                            3 -> SecurityScreen(vm)
                            4 -> WatchScreen(vm)
                            else -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

// ======================= مكوّنات مشتركة =======================

@Composable
fun RepoCard(r: Repo, vm: AppViewModel, showExplain: Boolean = true) {
    val ctx = LocalContext.current
    val fav = vm.favs.any { it.name == r.fullName }
    val scoreColor = when {
        r.score >= 70 -> Color(0xFF2E7D32)
        r.score >= 50 -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(r.fullName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${r.score}/100", color = scoreColor, fontWeight = FontWeight.Bold)
            }
            if (r.description.isNotBlank()) Text(r.description, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
            Text(
                "⭐ ${fmtNum(r.stars)}  ·  🍴 ${fmtNum(r.forks)}  ·  ${r.language ?: "—"}",
                style = MaterialTheme.typography.bodySmall
            )
            r.reasons.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showExplain) TextButton(onClick = { vm.analyze(r.fullName) }) { Text("اشرح بالعربية") }
                TextButton(onClick = { openUrl(ctx, r.url) }) { Text("فتح") }
                TextButton(onClick = { vm.toggleFav(r) }) { Text(if (fav) "إزالة" else "حفظ") }
            }
        }
    }
}

@Composable
private fun DeepCard(d: RepoDeep) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("حقائق إضافية", style = MaterialTheme.typography.titleSmall)
            if (d.languages.isNotEmpty()) {
                Text("اللغات: " + d.languages.joinToString("، ") { "${it.first} ${it.second}%" }, style = MaterialTheme.typography.bodySmall)
            }
            if (d.contributors.isNotEmpty()) {
                val total = d.contributors.sumOf { it.second }.coerceAtLeast(1)
                val top = d.contributors.first()
                Text("أبرز المساهمين: " + d.contributors.joinToString("، ") { "${it.first} (${it.second})" }, style = MaterialTheme.typography.bodySmall)
                Text("أكبر مساهم ≈ ${top.second * 100 / total}% من مساهمات الخمسة الأوائل", style = MaterialTheme.typography.bodySmall)
            }
            d.commits4w?.let { Text("التزامات آخر 4 أسابيع: $it", style = MaterialTheme.typography.bodySmall) }
            d.release?.let { Text("آخر إصدار: ${it.tag} (${fmtDate(it.publishedAt)})", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DevCard(d: Dev, vm: AppViewModel?) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                d.login + if (d.name.isNotBlank()) " — ${d.name}" else "",
                style = MaterialTheme.typography.titleMedium
            )
            Text(if (d.type == "Organization") "منظمة" else "مطوّر", style = MaterialTheme.typography.bodySmall)
            if (d.bio.isNotBlank()) Text(d.bio, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
            if (d.followers > 0 || d.publicRepos > 0) {
                Text("المتابعون: ${fmtNum(d.followers)}  ·  المستودعات: ${d.publicRepos}", style = MaterialTheme.typography.bodySmall)
            }
            val extra = listOf(d.company, d.location).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (vm != null) TextButton(onClick = { vm.analyze(d.login) }) { Text("حلّل") }
                if (d.url.isNotBlank()) TextButton(onClick = { openUrl(ctx, d.url) }) { Text("فتح") }
            }
        }
    }
}

// ======================= اكتشف =======================

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { if (vm.repos.isEmpty() && !vm.loading) vm.refresh() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Title("رادار GitHub+")
                TextButton(onClick = { vm.tab = 5 }) { Text("الإعدادات") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Domain.values().toList()) { d ->
                    FilterChip(selected = d in vm.activeDomains, onClick = { vm.toggleDomain(d) }, label = { Text(d.label) })
                }
            }
        }
        item {
            Text(
                "المصادر: " + Store.sources.joinToString("، ") { it.label },
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refresh() }, enabled = !vm.loading) { Text("حدّث") }
                OutlinedButton(
                    onClick = { vm.makeDigest() },
                    enabled = vm.repos.isNotEmpty() && !vm.digestLoading
                ) { Text("تقرير اليوم") }
            }
        }
        if (vm.loading || vm.digestLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { ErrorLine(vm.error) }
        vm.digest?.let { d -> item { TextCard(d) } }
        items(vm.repos, key = { it.fullName }) { RepoCard(it, vm) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ======================= ابحث =======================

@Composable
fun SearchScreen(vm: AppViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("بحث في GitHub") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.searchMode == 0, onClick = { vm.searchMode = 0 }, label = { Text("مستودعات") })
                FilterChip(selected = vm.searchMode == 1, onClick = { vm.searchMode = 1 }, label = { Text("مطورون ومنظمات") })
            }
        }
        item {
            OutlinedTextField(
                value = vm.searchQuery, onValueChange = { vm.searchQuery = it },
                label = { Text(if (vm.searchMode == 0) "كلمات البحث (بالإنجليزية أفضل)" else "اسم أو كلمة") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        if (vm.searchMode == 0) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.searchLang, onValueChange = { vm.searchLang = it },
                        label = { Text("اللغة") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = vm.searchMin, onValueChange = { vm.searchMin = it },
                        label = { Text("أقل نجوم") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !vm.searchSortUpdated, onClick = { vm.searchSortUpdated = false }, label = { Text("الأكثر نجومًا") })
                    FilterChip(selected = vm.searchSortUpdated, onClick = { vm.searchSortUpdated = true }, label = { Text("الأحدث تحديثًا") })
                }
            }
        }
        item {
            Button(onClick = { vm.search() }, enabled = !vm.searching, modifier = Modifier.fillMaxWidth()) { Text("ابحث") }
        }
        if (vm.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { ErrorLine(vm.searchError) }
        if (vm.searchMode == 0) {
            items(vm.searchRepos, key = { "r" + it.fullName }) { RepoCard(it, vm) }
        } else {
            items(vm.searchDevs, key = { "d" + it.login }) { DevCard(it, vm) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ======================= حلّل =======================

@Composable
fun AnalyzeScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Title("تحليل مستودع أو مطوّر")
        OutlinedTextField(
            value = vm.analyzeInput, onValueChange = { vm.analyzeInput = it },
            label = { Text("رابط، owner/repo، أو اسم مطوّر/منظمة") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.compareInput, onValueChange = { vm.compareInput = it },
            label = { Text("قارن مع مستودع آخر (اختياري)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.learnMode, onCheckedChange = { vm.learnMode = it })
            Spacer(Modifier.width(8.dp))
            Text(if (vm.learnMode) "وضع التعلّم (شرح لمبتدئ)" else "وضع التحليل (هل أستخدمه؟)")
        }
        Button(onClick = { vm.analyze() }, enabled = !vm.analyzing, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.compareInput.isNotBlank()) "قارن بالعربية" else "حلّل بالعربية")
        }
        if (vm.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorLine(vm.analyzeError)

        vm.analyzeRepo?.let { RepoCard(it, vm, showExplain = false) }
        vm.analyzeDeep?.let { DeepCard(it) }
        vm.compareRepo?.let { RepoCard(it, vm, showExplain = false) }
        vm.compareDeep?.let { DeepCard(it) }

        vm.analyzeDev?.let { dev ->
            DevCard(dev, null)
            vm.analyzeDevDeep?.let { dd ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("إجمالي النجوم: ${fmtNum(dd.totalStars)}", style = MaterialTheme.typography.bodyMedium)
                        if (dd.languages.isNotEmpty()) {
                            Text("اللغات: " + dd.languages.joinToString("، ") { "${it.first} ${it.second}%" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("أبرز المستودعات:", style = MaterialTheme.typography.titleSmall)
                        dd.topRepos.forEach { Text("• ${it.fullName}  ⭐ ${fmtNum(it.stars)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        vm.analyzeText?.let { TextCard(it) }
        Spacer(Modifier.height(12.dp))
    }
}

// ======================= أمان =======================

private val ecosystems = listOf(
    "" to "الكل", "npm" to "npm", "pip" to "Python", "maven" to "Java", "go" to "Go",
    "rust" to "Rust", "composer" to "PHP", "nuget" to ".NET", "rubygems" to "Ruby",
    "pub" to "Dart", "actions" to "Actions"
)
private val severities = listOf("critical" to "حرجة", "high" to "عالية", "medium" to "متوسطة", "" to "الكل")

@Composable
fun SecurityScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (vm.advisories.isEmpty() && !vm.advLoading) vm.loadAdvisories() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("الثغرات الأمنية (GitHub Advisories)") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(severities) { (v, label) ->
                    FilterChip(selected = vm.advSev == v, onClick = { vm.advSev = v; vm.loadAdvisories() }, label = { Text(label) })
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ecosystems) { (v, label) ->
                    FilterChip(selected = vm.advEco == v, onClick = { vm.advEco = v; vm.loadAdvisories() }, label = { Text(label) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.loadAdvisories() }, enabled = !vm.advLoading) { Text("حدّث") }
                OutlinedButton(
                    onClick = { vm.summarizeAdvisories() },
                    enabled = vm.advisories.isNotEmpty() && !vm.advLoading
                ) { Text("لخّص بالعربية") }
            }
        }
        if (vm.advLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { ErrorLine(vm.advError) }
        vm.advSummary?.let { s -> item { TextCard(s) } }
        items(vm.advisories, key = { it.id }) { a ->
            val c = when (a.severity) {
                "critical" -> Color(0xFFC62828)
                "high" -> Color(0xFFEF6C00)
                else -> Color(0xFFF9A825)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${a.severity.uppercase()}  ${a.cve.ifBlank { a.id }}", color = c, fontWeight = FontWeight.Bold)
                    Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                    if (a.packages.isNotEmpty()) Text("الحزم: " + a.packages.joinToString("، "), style = MaterialTheme.typography.bodySmall)
                    if (a.patched.isNotBlank()) Text("النسخة المصحَّحة: ${a.patched}", style = MaterialTheme.typography.bodySmall)
                    Text("نُشرت: ${fmtDate(a.published)}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openUrl(ctx, a.url) }) { Text("فتح") }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ======================= تابع =======================

@Composable
fun WatchScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.favs.size) { if (vm.favs.isNotEmpty()) vm.loadReleases() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("المفضلة والإصدارات") }
        if (vm.favs.isEmpty()) item { Text("احفظ مستودعات من تبويب اكتشف أو ابحث لمتابعة إصداراتها هنا.") }
        if (vm.relLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { ErrorLine(vm.relError) }
        items(vm.favs, key = { it.name }) { f ->
            val rel = vm.releases[f.name]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    if (f.desc.isNotBlank()) Text(f.desc, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    if (rel != null) {
                        val fresh = System.currentTimeMillis() - rel.publishedAt < 7 * 86_400_000L
                        Text(
                            "آخر إصدار: ${rel.tag} (${fmtDate(rel.publishedAt)})" + if (fresh) "  🆕" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (vm.relSummaryFor == f.name) vm.relSummary?.let { TextCard(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.analyze(f.name) }) { Text("حلّل") }
                        if (rel != null) TextButton(onClick = { vm.summarizeRelease(f.name) }) { Text("لخّص الإصدار") }
                        TextButton(onClick = { openUrl(ctx, "https://github.com/${f.name}") }) { Text("فتح") }
                        TextButton(onClick = { vm.removeFav(f.name) }) { Text("حذف") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ======================= الإعدادات =======================

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var gh by remember { mutableStateOf(Store.ghToken) }
    var key by remember { mutableStateOf(Store.claudeKey) }
    var model by remember { mutableStateOf(Store.model) }
    var notify by remember { mutableStateOf(Store.notify) }
    var notifyRel by remember { mutableStateOf(Store.notifyReleases) }
    var doms by remember { mutableStateOf(Store.domains) }
    var srcs by remember { mutableStateOf(Store.sources) }
    var tsince by remember { mutableStateOf(Store.trendSince) }
    var tlang by remember { mutableStateOf(Store.trendLang) }
    var srvUrl by remember { mutableStateOf(Store.serverUrl) }
    var srvKey by remember { mutableStateOf(Store.serverKey) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun askPermission() {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Title("الإعدادات")
            TextButton(onClick = { vm.tab = 0 }) { Text("رجوع") }
        }
        OutlinedTextField(
            value = gh, onValueChange = { gh = it },
            label = { Text("توكن GitHub (موصى به: يرفع الحد إلى 5000 طلب/ساعة)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("مفتاح Claude API (للشرح والتقارير العربية)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("اسم النموذج") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Text("مصادر الاكتشاف", style = MaterialTheme.typography.titleMedium)
        Src.values().forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    val n = if (s in srcs) srcs - s else srcs + s
                    if (n.isNotEmpty()) srcs = n
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = s in srcs, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(s.label)
            }
        }
        Text("فترة Trending", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("daily" to "يومي", "weekly" to "أسبوعي", "monthly" to "شهري").forEach { (v, l) ->
                FilterChip(selected = tsince == v, onClick = { tsince = v }, label = { Text(l) })
            }
        }
        OutlinedTextField(
            value = tlang, onValueChange = { tlang = it },
            label = { Text("لغة Trending (اختياري، مثل kotlin أو python)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text("خادم الرادار (اختياري)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = srvUrl, onValueChange = { srvUrl = it },
            label = { Text("عنوان الخادم أو رابط trending.json") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = srvKey, onValueChange = { srvKey = it },
            label = { Text("مفتاح الخادم (RADAR_KEY)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Text("المجالات المتابَعة", style = MaterialTheme.typography.titleMedium)
        Domain.values().forEach { d ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    val n = if (d in doms) doms - d else doms + d
                    if (n.isNotEmpty()) doms = n
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = d in doms, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(d.label)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = notify, onCheckedChange = { notify = it; if (it) askPermission() })
            Spacer(Modifier.width(8.dp))
            Text("تنبيه يومي بالجديد في مجالاتي")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = notifyRel, onCheckedChange = { notifyRel = it; if (it) askPermission() })
            Spacer(Modifier.width(8.dp))
            Text("تنبيه بإصدارات مستودعات المفضلة")
        }

        Button(onClick = {
            Store.ghToken = gh; Store.claudeKey = key; Store.model = model
            Store.notify = notify; Store.notifyReleases = notifyRel
            Store.domains = doms; Store.sources = srcs
            Store.trendSince = tsince; Store.trendLang = tlang
            Store.serverUrl = srvUrl; Store.serverKey = srvKey
            vm.activeDomains = doms
            Scheduler.schedule(ctx, notify || notifyRel)
            Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("حفظ") }

        OutlinedButton(onClick = {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<RadarWorker>().build())
            Toast.makeText(ctx, "بدأ الفحص (احفظ الإعدادات وفعّل التنبيه أولًا)", Toast.LENGTH_LONG).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("جرّب التنبيه الآن") }

        OutlinedButton(onClick = {
            Store.clearHistory()
            Toast.makeText(ctx, "تم مسح السجل (المشاهَد واللقطات)", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("مسح سجل الاكتشاف") }

        Text(
            "المفاتيح تُحفظ على جهازك فقط وتُرسل إلى GitHub وAnthropic مباشرة.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
    }
}
