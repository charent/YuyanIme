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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.ImeUiState
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.compose.SettingsPage
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.inputmethod.HWEngine

/**
 * SETTINGS 容器：功能菜单宫格 / 输入方式选择 / 菜单项定制。
 *
 * 旧实现用 `RecyclerView + ItemTouchHelper` 做拖拽排序，Compose 版改成
 * 「上移 / 下移」按钮 + 星标切换常驻——拖拽需要 `detectDragGesturesAfterLongPress`
 * 配合列表位移计算，在 IME 这种窄而矮的容器里手势冲突明显，按钮更可靠。
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
    val theme = LocalImeTheme.current
    val env = remember { EnvironmentSingleton.instance }
    val textColor = Color(theme.keyTextColor)
    var items by remember { mutableStateOf(loadMenuItems()) }

    LazyVerticalGrid(
        columns = GridCells.Fixed((env.skbWidth / 100).coerceAtLeast(3)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        items(items) { item ->
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .clickable {
                        DevicesUtils.tryPlayKeyDown()
                        DevicesUtils.tryVibrate()
                        controller.onMenuAction(item.skbMenuMode)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(item.funImgResource),
                    contentDescription = item.funName,
                    colorFilter = ColorFilter.tint(textColor),
                    modifier = Modifier.size(34.dp),
                )
                Text(
                    text = item.funName,
                    color = textColor,
                    style = TextStyle(fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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
    val theme = LocalImeTheme.current
    val textColor = Color(theme.keyTextColor)
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(items) { (name, icon, mode) ->
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .clickable {
                        DevicesUtils.tryPlayKeyDown()
                        DevicesUtils.tryVibrate()
                        controller.selectInputMode(mode)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(icon),
                    contentDescription = name,
                    colorFilter = ColorFilter.tint(textColor),
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = name,
                    color = textColor,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CustomizePage(controller: ImeController) {
    val theme = LocalImeTheme.current
    val textColor = Color(theme.keyTextColor)
    var items by remember { mutableStateOf(loadMenuItems()) }
    var keepNames by remember { mutableStateOf(loadKeepNames()) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.funName,
                    color = textColor,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                // 常驻候选栏开关
                Text(
                    text = if (keepNames.contains(item.skbMenuMode.name)) "★" else "☆",
                    color = if (keepNames.contains(item.skbMenuMode.name)) Color(theme.accentKeyBackgroundColor) else textColor,
                    style = TextStyle(fontSize = 18.sp),
                    modifier = Modifier.padding(horizontal = 8.dp).clickable {
                        val name = item.skbMenuMode.name
                        keepNames = if (keepNames.contains(name)) {
                            DataBaseKT.instance.skbFunDao().delete(SkbFun(name = name, isKeep = 1))
                            keepNames - name
                        } else {
                            DataBaseKT.instance.skbFunDao().insert(SkbFun(name = name, isKeep = 1))
                            keepNames + name
                        }
                    },
                )
                // 上移 / 下移
                Text(
                    text = "↑",
                    color = textColor,
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier.padding(horizontal = 8.dp).clickable {
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
                Text(
                    text = "↓",
                    color = textColor,
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier.padding(horizontal = 8.dp).clickable {
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
            Box(
                modifier = Modifier.fillMaxWidth().height(40.dp).clickable { controller.switchToInputKeyboard() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = controller.string(R.string.setting_ime_keyboard_height_sure),
                    color = textColor,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
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
