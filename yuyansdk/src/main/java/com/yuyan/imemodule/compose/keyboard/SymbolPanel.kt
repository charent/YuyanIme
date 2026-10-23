package com.yuyan.imemodule.compose.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.GestureTuning
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.ImeUiState
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.data.emojicon.EmojiconData
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.UsedSymbol
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 符号 / 表情 / 颜文字面板。
 *
 * 旧实现是 `ViewPager2 + TabLayout + FlexboxLayoutManager`；
 * Compose 版用 `HorizontalPager + LazyVerticalGrid` 复刻，分页图标仍用 `EmojiconData` 的 key（drawable res）。
 *
 * 表情长按的肤色选择不再用 `PopupWindow`，改为在面板底部弹一条肤色栏——
 * IME 窗口里再起 Popup 窗口容易抢焦点，内联一条更稳。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SymbolPanel(uiState: ImeUiState, controller: ImeController) {
    val theme = LocalImeTheme.current
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val textColor = Color(theme.keyTextColor)

    val pages: List<Map.Entry<Int, List<String>>> = remember(uiState.symbolMode) {
        val data = when (uiState.symbolMode) {
            SymbolMode.Emoticon -> EmojiconData.emoticonData
            SymbolMode.Emojicon -> EmojiconData.emojiconData
            else -> EmojiconData.symbolData
        }
        data.entries.toList()
    }
    if (pages.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = uiState.symbolPage.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    LaunchedEffect(pagerState.currentPage) { controller.setSymbolPage(pagerState.currentPage) }

    var locked by remember(uiState.symbolMode) { mutableStateOf(uiState.symbolMode != SymbolMode.Symbol) }
    var skinTone by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val values = pages[page].value
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 38.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                ) {
                    items(values) { raw ->
                        val symbol = raw.replace("[ \\r]".toRegex(), "")
                        Box(
                            modifier = Modifier
                                .height(42.dp)
                                .combinedClickable(
                                    onClick = {
                                        DevicesUtils.tryPlayKeyDown()
                                        DevicesUtils.tryVibrate()
                                        commitSymbol(controller, uiState.symbolMode, symbol)
                                        if (!locked) controller.switchToInputKeyboard()
                                    },
                                    onLongClick = {
                                        val variants = EmojiconData.skinToneVariants[symbol]
                                            ?: EmojiconData.skinToneVariants[symbol.replace("\uFE0F", "")]
                                        if (variants == null) {
                                            // 不支持肤色：长按等同单击
                                            commitSymbol(controller, uiState.symbolMode, symbol)
                                            if (!locked) controller.switchToInputKeyboard()
                                        } else {
                                            DevicesUtils.tryPlayKeyDown()
                                            DevicesUtils.tryVibrate()
                                            skinTone = symbol to variants
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = symbol,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                style = TextStyle(fontSize = 20.sp),
                            )
                        }
                    }
                }
            }
            // 肤色选择条
            skinTone?.let { (base, variants) ->
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(theme.keyBackgroundColor))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (listOf(base) + variants).forEach { option ->
                        Text(
                            text = option,
                            color = textColor,
                            style = TextStyle(fontSize = 22.sp),
                            modifier = Modifier.clickable {
                                skinTone = null
                                commitEmoji(controller, option)
                            }.padding(6.dp),
                        )
                    }
                }
            }
        }

        // 分页图标（底部）
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { index, entry ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .then(
                            if (selected) Modifier.background(
                                Color(theme.keyPressHighlightColor),
                                RoundedCornerShape(ThemeManager.prefs.keyRadius.getValue().toFloat()),
                            ) else Modifier
                        )
                        .clickable { controller.setSymbolPage(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(entry.key),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 返回 + 删除
            Image(
                painter = painterResource(R.drawable.ic_menu_keyboard_switcher),
                contentDescription = null,
                colorFilter = ColorFilter.tint(textColor),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(24.dp)
                    .clickable { controller.switchToInputKeyboard() },
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

@Composable
private fun DeleteButton(
    locked: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val theme = LocalImeTheme.current
    val textColor = Color(theme.keyTextColor)
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
            .padding(end = 6.dp)
            .size(32.dp)
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
            colorFilter = ColorFilter.tint(textColor),
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
