package com.yuyan.imemodule.compose.keyboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.GestureTuning
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.ImeUiState
import com.yuyan.imemodule.data.emojicon.EmojiconData
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.UsedSymbol
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.utils.DevicesUtils
import kotlin.math.max

/**
 * 符号 / 表情 / 颜文字面板。
 *
 * 旧实现是 `ViewPager2 + TabLayout + FlexboxLayoutManager`；
 * Compose 版用 `HorizontalPager + LazyVerticalGrid`，分页图标仍用 `EmojiconData` 的 key（drawable res）。
 *
 * 交互现代化：
 * - 符号格按下有缩放 + 圆角高亮（旧版只有字体本身，按下去毫无反馈）；
 * - 底部分类图标用「选中胶囊」，背景色随翻页做颜色过渡；
 * - 表情长按的肤色选择不再用 `PopupWindow`，改为浮在面板底部的圆角卡片——
 *   IME 窗口里再起 Popup 容易抢焦点，内联一张卡片更稳。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SymbolPanel(uiState: ImeUiState, controller: ImeController) {
    val palette = LocalImePalette.current

    // 分页数据：第一页「最近使用」按模式从数据库装载，为空时退回第一个非空分类
    // （旧 SymbolPagerAdapter.onBindViewHolder 同逻辑；迁移时丢了这一步导致首屏空白）。
    // 提交符号/表情后整体重载：分类数不变、pager 停留当前页，仅最近使用页内容刷新。
    var pages by remember(uiState.symbolMode) { mutableStateOf(loadPages(uiState.symbolMode)) }
    if (pages.isEmpty()) return
    val refreshPages = { pages = loadPages(uiState.symbolMode) }

    val pagerState = rememberPagerState(
        initialPage = uiState.symbolPage.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    // 滑动/程序翻页落定 → 记录当前页
    LaunchedEffect(pagerState.currentPage) { controller.setSymbolPage(pagerState.currentPage) }
    // 点底部分类图标 → 切页。旧实现走 ViewPager2.setCurrentItem；迁移时只剩
    // setSymbolPage 改状态，pager 从不响应，表现为「分类点了没反应」。
    LaunchedEffect(uiState.symbolPage) {
        val target = uiState.symbolPage.coerceIn(0, pages.lastIndex)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }

    var locked by remember(uiState.symbolMode) { mutableStateOf(uiState.symbolMode != SymbolMode.Symbol) }
    var skinTone by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val values = pages[page].second
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 40.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    items(values) { raw ->
                        val symbol = raw.replace("[ \\r]".toRegex(), "")
                        SymbolCell(
                            symbol = symbol,
                            onClick = {
                                DevicesUtils.tryPlayKeyDown()
                                DevicesUtils.tryVibrate()
                                commitSymbol(controller, uiState.symbolMode, symbol)
                                refreshPages()
                                if (!locked) controller.switchToInputKeyboard()
                            },
                            onLongClick = {
                                val variants = EmojiconData.skinToneVariants[symbol]
                                    ?: EmojiconData.skinToneVariants[symbol.replace("\uFE0F", "")]
                                if (variants == null) {
                                    commitSymbol(controller, uiState.symbolMode, symbol)
                                    refreshPages()
                                    if (!locked) controller.switchToInputKeyboard()
                                } else {
                                    DevicesUtils.tryPlayKeyDown()
                                    DevicesUtils.tryVibrate()
                                    skinTone = symbol to variants
                                }
                            },
                        )
                    }
                }
            }
            // 肤色选择条：浮在面板底部的卡片
            skinTone?.let { (base, variants) ->
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .shadow(10.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.card)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (listOf(base) + variants).forEach { option ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .imeClickable() {
                                    skinTone = null
                                    commitEmoji(controller, option)
                                    refreshPages()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = option,
                                color = palette.onSurface,
                                style = TextStyle(fontSize = 22.sp),
                            )
                        }
                    }
                }
            }
        }

        // 底部分类栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            pages.forEachIndexed { index, page ->
                val selected = pagerState.currentPage == index
                val bg by animateColorAsState(
                    targetValue = if (selected) palette.accent.copy(alpha = 0.16f) else Color.Transparent,
                    label = "symbolPageBg",
                )
                val tint by animateColorAsState(
                    targetValue = if (selected) palette.accent else palette.muted,
                    label = "symbolPageTint",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .imeClickable() { controller.setSymbolPage(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(page.first),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tint),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            ImeIconButton(
                iconRes = R.drawable.ic_menu_keyboard_switcher,
                contentDescription = null,
                tint = palette.onSurface,
                modifier = Modifier.padding(start = 2.dp),
                size = 36.dp,
                iconSize = 22.dp,
                onClick = { controller.switchToInputKeyboard() },
            )
            DeleteButton(
                locked = locked,
                onToggleLock = { locked = it },
                onDelete = {
                    DevicesUtils.tryPlayKeyDown(android.view.KeyEvent.KEYCODE_DEL)
                    DevicesUtils.tryVibrate()
                    controller.dispatcher.responseKeyEvent(SoftKey(android.view.KeyEvent.KEYCODE_DEL))
                },
            )
        }
    }
}

/** 单个符号格：按下时浮出圆角底色 + ripple，不做缩放（缩放会让整块面板跟着「沉」） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SymbolCell(symbol: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val palette = LocalImePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.keyPressed.copy(alpha = 0.7f) else Color.Transparent,
        label = "symbolCellBg",
    )
    Box(
        modifier = Modifier
            .padding(1.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = palette.onSurface,
            textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 21.sp),
        )
    }
}

@Composable
private fun DeleteButton(
    locked: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalImePalette.current
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val repeatRunnable = remember {
        // 长按连发：定时器走 Handler，避免在手势的 restricted suspend 作用域里 delay
        object : Runnable {
            override fun run() {
                onDelete()
                handler.postDelayed(this, GestureTuning.REPEAT_INTERVAL_MS)
            }
        }
    }
    Box(
        modifier = Modifier
            .padding(start = 2.dp, end = 2.dp)
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (locked) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .pointerInput(locked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (locked) {
                        handler.postDelayed(repeatRunnable, GestureTuning.REPEAT_START_DELAY_MS)
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                        }
                        handler.removeCallbacks(repeatRunnable)
                        onDelete()
                    } else {
                        // 未锁定：本次点击只是解锁
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                        }
                        onToggleLock(true)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (locked) R.drawable.sdk_skb_key_delete_icon else R.drawable.icon_symbol_lock
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (locked) palette.accent else palette.onSurface),
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun commitSymbol(controller: ImeController, mode: SymbolMode, symbol: String) {
    if (mode == SymbolMode.Symbol) {
        runCatching {
            DataBaseKT.instance.usedSymbolDao().insert(UsedSymbol(symbol = symbol))
            val num = max(DataBaseKT.instance.usedSymbolDao().getCount("symbol") - 50, 0)
            DataBaseKT.instance.usedSymbolDao().deleteOldest("symbol", num)
        }
    } else {
        runCatching {
            DataBaseKT.instance.usedSymbolDao().insert(UsedSymbol(symbol = symbol, type = "emoji"))
            val num = max(DataBaseKT.instance.usedSymbolDao().getCount("emoji") - 50, 0)
            DataBaseKT.instance.usedSymbolDao().deleteOldest("emoji", num)
        }
    }
    controller.dispatcher.responseKeyEvent(SoftKey(label = symbol))
}

private fun commitEmoji(controller: ImeController, emoji: String) {
    runCatching {
        DataBaseKT.instance.usedSymbolDao().insert(UsedSymbol(symbol = emoji, type = "emoji"))
        val num = max(DataBaseKT.instance.usedSymbolDao().getCount("emoji") - 50, 0)
        DataBaseKT.instance.usedSymbolDao().deleteOldest("emoji", num)
    }
    controller.dispatcher.responseKeyEvent(SoftKey(label = emoji))
}

/** 组装分页数据；「最近使用」页（recents）实时读库 */
private fun loadPages(mode: SymbolMode): List<Pair<Int, List<String>>> {
    val data = when (mode) {
        SymbolMode.Emoticon -> EmojiconData.emoticonData
        SymbolMode.Emojicon -> EmojiconData.emojiconData
        else -> EmojiconData.symbolData
    }
    return data.entries.map { (key, values) ->
        if (key == RECENTS_KEY) key to loadRecents(mode, data)
        else key to values
    }
}

/**
 * 最近使用：符号键盘取全部常用符号，表情/颜文字键盘取 emoji 记录；
 * 为空时退回一个非空分类（表情键走常用表情分类，旧 `defaultEmojiData` 同逻辑）。
 */
private fun loadRecents(mode: SymbolMode, data: Map<Int, List<String>>): List<String> {
    val recent = runCatching {
        if (mode == SymbolMode.Symbol) DataBaseKT.instance.usedSymbolDao().getAllUsedSymbol().map { it.symbol }
        else DataBaseKT.instance.usedSymbolDao().getAllSymbolEmoji().map { it.symbol }
    }.getOrDefault(emptyList())
    if (recent.isNotEmpty()) return recent
    return if (mode == SymbolMode.Symbol) {
        data.entries.firstOrNull { it.key != RECENTS_KEY }?.value
    } else {
        data[R.drawable.icon_emojibar_smileys]
            ?: data.entries.firstOrNull { it.key != RECENTS_KEY && it.value.isNotEmpty() }?.value
    } ?: emptyList()
}

private val RECENTS_KEY = R.drawable.icon_emojibar_recents
