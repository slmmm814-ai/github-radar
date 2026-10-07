package com.radar.plus

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** تقييم 0-100: سرعة النمو + النشاط + الصحة + إشارات التحذير. */
object Scoring {
    fun score(r: Repo, now: Long = System.currentTimeMillis()): Repo {
        val reasons = mutableListOf<String>()
        var s = 0.0

        val ageDays = max(1.0, (now - r.createdAt) / 86_400_000.0)
        val velocity = if (r.trendStars > 0 && r.trendDays > 0) r.trendStars.toDouble() / r.trendDays else r.stars / ageDays
        s += min(40.0, 10 * ln(1 + velocity))
        if (r.trendStars > 0) {
            val period = when (r.trendDays) { 1 -> "اليوم"; 7 -> "هذا الأسبوع"; else -> "هذا الشهر" }
            reasons += "Trending: +${r.trendStars} نجمة $period"
        } else if (velocity >= 10) reasons += "نمو سريع: " + "%.0f".format(Locale.US, velocity) + " نجمة/يوم"
        else if (velocity >= 2) reasons += "نمو جيد: " + "%.1f".format(Locale.US, velocity) + " نجمة/يوم"

        if (r.note.isNotBlank()) reasons += r.note

        if (r.growth > 0) {
            s += min(10.0, 3 * ln(1.0 + r.growth))
            reasons += "+${r.growth} نجمة منذ آخر فحص (${r.growthHours} ساعة)"
        }

        val pushedDays = (now - r.pushedAt) / 86_400_000.0
        if (pushedDays <= 3) { s += 15; reasons += "نشط: تحديث قبل أيام قليلة" }
        else if (pushedDays <= 14) s += 8
        else if (pushedDays > 90) { s -= 10; reasons += "تنبيه: لم يُحدَّث منذ أكثر من 3 أشهر" }

        if (r.archived) { s -= 25; reasons += "تنبيه: المستودع مؤرشف (متوقف)" }

        if (r.license != null) { s += 10; reasons += "الترخيص: ${r.license}" }
        else reasons += "تنبيه: لا يوجد ترخيص واضح"

        if (r.description.isNotBlank()) s += 5
        if (r.topics.size >= 3) s += 5

        val forkRatio = if (r.stars > 0) r.forks.toDouble() / r.stars else 0.0
        if (forkRatio in 0.05..0.5) s += 10
        if (r.stars >= 200 && forkRatio < 0.02) {
            s -= 10
            reasons += "تنبيه: نجوم كثيرة مقابل تفرّعات قليلة (قد يكون الاهتمام مصطنعًا)"
        }
        if (r.openIssues > 0) s += 5

        if (r.buzz > 0) {
            s += if (r.buzz >= 50) 10 else 5
            reasons += "نقاش في: " + r.buzzFrom.joinToString("، ") + " (${r.buzz})"
        }

        return r.copy(score = s.roundToInt().coerceIn(0, 100), reasons = reasons)
    }
}
