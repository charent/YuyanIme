package com.yuyan.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.ImeUiState
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.inputmethod.CustomEngine

/**
 * 剪贴板 / 常用语列表。
 *
 * 旧实现是 `SwipeRecyclerView`（侧滑出「置顶 / 删除」菜单）。
 * Compose 版把这两个动作放到每行右侧的图标上——侧滑删除在 IME 这种
 * 与宿主滑动嵌套的容器里很容易和「候选栏横滑」抢手势，
 * 改成显式图标后行为更可预期，能力不减少。
 */
@Composable
fun ClipboardPanel(uiState: ImeUiState, controller: ImeController) {
    val theme = LocalImeTheme.current
    val textColor = Color(theme.keyTextColor)
    val mode = uiState.clipboardMode
    val listState = rememberLazyListState()

    var items by remember(mode, uiState.clipboardRevision) { mutableStateOf(loadFirstPage(mode)) }
    var hasMore by remember(mode, uiState.clipboardRevision) { mutableStateOf(items.size >= PAGE_SIZE) }

    LaunchedEffect(listState.firstVisibleItemIndex, items.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (hasMore && last >= items.size - PRELOAD_DISTANCE) {
            val next = loadPage(mode, items.size)
            if (next.isNotEmpty()) {
                items = items + next
                hasMore = next.size >= PAGE_SIZE
            } else {
                hasMore = false
            }
        }
    }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = controller.string(R.string.clipboard_empty_ltip),
                color = textColor,
                style = TextStyle(fontSize = 14.sp),
            )
        }
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.content,
                    color = textColor,
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            DevicesUtils.tryPlayKeyDown()
                            DevicesUtils.tryVibrate()
                            controller.dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Text, item.content))
                            if (!CustomConstant.lockClipBoardEnable) controller.switchToInputKeyboard()
                        },
                )
                // 置顶 / 编辑
                Image(
                    painter = painterResource(
                        if (mode == SkbMenuMode.ClipBoard) {
                            if (item.isKeep == 1) R.drawable.ic_baseline_untop_circle_32
                            else R.drawable.ic_baseline_top_circle_32
                        } else R.drawable.ic_menu_edit
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(textColor),
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(22.dp)
                        .clickable {
                            if (mode == SkbMenuMode.ClipBoard) {
                                runCatching {
                                    val data = items[index]
                                    data.isKeep = 1 - data.isKeep
                                    DataBaseKT.instance.clipboardDao().update(data)
                                }
                                items = loadFirstPage(mode)
                            } else {
                                controller.startAddPhrases(
                                    runCatching {
                                        DataBaseKT.instance.phraseDao().queryByContent(item.content)
                                    }.getOrNull()
                                )
                            }
                        },
                )
                // 删除
                Image(
                    painter = painterResource(R.drawable.ic_menu_delete),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(textColor),
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(22.dp)
                        .clickable {
                            runCatching {
                                if (mode == SkbMenuMode.ClipBoard) {
                                    DataBaseKT.instance.clipboardDao().deleteByContent(item.content)
                                } else {
                                    DataBaseKT.instance.phraseDao().deleteByContent(item.content)
                                    CustomEngine.invalidatePhraseCache()
                                }
                            }
                            items = items.toMutableList().also { it.removeAt(index) }
                        },
                )
            }
        }
    }
}

private fun loadFirstPage(mode: SkbMenuMode): List<Clipboard> = loadPage(mode, 0)

private fun loadPage(mode: SkbMenuMode, offset: Int): List<Clipboard> = runCatching {
    if (mode == SkbMenuMode.ClipBoard) {
        DataBaseKT.instance.clipboardDao().getPage(PAGE_SIZE, offset)
    } else {
        DataBaseKT.instance.phraseDao().getPage(PAGE_SIZE, offset)
            .map { line -> Clipboard(line.content) }
    }
}.getOrDefault(emptyList())

private const val PAGE_SIZE = 100
private const val PRELOAD_DISTANCE = 10
