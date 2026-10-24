package com.whale.imemodule.application

import java.io.File
import com.whale.imemodule.data.flower.FlowerTypefaceMode

/**
 * 用户自定义常量类
 */
object CustomConstant {
    /**
     * 共享词库目录按内置版本号隔离：升级词库 = 新建 rime-v<N+1>，旧目录在拷贝完成后删除。
     * 只读，供 librime 的 shared_data_dir / prebuilt_data_dir 使用，每次启动无需重拷、无重部署。
     */
    var RIME_SHARED_PATH = File(
        Launcher.instance.context.getExternalFilesDir(null),
        "rime-v$CURRENT_RIME_DICT_DATA_VERSIOM"
    ).toString()
    /** librime 可写目录：userdb、部署产物 build/、installation.yaml 等，与共享词库分离 */
    var RIME_USER_PATH = Launcher.instance.context.getExternalFilesDir("rime-user").toString()
    /** 旧版本（shared/user 混用）的词库目录，仅用于升级时迁出用户数据 */
    val RIME_LEGACY_PATH = Launcher.instance.context.getExternalFilesDir("rime").toString()
    var HW_DICT_PATH: String  = Launcher.instance.context.getExternalFilesDir("hw").toString()
    const val SCHEMA_ZH_T9 = "t9_pinyin" // 拼音九键
    const val SCHEMA_ZH_QWERTY = "pinyin" // 拼音全键
    const val SCHEMA_EN = "english"         // 英语方案
    const val SCHEMA_ZH_HANDWRITING = "handwriting" // 手写输入
    const val SCHEMA_ZH_DOUBLE_FLYPY = "double_pinyin_" // 小鹤双拼
    const val SCHEMA_ZH_DOUBLE_LX17 = "double_pinyin_ls17" // 乱序17双拼
    const val SCHEMA_ZH_STROKE = "stroke" // 五笔画
    // 20261022：升级到 librime 1.17.0（prism 3.0→4.0、新增 schema yaml）
    // 20261023：schema 补 menu/page_size（缺省 5，等价旧版 setRimePageSize(100)）
    // 20261024：候选改走 candidate_list_* 全量列表（Rime.getCandidates），候选栏不再依赖
    //           `menu/page_size`，把该值从 100 降回默认 5：`get_context` 每键只预取 5 条，
    //           而不是为了取候选白算 100 条（翻译 + 过滤），实际要几条由 RimeEngine 逐段取。
    // 20261025：shared/user 目录分离（rime-v<N> 只读共享 + rime-user 可写），旧 `rime/` 目录
    //           升级时迁出 userdb 后删除；此后每次启动不再重拷、不再全量校验共享词库
    // 必须递增此版本号，否则已安装的设备不会重新复制 assets 里的词典与配置
    const val CURRENT_RIME_DICT_DATA_VERSIOM = 20261025
    const val WHALE_IME_REPO = "https://github.com/gurecn/WhaleIME"
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
    const val FEEDBACK_TXC_REPO = "https://github.com/gurecn/WhaleIME/issues"

    // 花漾字状态
    var flowerTypeface = FlowerTypefaceMode.Disabled
    // 剪切板/常用语界面锁定
    var lockClipBoardEnable = false
}