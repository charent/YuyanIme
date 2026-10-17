package com.yuyan.imemodule.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.DevicesUtils

/**
 * 候选词栏与键盘之间的间隔占位视图。
 *
 * 高度每次测量时直接从设置项读取，因此在设置中修改间距后，键盘重新布局即可生效。
 */
class CandidatesGapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val gap = DevicesUtils.dip2px(
            AppPrefs.getInstance().keyboardSetting.candidatesBottomGap.getValue()
        )
        setMeasuredDimension(0, gap)
    }
}
