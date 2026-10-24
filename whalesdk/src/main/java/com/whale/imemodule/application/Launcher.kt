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
import java.io.File

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
            prepareRimeData()
            InputDispatcher.resetIme()  // 解决词库复制慢，导致先调用初始化问题
            UserDictManager.load()      // 用户词库载入内存索引，供候选注入高频查询
            // 纠错直接读引擎自己的词库（必须放在上面的词库复制之后）：
            // 不新增任何资源，判定依据与引擎出候选完全一致
            EngineLexicon.prepare(CustomConstant.RIME_SHARED_PATH + "/build/pinyin.table.bin")
            WhaleEmojiCompat.init(context)
            // 深色模式跟随系统是平台默认行为：界面全部是 Compose + values-night，
            // 旧框架的 setDefaultNightMode 调用在没有对应 Activity 基类后本就是空操作。
        }
    }

    /**
     * 共享词库按版本落到 `rime-v<N>`（只读、内容不变），用户可写数据在独立的 `rime-user`：
     * - 平时启动：两个目录都齐备，什么都不做，librime 只扫小体量的 user 目录，不再卡顿。
     * - 升级词库：拷贝到新版本目录后删除旧版本目录，一次完成。
     * - 旧版升级（shared/user 混在 `rime/`）：先迁出 userdb / 自定义 yaml 再拷贝。
     */
    private fun prepareRimeData() {
        val sharedDir = File(CustomConstant.RIME_SHARED_PATH)
        val userDir = File(CustomConstant.RIME_USER_PATH)
        val legacyDir = File(CustomConstant.RIME_LEGACY_PATH)
        // .copied 标记：版本号未变但目录被系统清理等异常情况下的兜底
        val copied = File(sharedDir, ".copied").exists()
        if (AppPrefs.getInstance().internal.dataDictVersion.getValue() < CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM || !copied) {
            if (legacyDir.exists() && !userDir.exists()) migrateLegacyUserData(legacyDir, userDir)
            copyFileOrDir(context, "rime", "", CustomConstant.RIME_SHARED_PATH, true)
            copyFileOrDir(context, "hw", "", CustomConstant.HW_DICT_PATH, true)
            File(sharedDir, ".copied").writeText(CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM.toString())
            AppPrefs.getInstance().internal.dataDictVersion.setValue(CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM)
            cleanupStaleRimeDirs(sharedDir, legacyDir)
        }
    }

    /** 旧目录是 shared/user 混用的，只迁出属于用户的数据：userdb（自学习词库）与 *.custom.yaml */
    private fun migrateLegacyUserData(legacyDir: File, userDir: File) {
        legacyDir.listFiles { f -> f.isDirectory && f.name.endsWith(".userdb") }?.forEach { db ->
            db.copyRecursively(File(userDir, db.name), overwrite = true)
        }
        legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".custom.yaml") }?.forEach { f ->
            f.copyTo(File(userDir, f.name), overwrite = true)
        }
    }

    /** 拷贝成功后清理旧版本目录与旧版混用目录，回收 100MB+ 磁盘 */
    private fun cleanupStaleRimeDirs(current: File, legacyDir: File) {
        current.parentFile?.listFiles { f -> f.isDirectory && f.name.startsWith("rime-v") && f.name != current.name }
            ?.forEach { it.deleteRecursively() }
        legacyDir.deleteRecursively()
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
