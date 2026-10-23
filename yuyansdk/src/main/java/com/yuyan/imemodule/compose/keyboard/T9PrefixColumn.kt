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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    val env = remember { EnvironmentSingleton.instance }
    val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
    val textColor = Color(theme.keyTextColor)
    val keyBackground = Color(theme.keyBackgroundColor)
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

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (isPrefixMode) {
                itemsIndexed(prefixes) { index, prefix ->
                    SideCell(text = prefix, textColor = textColor, background = keyBackground, bordered = keyBorder) {
                        DevicesUtils.tryPlayKeyDown()
                        DevicesUtils.tryVibrate()
                        onSelectPrefix(index)
                    }
                }
            } else {
                itemsIndexed(symbols) { index, symbol ->
                    SideCell(
                        text = symbol.symbolKey,
                        textColor = textColor,
                        background = keyBackground,
                        bordered = keyBorder,
                    ) {
                        DevicesUtils.tryPlayKeyDown()
                        DevicesUtils.tryVibrate()
                        onSymbol(symbol.symbolValue)
                    }
                }
            }
        }
        if (!isPrefixMode) {
            // 底部入口：去「侧边符号」设置页
            Box(
                modifier = Modifier.fillMaxWidth().padding(4.dp).clickable { onAddSymbol() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_menu_setting),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(textColor),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun SideCell(
    text: String,
    textColor: Color,
    background: Color,
    bordered: Boolean,
    onClick: () -> Unit,
) {
    val env = remember { EnvironmentSingleton.instance }
    val radius = ThemeManager.prefs.keyRadius.getValue().toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (bordered) Modifier.background(background, RoundedCornerShape(radius))
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(fontSize = env.keyTextSmallSize.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}
