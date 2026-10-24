package com.whale.imemodule.application

import com.whale.imemodule.data.flower.FlowerTypefaceMode

/**
 * 用户自定义常量类
 */
object CustomConstant {
    var RIME_DICT_PATH = Launcher.instance.context.getExternalFilesDir("rime").toString()
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
    // 必须递增此版本号，否则已安装的设备不会重新复制 assets 里的词典与配置
    const val CURRENT_RIME_DICT_DATA_VERSIOM = 20261024
    const val WHALE_IME_REPO = "https://github.com/gurecn/WhaleIME"
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
    const val FEEDBACK_TXC_REPO = "https://github.com/gurecn/WhaleIME/issues"

    // 花漾字状态
    var flowerTypeface = FlowerTypefaceMode.Disabled
    // 剪切板/常用语界面锁定
    var lockClipBoardEnable = false
}