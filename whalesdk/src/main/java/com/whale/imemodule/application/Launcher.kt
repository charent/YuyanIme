package com.whale.imemodule.application

import android.annotation.SuppressLint
import android.content.Context
import com.whale.imemodule.data.emojicon.WhaleEmojiCompat
import com.whale.imemodule.data.theme.ThemeManager
import com.whale.imemodule.database.DataBaseKT
import com.whale.imemodule.manager.UserDictManager
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.service.ClipboardHelper
import com.whale.imemodule.service.InputDispatcher
import com.whale.imemodule.utils.AssetUtils.copyFileOrDir
import com.whale.imemodule.utils.defaultSharedPreferences
import com.whale.imemodule.utils.thread.ThreadPoolUtils
import com.whale.inputmethod.correction.EngineLexicon

class Launcher {
    lateinit var context: Context
        private set

    fun initData(context: Context) {
        this.context = context
        currentInit()
        onInitDataChildThread()
    }

    private fun currentInit() {
        AppPrefs.init(context.defaultSharedPreferences())
        ThemeManager.init(context.resources.configuration)
        DataBaseKT.instance.sideSymbolDao().getAllSideSymbolPinyin()  //操作一次查询，提前创建数据库，避免使用时才创建数据库
        ClipboardHelper.init()
    }

    /**
     * 可以在子线程初始化的操作
     */
    private fun onInitDataChildThread() {
        ThreadPoolUtils.executeSingleton {
            // 复制词库文件
            val dataDictVersion = AppPrefs.getInstance().internal.dataDictVersion.getValue()
            if (dataDictVersion < CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM) {
                //rime词库
                copyFileOrDir(context, "rime", "", CustomConstant.RIME_DICT_PATH, true)
                copyFileOrDir(context, "hw", "", CustomConstant.HW_DICT_PATH, true)
                AppPrefs.getInstance().internal.dataDictVersion.setValue(CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM)
            }
            InputDispatcher.resetIme()  // 解决词库复制慢，导致先调用初始化问题
            UserDictManager.load()      // 用户词库载入内存索引，供候选注入高频查询
            // 纠错直接读引擎自己的词库（必须放在上面的词库复制之后）：
            // 不新增任何资源，判定依据与引擎出候选完全一致
            EngineLexicon.prepare(CustomConstant.RIME_DICT_PATH + "/build/pinyin.table.bin")
            WhaleEmojiCompat.init(context)
            // 深色模式跟随系统是平台默认行为：界面全部是 Compose + values-night，
            // 旧框架的 setDefaultNightMode 调用在没有对应 Activity 基类后本就是空操作。
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
