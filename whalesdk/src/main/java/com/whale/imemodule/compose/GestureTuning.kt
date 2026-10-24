package com.whale.imemodule.compose

import com.whale.imemodule.data.theme.ThemeManager
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.prefs.behavior.KeyboardSymbolSlideUpMod
import com.whale.imemodule.singleton.EnvironmentSingleton

/**
 * 键盘手势参数集中处（方案 §2.2 要求）。
 *
 * 所有数值都从旧实现 `BaseKeyboardView` / `compose` 前的代码**原值搬移**，
 * 不在这里做任何「优化」——手感回归时只需要比对这个文件，
 * 也能保证 Compose 版和旧版在同一台机器上跑出同一套阈值。
 *
 * 注意：这里的值都是**运行时读取**（跟随设置页即时生效），
 * 因此调用点不要在 `remember` 里缓存，直接在手势回调里取。
 */
object GestureTuning {

    /** 长按连发的首次延迟（`BaseKeyboardView.REPEAT_START_DELAY`） */
    const val REPEAT_START_DELAY_MS = 400L

    /** 长按连发的重复间隔（`BaseKeyboardView.REPEAT_INTERVAL`） */
    const val REPEAT_INTERVAL_MS = 50L

    /** 删除键左滑触发「清空」的最小横向距离（旧实现为 `distanceX > 20`） */
    const val DELETE_SWIPE_MIN_PX = 20f

    /**
     * 判定纵向滑动时，纵向位移相对横向位移的倍数。
     * 旧实现：`relDiffX * 1.5 < relDiffY`，横竖接近时优先触发左右滑动。
     */
    const val VERTICAL_DOMINANCE = 1.5f

    /** 菜单面板上滑触发阈值 = 小标点阈值 × 该倍数（旧实现 `symbolSlideUp * 2`） */
    const val MENU_SLIDE_UP_MULTIPLIER = 2f

    /** 浮动键盘移动条判定「拖动了」的最小位移（dp） */
    const val MOVE_BAR_THRESHOLD_DP = 10f

    /** 长按触发弹层的超时，跟随用户设置 */
    val longPressTimeoutMs: Long
        get() = AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong()

    /**
     * 上滑触发小标点上屏的距离阈值。
     * 旧实现以「候选区高度」为基准，按「上滑灵敏度」设置除以 3 / 2 / 1。
     */
    val symbolSlideUpThreshold: Float
        get() {
            val base = EnvironmentSingleton.instance.heightForCandidatesArea
            val divisor = when (ThemeManager.prefs.symbolSlideUpMod.getValue()) {
                KeyboardSymbolSlideUpMod.SHORT -> 3f
                KeyboardSymbolSlideUpMod.MEDIUM -> 2f
                else -> 1f
            }
            return base / divisor
        }

    /** 上滑唤起菜单面板的距离阈值 */
    val menuSlideUpThreshold: Float
        get() = symbolSlideUpThreshold * MENU_SLIDE_UP_MULTIPLIER

    /** 空格/0 键左右滑动移动光标的步进阈值 */
    val spaceSwipeSpeed: Float
        get() = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursorSpeed.getValue().toFloat()

    /** 是否允许上滑小标点（跟随「键盘显示符号」开关） */
    val symbolSlideUpEnabled: Boolean
        get() = ThemeManager.prefs.keyboardSymbol.getValue()

    /** 是否允许空格滑动移动光标 */
    val spaceSwipeMoveCursor: Boolean
        get() = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursor.getValue()

    /** 是否显示按键气泡 */
    val balloonEnabled: Boolean
        get() = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow.getValue()
}
