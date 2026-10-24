package com.yuyan.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils

/**
 * T9 / 17 键 / 数字键左侧、手写右侧的竖排列表。
 *
 * 与旧 `T9TextContainer.updateSymbolListView` 一致：
 * - 有拼音前缀时显示前缀（点选 = 选择拼音）；
 * - 没有前缀时显示用户自定义的侧边符号（点选 = 上屏该符号），底部带「去设置」入口。
 */
@Composable
fun T9PrefixColumn(
    numberMode: Boolean,
    onSelectPrefix: (Int) -> Unit,
    onSymbol: (String) -> Unit,
    onAddSymbol: () -> Unit,
) {
    val theme = LocalImeTheme.current
    val palette = LocalImePalette.current
    val env = remember { EnvironmentSingleton.instance }
    val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
    val textColor = palette.onSurface
    val keyBorder = ThemeManager.prefs.keyBorder.getValue()

    val prefixes = remember(candidateState.prefixRevision) {
        runCatching { DictDecoder.prefixs.toList() }.getOrDefault(emptyList())
    }
    val symbols = remember(numberMode) {
        runCatching {
            if (numberMode) DataBaseKT.instance.sideSymbolDao().getAllSideSymbolNumber()
            else DataBaseKT.instance.sideSymbolDao().getAllSideSymbolPinyin()
        }.getOrDefault(emptyList())
    }
    val isPrefixMode = prefixes.isNotEmpty()
    val listState = rememberLazyListState()
    val radius = ThemeManager.prefs.keyRadius.getValue().toFloat()
    val density = LocalDensity.current
    // keyTextSmallSize 是像素值（与 KeyGrid.pxToSp 同一换算规则）。直接 `.sp` 会被
    // density 再放大一次，符号比旧实现大出整个密度倍数
    val symbolTextSize = remember(env.keyTextSmallSize, density) {
        with(density) { (env.keyTextSmallSize / fontScale).toSp() }
    }

    // 整条侧栏是一块键帽底色（等价旧实现的占位键背景），条目只画文本：
    // 各自再画底色会和键盘键帽叠出双背景，把侧栏啃出毛边
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (keyBorder) Modifier.background(
                    palette.card.copy(alpha = 0.92f),
                    RoundedCornerShape(radius.coerceAtLeast(12f)),
                )
                else Modifier
            )
            // 旧实现整条侧栏是一个吃掉触摸的 RecyclerView：空白处按下不应落到
            // 下面键盘的按键上（会误触上屏/高亮）。子项的 clickable 在 Main pass
            // 先于这里处理，不受影响。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isPrefixMode) {
                itemsIndexed(prefixes) { index, prefix ->
                    SideCell(text = prefix, textColor = textColor, textSize = symbolTextSize) {
                        DevicesUtils.tryPlayKeyDown()
                        DevicesUtils.tryVibrate()
                        onSelectPrefix(index)
                    }
                }
            } else {
                itemsIndexed(symbols) { index, symbol ->
                    SideCell(text = symbol.symbolKey, textColor = textColor, textSize = symbolTextSize) {
                        DevicesUtils.tryPlayKeyDown()
                        DevicesUtils.tryVibrate()
                        onSymbol(symbol.symbolValue)
                    }
                }
                // 「去设置」入口是列表末尾的 footer（旧 addFooterView 同语义）：
                // 拉到列表末尾才看到，而不是固定在底部被滚动的符号盖住
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ImeIconButton(
                            iconRes = R.drawable.ic_menu_setting,
                            contentDescription = null,
                            tint = palette.muted,
                            size = 34.dp,
                            iconSize = 20.dp,
                            onClick = onAddSymbol,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SideCell(
    text: String,
    textColor: Color,
    textSize: TextUnit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .imeClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(fontSize = textSize, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(vertical = 11.dp),
        )
    }
}
