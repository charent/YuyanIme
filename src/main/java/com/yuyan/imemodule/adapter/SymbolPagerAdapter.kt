package com.yuyan.imemodule.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import com.yuyan.imemodule.R
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.manager.layout.CustomFlexboxLayoutManager

/**
 * 表情或符号界面适配器
 */
class SymbolPagerAdapter(context: Context, private val mDatas: Map<Int, List<String>>, val viewType: SymbolMode, private val onClickSymbol: (String, Int) -> Unit, private val onLongClickSymbol: ((String, View) -> Unit)? = null) :
    RecyclerView.Adapter<SymbolPagerAdapter.ViewHolder>() {
    private val mContext: Context

    init {
        mContext = context
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiGroupRv: RecyclerView = view.findViewById(R.id.emojiGroupRv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(mContext).inflate(R.layout.sdk_item_pager_symbols_emoji, parent, false))
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    /**
     * 最近使用为空时的兜底数据：表情键盘取常用表情分类（不依赖下标，避免分类增删后错位）
     */
    private fun defaultEmojiData(): List<String>? {
        return mDatas[R.drawable.icon_emojibar_smileys] ?: mDatas.values.firstOrNull { it.isNotEmpty() }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = when (val key = mDatas.keys.toList()[position]) {
            R.drawable.icon_emojibar_recents -> {
                if (viewType != SymbolMode.Symbol) DataBaseKT.instance.usedSymbolDao().getAllSymbolEmoji().map { it.symbol }.takeIf { it.isNotEmpty() } ?: defaultEmojiData()
                else DataBaseKT.instance.usedSymbolDao().getAllUsedSymbol().map { it.symbol }.takeIf { it.isNotEmpty() } ?: mDatas[mDatas.keys.toList()[1]]
            }
            else -> mDatas[key]
        }
        val manager = CustomFlexboxLayoutManager(mContext)
        manager.flexDirection = FlexDirection.ROW
        manager.flexWrap = FlexWrap.WRAP
        // emoji 网格固定列宽，左对齐排列（避免最后一行或数量不足时被分散对齐拉开）
        manager.justifyContent = if (viewType == SymbolMode.Emojicon) JustifyContent.FLEX_START else JustifyContent.SPACE_AROUND
        holder.emojiGroupRv.layoutManager = manager
        val mSymbolAdapter = SymbolAdapter(mContext, viewType, position, onClickSymbol, onLongClickSymbol)
        mSymbolAdapter.mDatas = item
        holder.emojiGroupRv.adapter = mSymbolAdapter
    }
}
