package com.whale.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.imemodule.R
import com.whale.imemodule.application.CustomConstant
import com.whale.imemodule.compose.ImeController
import com.whale.imemodule.compose.ImeUiState
import com.whale.imemodule.database.DataBaseKT
import com.whale.imemodule.database.entry.Clipboard
import com.whale.imemodule.prefs.behavior.PopupMenuMode
import com.whale.imemodule.prefs.behavior.SkbMenuMode
import com.whale.imemodule.utils.DevicesUtils
import com.whale.inputmethod.CustomEngine

/**
 * 剪贴板 / 常用语列表。
 *
 * 旧实现是 `SwipeRecyclerView`（侧滑出「置顶 / 删除」菜单）。
 * Compose 版把这两个动作放到每行右侧的图标上——侧滑删除在 IME 这种
 * 与宿主滑动嵌套的容器里很容易和「候选栏横滑」抢手势，
 * 改成显式图标后行为更可预期，能力不减少。
 *
 * 视觉上每一条是一张圆角卡片：内容区点击即上屏，右侧两个圆形图标按钮。
 */
@Composable
fun ClipboardPanel(uiState: ImeUiState, controller: ImeController) {
    val palette = LocalImePalette.current
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
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_menu_clipboard),
                contentDescription = null,
                colorFilter = ColorFilter.tint(palette.muted),
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = controller.string(R.string.clipboard_empty_ltip),
                color = palette.muted,
                style = TextStyle(fontSize = 14.sp),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.card.copy(alpha = 0.9f))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.content,
                    color = palette.onSurface,
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .imeClickable() {
                            DevicesUtils.tryPlayKeyDown()
                            DevicesUtils.tryVibrate()
                            controller.dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Text, item.content))
                            if (!CustomConstant.lockClipBoardEnable) controller.switchToInputKeyboard()
                        },
                )
                // 置顶 / 编辑
                ImeIconButton(
                    iconRes = if (mode == SkbMenuMode.ClipBoard) {
                        if (item.isKeep == 1) R.drawable.ic_baseline_untop_circle_32
                        else R.drawable.ic_baseline_top_circle_32
                    } else R.drawable.ic_menu_edit,
                    contentDescription = null,
                    tint = if (item.isKeep == 1) palette.accent else palette.muted,
                    size = 34.dp,
                    iconSize = 20.dp,
                    onClick = {
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
                ImeIconButton(
                    iconRes = R.drawable.ic_menu_delete,
                    contentDescription = null,
                    tint = palette.muted,
                    size = 34.dp,
                    iconSize = 20.dp,
                    onClick = {
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
