package com.yuyan.imemodule.adapter

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.database.entry.UserWord
import splitties.dimensions.dp

/**
 * 用户词库列表适配器
 *
 * 条目：词语（大）/「来源 · 命中次数 · 权重」（小），点击回调给编辑弹窗。
 */
class UserDictAdapter(
    private val context: Context,
    private val onClick: (UserWord) -> Unit
) : RecyclerView.Adapter<UserDictAdapter.WordHolder>() {

    private val items = mutableListOf<UserWord>()

    private val textPrimary = context.resolveThemeColor(android.R.attr.textColorPrimary)
    private val textSecondary = context.resolveThemeColor(android.R.attr.textColorSecondary)

    val size: Int get() = items.size

    fun submit(list: List<UserWord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<UserWord>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordHolder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setBackgroundResource(context.selectableItemBackground())
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tvWord = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(textPrimary)
            maxLines = 1
        }
        val tvSummary = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        column.addView(tvWord)
        column.addView(tvSummary)
        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return WordHolder(root, tvWord, tvSummary)
    }

    override fun onBindViewHolder(holder: WordHolder, position: Int) {
        val item = items.getOrNull(position) ?: return
        holder.word.text = item.word
        val source = context.getString(
            if (item.source == UserWord.SOURCE_LEARNED) R.string.user_dict_source_learned
            else R.string.user_dict_source_manual
        )
        holder.summary.text = context.getString(
            R.string.user_dict_item_summary, "$source · ${item.code}", item.hits, item.weight
        )
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    inner class WordHolder(
        view: View,
        val word: TextView,
        val summary: TextView
    ) : RecyclerView.ViewHolder(view)
}

internal fun Context.resolveThemeColor(attr: Int): Int {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return Color.BLACK
    return when {
        value.resourceId != 0 -> ContextCompat.getColor(this, value.resourceId)
        value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT -> value.data
        else -> Color.BLACK
    }
}

internal fun Context.selectableItemBackground(): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) value.resourceId else 0
}
