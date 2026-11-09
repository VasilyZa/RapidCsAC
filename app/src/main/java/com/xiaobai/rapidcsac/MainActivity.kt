package com.xiaobai.rapidcsac

import android.app.KeyguardManager
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import com.xiaobai.rapidcsac.ui.RapidApp

class MainActivity : ComponentActivity() {
    private val graph
        get() = (application as RapidApplication).graph

    private var systemMotionScale by mutableFloatStateOf(1f)
    private val scaleObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                readMotionScale()
            }
        }

    private fun readMotionScale() {
        systemMotionScale =
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private var unlocked by mutableStateOf(false)
    private var pendingLink by mutableStateOf<Intent?>(null)
    private val unlock =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) unlocked = true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingLink = intent
        readMotionScale()
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            scaleObserver,
        )
        val motionScale =
            object : MotionDurationScale {
                override val scaleFactor: Float
                    get() = if (graph.reducedMotion.value) 0f else systemMotionScale
            }
        val recomposer =
            window.decorView.createLifecycleAwareWindowRecomposer(motionScale, lifecycle)
        setContent(parent = recomposer) {
            RapidApp(graph, unlocked, ::unlockApplication, pendingLink, { pendingLink = null })
        }
    }

    override fun onStart() {
        super.onStart()
        if (graph.backgroundAt > 0 && System.currentTimeMillis() - graph.backgroundAt > 30_000)
            unlocked = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink = intent
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(scaleObserver)
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // Device credentials are available across the supported API 24+ range.
    private fun unlockApplication() {
        val manager = getSystemService(KeyguardManager::class.java)
        manager
            .createConfirmDeviceCredentialIntent("解锁 RapidCsAC", "验证设备凭据以查看聊天")
            ?.let(unlock::launch)
    }
}
