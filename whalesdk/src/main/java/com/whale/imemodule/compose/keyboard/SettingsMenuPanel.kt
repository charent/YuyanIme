package com.whale.imemodule.compose.keyboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.imemodule.R
import com.whale.imemodule.compose.ImeController
import com.whale.imemodule.compose.ImeUiState
import com.whale.imemodule.compose.SettingsPage
import com.whale.imemodule.data.menuSkbFunsPreset
import com.whale.imemodule.database.DataBaseKT
import com.whale.imemodule.database.entry.SkbFun
import com.whale.imemodule.entity.SkbFunItem
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.whale.imemodule.prefs.behavior.SkbMenuMode
import com.whale.imemodule.utils.DevicesUtils
import com.whale.inputmethod.HWEngine

/**
 * SETTINGS 容器：功能菜单宫格 / 输入方式选择 / 菜单项定制。
 *
 * 旧实现是 `RecyclerView + 宫格 + ItemTouchHelper 拖拽排序`，Compose 版把三个页面
 * 统一成「卡片格子 / 卡片行」：
 * - 功能宫格：每项是一张圆角卡片（图标 + 标签），带错峰入场与按压缩放；
 * - 输入方式：同款卡片，图标更大、标题居中；
 * - 定制页：行卡片 + 星标（常驻）/ 上移下移按钮。
 *
 * 排序仍然用「上移 / 下移」而不是拖拽：IME 窗口又矮又窄，
 * 长按拖拽和候选栏横滑、键区长按抢手势，按钮更可靠（能力不减少）。
 */
@Composable
fun SettingsMenuPanel(uiState: ImeUiState, controller: ImeController) {
    when (uiState.settingsPage) {
        SettingsPage.Menu -> MenuGridPage(controller)
        SettingsPage.SelectInputMode -> SelectInputModePage(controller)
        SettingsPage.Customize -> CustomizePage(controller)
    }
}

@Composable
private fun MenuGridPage(controller: ImeController) {
    val items = remember { loadMenuItems() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items) { _, item ->
            val palette = LocalImePalette.current
            ImeTile(
                iconRes = item.funImgResource,
                label = item.funName,
                palette = palette,
                modifier = Modifier.height(60.dp),
                onClick = {
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate()
                    controller.onMenuAction(item.skbMenuMode)
                },
            )
        }
    }
}

private fun loadMenuItems(): List<SkbFunItem> = runCatching {
    DataBaseKT.instance.skbFunDao().getAllMenu().mapNotNull { item ->
        menuSkbFunsPreset[SkbMenuMode.decode(item.name)]
    }
}.getOrDefault(emptyList())

@Composable
private fun SelectInputModePage(controller: ImeController) {
    val palette = LocalImePalette.current
    val appPrefs = AppPrefs.getInstance()

    val doublePYSchemaName = when (appPrefs.input.doublePYSchemaMode.getValue()) {
        DoublePinyinSchemaMode.flypy -> R.string.double_pinyin_flypy_plus
        DoublePinyinSchemaMode.natural -> R.string.double_pinyin_natural
        DoublePinyinSchemaMode.abc -> R.string.double_pinyin_abc
        DoublePinyinSchemaMode.mspy -> R.string.double_pinyin_mspy
        DoublePinyinSchemaMode.sogou -> R.string.double_pinyin_sougou
        DoublePinyinSchemaMode.ziguang -> R.string.double_pinyin_ziguang
    }

    val items = remember {
        buildList {
            add(Triple(controller.string(R.string.keyboard_name_t9), R.drawable.selece_input_mode_py9, SkbMenuMode.PinyinT9))
            add(Triple(controller.string(R.string.keyboard_name_cn26), R.drawable.selece_input_mode_py26, SkbMenuMode.Pinyin26Jian))
            // 16KB 页设备上手写 .so 无法加载，直接不展示该入口
            if (HWEngine.isAvailable) {
                add(Triple(controller.string(R.string.keyboard_name_hand), R.drawable.selece_input_mode_handwriting, SkbMenuMode.PinyinHandWriting))
            }
            add(Triple(controller.string(doublePYSchemaName), R.drawable.selece_input_mode_dpy26, SkbMenuMode.Pinyin26Double))
            add(Triple(controller.string(R.string.keyboard_name_pinyin_lx_17), R.drawable.selece_input_mode_lx17, SkbMenuMode.PinyinLx17))
            add(Triple(controller.string(R.string.keyboard_name_stroke), R.drawable.selece_input_mode_stroke, SkbMenuMode.PinyinStroke))
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items) { _, (name, icon, mode) ->
            ImeTile(
                iconRes = icon,
                label = name,
                palette = palette,
                modifier = Modifier.height(84.dp),
                iconSize = 32.dp,
                labelSize = 12,
                onClick = {
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate()
                    controller.selectInputMode(mode)
                },
            )
        }
    }
}

@Composable
private fun CustomizePage(controller: ImeController) {
    val palette = LocalImePalette.current
    var items by remember { mutableStateOf(loadMenuItems()) }
    var keepNames by remember { mutableStateOf(loadKeepNames()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items) { index, item ->
            val kept = keepNames.contains(item.skbMenuMode.name)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.card.copy(alpha = 0.9f))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.funName,
                    color = palette.onSurface,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 常驻候选栏（星标）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .imeClickable() {
                            val name = item.skbMenuMode.name
                            keepNames = if (kept) {
                                DataBaseKT.instance.skbFunDao().delete(SkbFun(name = name, isKeep = 1))
                                keepNames - name
                            } else {
                                DataBaseKT.instance.skbFunDao().insert(SkbFun(name = name, isKeep = 1))
                                keepNames + name
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (kept) "★" else "☆",
                        color = if (kept) palette.accent else palette.muted,
                        style = TextStyle(fontSize = 16.sp),
                        textAlign = TextAlign.Center,
                    )
                }
                // 上移 / 下移
                ImeIconButton(
                    iconRes = R.drawable.ic_menu_arrow_up,
                    contentDescription = null,
                    tint = if (index > 0) palette.onSurface else palette.muted.copy(alpha = 0.35f),
                    size = 32.dp,
                    iconSize = 16.dp,
                    onClick = {
                        if (index > 0) {
                            items = items.toMutableList().also { list ->
                                val tmp = list[index - 1]
                                list[index - 1] = list[index]
                                list[index] = tmp
                            }
                            persistOrder(items)
                        }
                    },
                )
                ImeIconButton(
                    iconRes = R.drawable.ic_menu_arrow_down,
                    contentDescription = null,
                    tint = if (index < items.lastIndex) palette.onSurface else palette.muted.copy(alpha = 0.35f),
                    size = 32.dp,
                    iconSize = 16.dp,
                    onClick = {
                        if (index < items.size - 1) {
                            items = items.toMutableList().also { list ->
                                val tmp = list[index + 1]
                                list[index + 1] = list[index]
                                list[index] = tmp
                            }
                            persistOrder(items)
                        }
                    },
                )
            }
        }
        item {
            // 完成：一颗强调色胶囊，和下面的卡片列表拉开层级
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(palette.accent)
                        .imeClickable() { controller.switchToInputKeyboard() }
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = controller.string(R.string.setting_ime_keyboard_height_sure),
                        color = palette.onAccent,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

private fun loadKeepNames(): Set<String> = runCatching {
    DataBaseKT.instance.skbFunDao().getALlBarMenu().map { it.name }.toSet()
}.getOrDefault(emptySet())

private fun persistOrder(items: List<SkbFunItem>) {
    runCatching {
        items.forEachIndexed { index, item ->
            DataBaseKT.instance.skbFunDao().update(
                SkbFun(name = item.skbMenuMode.name, isKeep = 0, position = index)
            )
        }
    }
}
