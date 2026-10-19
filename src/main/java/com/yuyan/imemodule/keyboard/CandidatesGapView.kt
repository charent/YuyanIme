package com.yuyan.imemodule.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.DevicesUtils

/**
 * 候选词栏与键盘之间的间隔占位视图。
 *
 * 高度每次测量时直接从设置项读取，因此在设置中修改间距后，键盘重新布局即可生效。
 *
 * 这块间隔在视觉上要留着（否则选词时容易点到键盘第一行），但不该是一块死区：
 * 小屏手机上点候选词时，手指很容易落到这几 dp 里，结果候选词没上屏。所以这里把它做成
 * 「候选词的延展点击区」——落点在这一条间隔里时，按横坐标找到正上方的候选词并上屏，
 * 命中范围跟着候选词本身的宽度走（一个字、两个字、三个字自然各不相同）。
 */
class CandidatesGapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 间隔内的点击回调：入参是间隔内的横坐标，返回 true 表示已被候选词消费 */
    var onGapTouch: ((Float) -> Boolean)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val gap = DevicesUtils.dip2px(
            AppPrefs.getInstance().keyboardSetting.candidatesBottomGap.getValue()
        )
        // 宽度必须铺满父容器：宽度为 0 的 View 收不到触摸事件，间隔就做不成可点击的
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        setMeasuredDimension(width, gap)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只在按下时判断一次：命中候选词就直接上屏，并吞掉这次触摸，避免穿透到下面的键盘
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        return onGapTouch?.invoke(event.x) ?: false
    }
}
