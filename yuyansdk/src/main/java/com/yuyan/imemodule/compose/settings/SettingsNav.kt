package com.yuyan.imemodule.compose.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.screens.AboutScreen
import com.yuyan.imemodule.compose.settings.screens.ClipboardSettingsScreen
import com.yuyan.imemodule.compose.settings.screens.FeedbackSettingsScreen
import com.yuyan.imemodule.compose.settings.screens.FullDisplayKeyboardScreen
import com.yuyan.imemodule.compose.settings.screens.HandwritingSettingsScreen
import com.yuyan.imemodule.compose.settings.screens.HomeScreen
import com.yuyan.imemodule.compose.settings.screens.InputSettingsScreen
import com.yuyan.imemodule.compose.settings.screens.KeyboardSettingsScreen
import com.yuyan.imemodule.compose.settings.screens.OtherSettingsScreen
import com.yuyan.imemodule.compose.settings.screens.PrivacyPolicyScreen
import com.yuyan.imemodule.compose.settings.screens.SidebarSymbolScreen
import com.yuyan.imemodule.compose.settings.screens.ThemeScreen
import com.yuyan.imemodule.compose.settings.screens.UserDictScreen
import com.yuyan.imemodule.compose.settings.screens.VoiceSettingsScreen

/**
 * 设置导航目的地（对应旧 `settings_nav.xml` 的 13 个 destination）。
 *
 * 键盘端的深链不再用 `NavDeepLinkBuilder`（nav-compose 不兼容该 API），
 * 改为显式 intent extra，见 [EXTRA_DEST] 与 `AppUtil.launchSettingsToXxx`。
 */
enum class SettingsDest(val route: String, @StringRes val title: Int) {
    Home("home", R.string.input_methods),
    Input("input", R.string.setting_ime_input),
    Handwriting("handwriting", R.string.ime_settings_handwriting),
    Voice("voice", R.string.ime_settings_voice),
    UserDict("userDict", R.string.user_dict),
    Theme("theme", R.string.theme),
    Feedback("feedback", R.string.keyboard_feedback),
    Keyboard("keyboard", R.string.setting_ime_keyboard),
    Clipboard("clipboard", R.string.clipboard),
    FullDisplay("fullDisplay", R.string.full_display_keyboard),
    Other("other", R.string.setting_ime_other),
    About("about", R.string.about),
    Privacy("privacy", R.string.privacy_policy),
    Sidebar("sidebar", R.string.setting_ime_prefixs);

    companion object {
        /** 键盘端跳转用的 intent extra key */
        const val EXTRA_DEST = "com.yuyan.imemodule.extra.SETTINGS_DEST"

        fun fromRoute(route: String?): SettingsDest? = entries.find { it.route == route }
    }
}

/**
 * 设置导航图。
 *
 * [startRoute] 由 Activity 决定：首次启动未同意隐私政策时直接从 [SettingsDest.Privacy] 起步，
 * 键盘端深链则直接落到目标页（栈里只有该页，与旧 `NavDeepLinkBuilder` 语义一致）。
 */
@Composable
fun SettingsNavHost(
    startRoute: String,
    onExit: () -> Unit,
) {
    val navController = rememberNavController()
    // 返回：能回退就回退，否则结束 Activity（等价旧 navigateUp() || moveTaskToBack()）
    val back: () -> Unit = {
        if (!navController.popBackStack()) onExit()
    }
    val go: (SettingsDest) -> Unit = { dest ->
        navController.navigateTo(dest)
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable(SettingsDest.Home.route) {
            HomeScreen(onNavigate = go, onExit = onExit)
        }
        composable(SettingsDest.Input.route) {
            InputSettingsScreen(onBack = back, onUserDict = { go(SettingsDest.UserDict) })
        }
        composable(SettingsDest.Handwriting.route) {
            HandwritingSettingsScreen(onBack = back)
        }
        composable(SettingsDest.Voice.route) {
            VoiceSettingsScreen(onBack = back)
        }
        composable(SettingsDest.UserDict.route) {
            UserDictScreen(onBack = back)
        }
        composable(SettingsDest.Theme.route) {
            ThemeScreen(onBack = back)
        }
        composable(SettingsDest.Feedback.route) {
            FeedbackSettingsScreen(onBack = back)
        }
        composable(SettingsDest.Keyboard.route) {
            KeyboardSettingsScreen(onBack = back)
        }
        composable(SettingsDest.Clipboard.route) {
            ClipboardSettingsScreen(onBack = back)
        }
        composable(SettingsDest.FullDisplay.route) {
            FullDisplayKeyboardScreen(onBack = back)
        }
        composable(SettingsDest.Other.route) {
            OtherSettingsScreen(onBack = back)
        }
        composable(SettingsDest.About.route) {
            AboutScreen(onBack = back, onPrivacy = { go(SettingsDest.Privacy) })
        }
        composable(SettingsDest.Privacy.route) {
            // 首次启动时隐私政策是根页面，不显示返回箭头（旧 AppBarConfiguration 的 topLevel 语义）；
            // 同意后清掉隐私页回到首页，取消则结束整个设置页（与旧实现一致）。
            val isRoot = startRoute == SettingsDest.Privacy.route
            PrivacyPolicyScreen(
                onBack = if (isRoot) null else back,
                onAccepted = if (isRoot) {
                    {
                        navController.navigate(SettingsDest.Home.route) {
                            popUpTo(SettingsDest.Privacy.route) { inclusive = true }
                        }
                    }
                } else null,
            )
        }
        composable(SettingsDest.Sidebar.route) {
            SidebarSymbolScreen(onBack = back)
        }
    }
}

private fun NavHostController.navigateTo(dest: SettingsDest) {
    navigate(dest.route) { launchSingleTop = true }
}
