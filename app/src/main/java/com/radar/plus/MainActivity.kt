package com.radar.plus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        handleShare(intent)
        setContent { RadarApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** مشاركة رابط مستودع من المتصفح إلى التطبيق تملأ حقل التحليل. */
    private fun handleShare(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let {
                vm.analyzeInput = it
                vm.tab = 2
            }
        }
    }
}
