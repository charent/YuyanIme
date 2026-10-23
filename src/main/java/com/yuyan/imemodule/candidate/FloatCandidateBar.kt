package com.yuyan.imemodule.candidate

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.yuyan.imemodule.adapter.CandidatesBarAdapter
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.manager.layout.CustomLinearLayoutManager
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import splitties.dimensions.dp

/**
 * 距离列表末尾还剩几项就预取下一批候选（与 [com.yuyan.imemodule.view.CandidatesBar] 一致）。
 */
private const val LOAD_MORE_LOOKAHEAD = 6

/**
 * 候选词集装箱
 */
class FloatCandidateBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    private var mFloatCandidateBarWidth: Int = 0

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mComposingView: TextView // 组成字符串的View，用于显示输入的拼音。
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var candidatesData: LinearLayout //候选词视图
    private var activeCandNo:Int = 0

    /** 上一次全量刷新时对应的候选"代"与条数，用于识别"只是追加了一批" */
    private var syncedGeneration = -1
    private var syncedCount = 0

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        mFloatCandidateBarWidth = (if(instance.isLandscape)instance.mScreenHeight else instance.mScreenWidth) - dp(40)
        initCandidateView()
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            mComposingView = TextView(context).apply {
                includeFontPadding = false
                setPadding(dp(10), 0, dp(10), 0)
            }
            candidatesData = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        DictDecoder.activeCandidateBar =
                            layoutManager.findLastVisibleItemPosition()
                        val itemCount = recyclerView.adapter?.itemCount
                        if (KeyboardManager.instance.currentContainer !is CandidatesContainer && itemCount != null && DictDecoder.activeCandidateBar >= itemCount - LOAD_MORE_LOOKAHEAD) {
                            DictDecoder.nextPageCandidates
                        }
                    }
                }
            })
            mCandidatesDataContainer.addView(mComposingView)
            mCandidatesDataContainer.addView(candidatesData)
            this.addView(mCandidatesDataContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
        }
        val candidatesHeight = instance.heightForCandidates
        mComposingView.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, instance.heightForcomposing)
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, candidatesHeight)
        candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
        mComposingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.composingTextSize)
        mCandidatesAdapter.notifyChanged()
    }

    /**
     * 显示候选词
     */
    fun showCandidates() {
        // 追加路径：只增量插入，不重绑、不归位（否则滑动惯性会被打断）
        if (DictDecoder.generation == syncedGeneration && DictDecoder.candidateSize > syncedCount) {
            mCandidatesAdapter.notifyRangeInserted(DictDecoder.candidateSize - syncedCount)
            syncedCount = DictDecoder.candidateSize
            return
        }
        syncedGeneration = DictDecoder.generation
        syncedCount = DictDecoder.candidateSize
        mComposingView.text = DictDecoder.composingStrForDisplay
        if (DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) {
            this.visibility = GONE
        } else {
            if (DictDecoder.candidateSize > DictDecoder.activeCandidateBar) mRVCandidates.layoutManager?.scrollToPosition(DictDecoder.activeCandidateBar)
            this.visibility = VISIBLE
        }
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
    }

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DictDecoder.isCandidatesEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DictDecoder.candidateSize) activeCandNo = DictDecoder.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mCandidatesAdapter.notifyChanged()
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo - 1 else 0
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(instance.heightForCandidatesArea, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(mFloatCandidateBarWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    // 刷新主题
    fun updateTheme(textColor: Int) {
        initCandidateView()
        mComposingView.setTextColor(textColor)
        val drawable = GradientDrawable()
        drawable.setShape(GradientDrawable.RECTANGLE)
        drawable.setColor(ThemeManager.activeTheme.keyboardColor)
        val cornerRadiusInPx = 20f
        drawable.setCornerRadius(cornerRadiusInPx)
        background = drawable
        mCandidatesAdapter.notifyChanged()
    }
}
