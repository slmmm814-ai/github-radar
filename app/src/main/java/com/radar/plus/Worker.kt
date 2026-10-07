package com.radar.plus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class RadarWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Store.init(applicationContext)
        try {
            if (Store.notify) notifyDiscoveries()
            if (Store.notifyReleases) notifyReleases()
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.success()
        }
    }

    private fun notifyDiscoveries() {
        val seen = Store.seen
        val fresh = Discovery.discover(Store.domains)
            .filter { it.fullName !in seen && it.score >= 55 }
            .take(5)
        if (fresh.isEmpty()) return
        val digest = if (Store.claudeKey.isNotBlank()) runCatching { Reports.digest(fresh) }.getOrNull() else null
        val body = digest ?: fresh.joinToString("\n") { "• ${it.fullName} (${it.score}/100)" }
        Notifier.show(applicationContext, 1, "رادار GitHub+: ${fresh.size} مشاريع تستحق النظر", body)
        Store.seen = seen + fresh.map { it.fullName }
    }

    private fun notifyReleases() {
        val news = mutableListOf<String>()
        for (f in Store.favorites().take(20)) {
            val rel = runCatching { GitHubApi.latestRelease(f.name) }.getOrNull() ?: continue
            val last = Store.releaseSeen(f.name)
            if (last != rel.tag) {
                Store.markRelease(f.name, rel.tag)
                if (last != null) news += "• ${f.name} ← ${rel.tag}"
            }
        }
        if (news.isNotEmpty()) {
            Notifier.show(applicationContext, 2, "إصدارات جديدة في مفضلتك", news.joinToString("\n"))
        }
    }
}

object Notifier {
    private const val CHANNEL = "radar"

    fun show(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "تنبيهات الرادار", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

object Scheduler {
    fun schedule(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (on) {
            val req = PeriodicWorkRequestBuilder<RadarWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork("radar", ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork("radar")
        }
    }
}
