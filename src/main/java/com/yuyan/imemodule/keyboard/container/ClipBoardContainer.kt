package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.ClipBoardAdapter
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.libs.recyclerview.SwipeMenu
import com.yuyan.imemodule.libs.recyclerview.SwipeMenuBridge
import com.yuyan.imemodule.libs.recyclerview.SwipeMenuItem
import com.yuyan.imemodule.libs.recyclerview.SwipeRecyclerView
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.ClipboardLayoutMode
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.layout.CustomGridLayoutManager
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.inputmethod.CustomEngine
import splitties.dimensions.dp
import splitties.views.textResource
import kotlin.math.ceil

/**
 * 粘贴板列表键盘容器
 *
 * 使用RecyclerView实现垂直ListView列表布局。
 */
@SuppressLint("ViewConstructor")
class ClipBoardContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private val mPaint : Paint = Paint() // 测量字符串长度
    private val mRVSymbolsView: SwipeRecyclerView = SwipeRecyclerView(context)
    private var mTVLable: TextView? = null
    private var itemMode:SkbMenuMode? = null

    /**
     * 分页数据源，与 [ClipBoardAdapter] 共享同一个实例：
     * 剪贴板最多 1000 条、单条正文最长 65535 字符，全量载入代价太高，改为滚动到底部时追加下一页。
     */
    private val mContents: MutableList<Clipboard> = mutableListOf()
    private var mAdapter: ClipBoardAdapter? = null
    private var hasMorePage = true
    private var isLoadingPage = false

    private companion object {
        /** 每页条数 */
        const val PAGE_SIZE = 100

        /** 距列表底部还剩多少条时开始预加载下一页 */
        const val PRELOAD_DISTANCE = 10

        /** 估算列数时最多测量的字符数：更长的文本无论如何都会占满整行，全量 measureText 代价太高 */
        const val MEASURE_CHAR_LIMIT = 128
    }

    init {
        mPaint.textSize = dp(22f)
        initView(context)
        initLoadMoreListener()
    }

    private fun initView(context: Context) {
        mTVLable = TextView(context).apply {
            textResource = R.string.clipboard_empty_ltip
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            textSize = instance.candidateTextSize
        }
        mRVSymbolsView.setItemAnimator(null)
        val layoutParams2 = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        mRVSymbolsView.layoutParams = layoutParams2
        this.addView(mRVSymbolsView)
    }

    /**
     * 滚动到接近底部时追加下一页；监听器只注册一次，切换列表内容时复用
     */
    private fun initLoadMoreListener() {
        mRVSymbolsView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= mContents.size - PRELOAD_DISTANCE) {
                    loadNextPage()
                }
            }
        })
    }

    /**
     * 显示候选词界面 , 点击候选词时执行
     */
    fun showClipBoardView(item: SkbMenuMode) {
        CustomConstant.lockClipBoardEnable = false
        itemMode = item
        mRVSymbolsView.setHasFixedSize(true)

        mContents.clear()
        hasMorePage = true
        isLoadingPage = false
        loadPage(0)

        val layoutMode = AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()
        val manager =  when (layoutMode){
            ClipboardLayoutMode.ListView ->  LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            ClipboardLayoutMode.GridView -> CustomGridLayoutManager(context, 2)
            ClipboardLayoutMode.FlexboxView -> {
                calculateColumn(mContents)
                CustomGridLayoutManager(context, 6).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(i: Int) = mHashMapSymbols[i] ?: 1
                    }
                }
            }
        }
        mRVSymbolsView.setLayoutManager(manager)
        val viewParent = mTVLable?.parent
        if (viewParent != null) {
            (viewParent as ViewGroup).removeView(mTVLable)
        }
        if(mContents.isEmpty()){
            this.addView(mTVLable, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val adapter = ClipBoardAdapter(context, mContents)
        mAdapter = adapter
        mRVSymbolsView.setAdapter(null)
        mRVSymbolsView.setOnItemClickListener{ _: View?, position: Int ->
            inputView.responseLongKeyEvent(Pair(PopupMenuMode.Text, mContents[position].content))
            if(!CustomConstant.lockClipBoardEnable)KeyboardManager.instance.switchKeyboard()
        }
        mRVSymbolsView.setSwipeMenuCreator{ _: SwipeMenu, rightMenu: SwipeMenu, position: Int ->
            val topItem = SwipeMenuItem(mContext).apply {
                setImage(if(itemMode == SkbMenuMode.ClipBoard) {
                    if(mContents[position].isKeep == 1)R.drawable.ic_baseline_untop_circle_32 else R.drawable.ic_baseline_top_circle_32 }
                else R.drawable.ic_menu_edit)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(topItem)
            val deleteItem = SwipeMenuItem(mContext).apply {
                setImage(R.drawable.ic_menu_delete)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(deleteItem)
        }
        mRVSymbolsView.setOnItemMenuClickListener { menuBridge: SwipeMenuBridge, position: Int ->
            menuBridge.closeMenu()
            if(position >= mContents.size) return@setOnItemMenuClickListener
            if(itemMode == SkbMenuMode.ClipBoard){
                if(menuBridge.position == 0) {
                    val data: Clipboard = mContents[position]
                    data.isKeep = 1 - data.isKeep
                    DataBaseKT.instance.clipboardDao().update(data)
                    showClipBoardView(SkbMenuMode.ClipBoard)
                } else if(menuBridge.position == 1){
                    val data: Clipboard = mContents.removeAt(position)
                    DataBaseKT.instance.clipboardDao().deleteByContent(data.content)
                    mRVSymbolsView.adapter?.notifyItemRemoved(position)
                    // 序号按位置实时计算，删除后刷新后续条目，保证从上往下连续
                    mRVSymbolsView.adapter?.notifyItemRangeChanged(position, mContents.size - position)
                }
            } else {
                val content = mContents[position].content
                if(menuBridge.position == 0) {
                    inputView.onSettingsMenuClick(SkbMenuMode.AddPhrases, DataBaseKT.instance.phraseDao().queryByContent(content))
                } else if(menuBridge.position == 1){
                    DataBaseKT.instance.phraseDao().deleteByContent(content)
                    CustomEngine.invalidatePhraseCache()
                    showClipBoardView(SkbMenuMode.Phrases)
                }
            }
        }
        mRVSymbolsView.setAdapter(adapter)
    }

    /**
     * 追加一页数据，返回本次追加的条数
     */
    private fun loadPage(offset: Int): Int {
        val mode = itemMode ?: return 0
        isLoadingPage = true
        val page: List<Clipboard> = when (mode) {
            SkbMenuMode.ClipBoard -> DataBaseKT.instance.clipboardDao().getPage(PAGE_SIZE, offset)
            else -> DataBaseKT.instance.phraseDao().getPage(PAGE_SIZE, offset)
                .map { line -> Clipboard(line.content) }
        }
        isLoadingPage = false
        hasMorePage = page.size >= PAGE_SIZE
        mContents.addAll(page)
        return page.size
    }

    /**
     * 加载下一页并刷新列表
     */
    private fun loadNextPage() {
        if (!hasMorePage || isLoadingPage) return
        val start = mContents.size
        if (loadPage(start) == 0) return
        val adapter = mAdapter ?: return
        if (AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue() == ClipboardLayoutMode.FlexboxView) {
            // 瀑布流需要按全部已加载数据重算列数，末尾那条的列数可能因为新数据而变化
            calculateColumn(mContents)
            if (start > 0) adapter.notifyItemChanged(start - 1)
        }
        adapter.notifyItemRangeInserted(start, mContents.size - start)
    }

    private val mHashMapSymbols = HashMap<Int, Int>() //候选词索引列数对应表
    private fun calculateColumn(data : MutableList<Clipboard>) {
        mHashMapSymbols.clear()
        val itemWidth = instance.skbWidth/6 - dp(10)
        var mCurrentColumn = 0
        val contents = data.map { it.content }
        contents.forEachIndexed { position, candidate ->
            var count = getSymbolsCount(candidate, itemWidth)
            var nextCount = 0
            if (contents.size > position + 1) {
                val nextCandidate = contents[position + 1]
                nextCount = getSymbolsCount(nextCandidate, itemWidth)
            }
            mCurrentColumn = if (mCurrentColumn + count + nextCount > 6) {
                count = 6 - mCurrentColumn
                0
            } else  (mCurrentColumn + count) % 6
            mHashMapSymbols[position] = count
        }
    }

    private fun getSymbolsCount(data: String, itemWidth:Int): Int {
        return if (!TextUtils.isEmpty(data)) {
            ceil(mPaint.measureText(data.take(MEASURE_CHAR_LIMIT)).div(itemWidth)).toInt()
        } else 0
    }
    fun getMenuMode():SkbMenuMode? {
       return itemMode
    }
}
