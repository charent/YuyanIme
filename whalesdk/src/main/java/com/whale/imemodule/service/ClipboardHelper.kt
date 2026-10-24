package com.whale.imemodule.service

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import com.whale.imemodule.application.Launcher
import com.whale.imemodule.database.DataBaseKT
import com.whale.imemodule.database.entry.Clipboard
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.prefs.CLIPBOARD_HISTORY_LIMIT_MAX
import com.whale.imemodule.prefs.CLIPBOARD_HISTORY_LIMIT_MIN
import com.whale.imemodule.utils.clipboardManager
import kotlin.math.max

/**
 * 剪切板监听
 * 移除使用广播监听方式，解决部分手机后台无法启动监听服务异常(API level 31)。
 */
object ClipboardHelper : OnPrimaryClipChangedListener {

    /** 单条剪贴板正文的保存上限（与数据库 TEXT 字段的常规上限一致） */
    const val MAX_CONTENT_LENGTH = 65535

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    /**
     * 按字符数截断正文；截断点落在代理对中间时回退一位，避免存进半个 emoji
     */
    private fun String.takeContent(length: Int): String {
        if (this.length <= length) return this
        val end = if (this[length - 1].isHighSurrogate()) length - 1 else length
        return substring(0, end)
    }

    override fun onPrimaryClipChanged() {
        val isClipboardListening = AppPrefs.getInstance().clipboard.clipboardListening.getValue()
        if(isClipboardListening) {
           val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0)
            item?.takeIf { it.text?.isNotBlank() == true }?.let {
                    val data = it.text.toString().takeContent(MAX_CONTENT_LENGTH)
                    DataBaseKT.instance.clipboardDao().insert(Clipboard(content = data))
                    // 兼容旧版本遗留的低值配置，保证与设置项的滑动范围一致
                    val limit = AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue()
                        .coerceIn(CLIPBOARD_HISTORY_LIMIT_MIN, CLIPBOARD_HISTORY_LIMIT_MAX)
                    val num = max(DataBaseKT.instance.clipboardDao().getCount() - limit, 0)
                    DataBaseKT.instance.clipboardDao().deleteOldest(num)
                    if (AppPrefs.getInstance().clipboard.clipboardSuggestion.getValue()) {
                        AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(System.currentTimeMillis())
                        AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(data)
                    }
                }
        }
    }
}
