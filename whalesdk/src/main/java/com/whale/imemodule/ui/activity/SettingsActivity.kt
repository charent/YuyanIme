package com.whale.imemodule.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.whale.imemodule.compose.settings.SettingsDest
import com.whale.imemodule.compose.settings.SettingsNavHost
import com.whale.imemodule.compose.settings.SettingsTheme
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.ui.setup.SetupActivity
import com.whale.imemodule.utils.startActivity

/**
 * 设置宿主（Phase 4 起为 Compose）。
 *
 * - 导航：`navigation-compose`，路由见 [SettingsDest]；
 * - 深链：键盘端通过 intent extra [SettingsDest.EXTRA_DEST] 指定首跳目的地；
 * - 首次启动未同意隐私政策时以隐私页为起点，且不显示返回箭头。
 *
 * 设备加密存储的同步挂在这里的 `onStop`（等价旧 `ManagedPreferenceFragment.onStop`），
 * 因为 Fragment 层已删除，同步时机改为整个设置会话结束时。
 */
open class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val privacySure = AppPrefs.getInstance().internal.privacyPolicySure.getValue()
        val deepLink = SettingsDest.fromRoute(intent?.getStringExtra(SettingsDest.EXTRA_DEST))
        val startRoute = when {
            !privacySure -> SettingsDest.Privacy.route
            deepLink != null -> deepLink.route
            else -> SettingsDest.Home.route
        }

        setContent {
            SettingsTheme {
                SettingsNavHost(
                    startRoute = startRoute,
                    onExit = { finish() },
                )
            }
        }

        // 已同意隐私政策且引导未完成 → 进入引导页（旧实现同样在 onCreate 末尾触发）
        if (privacySure && SetupActivity.shouldShowUp() && savedInstanceState == null) {
            startActivity<SetupActivity>()
        }
    }

    override fun onStop() {
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }
}

class LauncherActivity : SettingsActivity()
