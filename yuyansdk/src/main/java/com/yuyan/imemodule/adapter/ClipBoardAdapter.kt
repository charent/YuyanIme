package com.yuyan.imemodule.adapter

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.ClipboardLayoutMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.DevicesUtils.dip2px
import splitties.views.dsl.core.margin

/**
 * 剪切板界面适配器
 */
class ClipBoardAdapter(context: Context, datas: MutableList<Clipboard>) :
    RecyclerView.Adapter<ClipBoardAdapter.SymbolHolder>() {
    private var mDatas : MutableList<Clipboard> = datas
    private val mContext: Context
    private var textColor: Int
    private var clipboardLayoutCompact: ClipboardLayoutMode

    /** 序号字号：比正文小不少，只作定位用 */
    private val indexTextSize: Float

    init {
        val theme = activeTheme
        textColor = theme.keyTextColor
        mContext = context
        clipboardLayoutCompact = AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()
        indexTextSize = (instance.candidateTextSize * INDEX_TEXT_RATIO).coerceAtLeast(MIN_INDEX_TEXT_SIZE)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val marginValue = dip2px(3)
        // item 根布局透明：上半截放序号（落在两条内容之间的背景上），下半截是带背景的内容卡片
        val mRoot = LinearLayout(mContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        mRoot.layoutParams = when (clipboardLayoutCompact){
            ClipboardLayoutMode.ListView -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(marginValue*2, marginValue, marginValue*2, marginValue)
            }
            else -> GridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(marginValue, marginValue, marginValue, marginValue)
            }
        }
        // 序号：不在卡片内，位于卡片上方的间隔里，淡色小字，不抢正文
        val viewIndex = TextView(mContext).apply {
            id = R.id.clipboard_adapter_index
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, indexTextSize)
            setTextColor(textColor)
            alpha = INDEX_ALPHA
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = marginValue * 2
            }
        }
        val mContainer = RelativeLayout(mContext)
        mContainer.gravity = Gravity.CENTER_VERTICAL
        mContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        mContainer.background = GradientDrawable().apply {
            setColor(activeTheme.keyBackgroundColor)
            setShape(GradientDrawable.RECTANGLE)
            setCornerRadius(ThemeManager.prefs.keyRadius.getValue().toFloat()) // 设置圆角半径
        }
        val viewContext = EmojiTextView(mContext).apply {
            id = R.id.clipboard_adapter_content
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                margin = marginValue
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
        }
        val viewIvYopTips = ImageView(mContext).apply {
            id = R.id.clipboard_adapter_top_tips
            setImageResource(R.drawable.ic_baseline_top_tips_32)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE)
            }
        }
        mContainer.addView(viewContext)
        mContainer.addView(viewIvYopTips)
        mRoot.addView(viewIndex)
        mRoot.addView(mContainer)
        return SymbolHolder(mRoot)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val data = mDatas[position]
        // 序号按当前列表位置实时计算，删除后随位置变化自动重排，不落库
        holder.textIndex.text = "${position + 1}:"
        holder.textView.text = data.content.replace("\n", "\\n")
        holder.ivTopTips.visibility = if(data.isKeep == 1)View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textIndex: TextView = view.findViewById(R.id.clipboard_adapter_index)
        var textView: TextView = view.findViewById(R.id.clipboard_adapter_content)
        var ivTopTips: ImageView
        init {
            textView.setTextColor(textColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize)
            ivTopTips = view.findViewById(R.id.clipboard_adapter_top_tips)
        }
    }

    private companion object {
        /** 序号字号相对正文字号的占比 */
        const val INDEX_TEXT_RATIO = 0.55f

        /** 序号最小字号（dp） */
        const val MIN_INDEX_TEXT_SIZE = 8f

        /** 序号透明度，弱化显示 */
        const val INDEX_ALPHA = 0.45f
    }
}
