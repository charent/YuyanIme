package com.whale.imemodule.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.whale.imemodule.R
import com.whale.imemodule.application.Launcher
import com.whale.imemodule.compose.settings.SettingsDest
import com.whale.imemodule.ui.activity.SettingsActivity
import kotlin.system.exitProcess

object AppUtil {

    fun launchSettings(context: Context) {
        context.startActivity<SettingsActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    /**
     * 跳到设置里的某个页面。
     *
     * Phase 4 起设置改用 `navigation-compose`，`NavDeepLinkBuilder` 与之不兼容，
     * 因此改成显式 intent extra：`SettingsActivity` 读取 [SettingsDest.EXTRA_DEST] 作为首跳目的地。
     */
    private fun launchMainToDest(context: Context, dest: SettingsDest) {
        context.startActivity(
            Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(SettingsDest.EXTRA_DEST, dest.route)
            }
        )
    }

    fun launchSettingsToHandwriting(context: Context) =
        launchMainToDest(context, SettingsDest.Handwriting)

    fun launchSettingsToKeyboard(context: Context) =
        launchMainToDest(context, SettingsDest.Feedback)

    /** `arguments` 仅为兼容旧调用点（侧边栏 tab 由页面内 TabRow 决定，与旧实现行为一致） */
    fun launchSettingsToPrefix(context: Context, arguments: Bundle? = null) =
        launchMainToDest(context, SettingsDest.Sidebar)

    fun launchMarketforWhale(context: Context){
        val packageName = Launcher.instance.context.packageName
        try {
            val uri = Uri.parse("market://details?id=$packageName")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // 如果设备没有安装应用市场，使用Google Play的网页版链接
            val webMarketUrl = "https://play.google.com/store/apps/details?id=$packageName"
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webMarketUrl))
            context.startActivity(webIntent)
        }
    }

    fun exit() {
        exitProcess(0)
    }

    fun showRestartNotification(ctx: Context) {
        val channelId = "app-restart"
        val channel = NotificationChannel(channelId, ctx.getText(R.string.restart_channel), NotificationManager.IMPORTANCE_HIGH).apply { description = channelId }
        ctx.notificationManager.createNotificationChannel(channel)
        NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_sdk_launcher_transparent)
            .setContentTitle(ctx.getText(R.string.app_name))
            .setContentText(ctx.getText(R.string.restart_notify_msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
            .let { ctx.notificationManager.notify(0xdead, it) }
    }
}