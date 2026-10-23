package com.yuyan.imemodule.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import splitties.dimensions.dp
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.HalfWidthSymbolsMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.StringUtils
import kotlin.math.min

/**
 * 表情或符号界面适配器
 */
class SymbolAdapter(context: Context?, val viewType: SymbolMode, private val pagerIndex: Int, private val onClickSymbol: (String, Int) -> Unit, private val onLongClickSymbol: ((String, View) -> Unit)? = null) :
    RecyclerView.Adapter<SymbolAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater
    var mDatas: List<String>? = null
    private val halfWidthSymbolsMode = AppPrefs.getInstance().keyboardSetting.halfWidthSymbolsMode.getValue()

    companion object {
        /** emoji 表情网格每行列数 */
        const val EMOJI_COLUMN_COUNT = 8
        /** emoji 表情字号占单元格宽度的比例：列数不变的前提下放大表情，行数随之减少 */
        const val EMOJI_TEXT_SIZE_RATIO = 0.7f
        /** emoji 表情网格一屏至少保留的行数，用于限制横屏/悬浮键盘下的表情字号 */
        const val EMOJI_MIN_ROWS = 5
        /** emoji 表情 item 上下内边距（dp），控制行间距 */
        const val EMOJI_ITEM_PADDING_DP = 2
    }

    init {
        inflater = LayoutInflater.from(context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        return SymbolHolder(inflater.inflate(R.layout.sdk_item_recyclerview_symbols_emoji, parent, false))
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val data = mDatas!![position]
        holder.textView.text = data
        holder.tVSdb.visibility = if(viewType != SymbolMode.Symbol) View.GONE else {
            when (halfWidthSymbolsMode) {
                HalfWidthSymbolsMode.All -> if (StringUtils.isDBCSymbol(data)) View.VISIBLE else View.GONE
                HalfWidthSymbolsMode.OnlyUsed -> if (pagerIndex == 0 && StringUtils.isDBCSymbol(data)) View.VISIBLE else View.GONE
                HalfWidthSymbolsMode.None -> View.GONE
            }
        }
        holder.textView.setOnClickListener { _: View? ->
            onClickSymbol(mDatas!![position], position)
        }
        holder.textView.setOnLongClickListener { v: View? ->
            onLongClickSymbol?.invoke(mDatas!![position], v ?: holder.itemView)
            true
        }
    }

    override fun getItemCount(): Int {
        return mDatas?.size?:0
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_symbols_item)
        var tVSdb: TextView
        init {
            textView.setTextColor(activeTheme.keyTextColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize)
            tVSdb = view.findViewById(R.id.tv_sdb_symbols_item)
            tVSdb.setTextColor(activeTheme.keyTextColor)
            // emoji 表情网格：固定 8 列，表情字号按单元格宽度放大，缩小行间距
            if (viewType == SymbolMode.Emojicon) {
                val environment = EnvironmentSingleton.instance
                val cellWidth = environment.skbWidth / EMOJI_COLUMN_COUNT
                (view.layoutParams as FlexboxLayoutManager.LayoutParams).apply {
                    width = cellWidth
                }
                // 表情字号随屏幕宽度自适应：单元格宽度的 70%，同时限制为至少能显示 EMOJI_MIN_ROWS 行
                val emojiSizePx = min(cellWidth * EMOJI_TEXT_SIZE_RATIO, environment.skbHeight / EMOJI_MIN_ROWS.toFloat())
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DevicesUtils.px2dip(emojiSizePx.toInt()))
                textView.setPadding(0, view.dp(EMOJI_ITEM_PADDING_DP), 0, view.dp(EMOJI_ITEM_PADDING_DP))
            }
        }
    }
}
