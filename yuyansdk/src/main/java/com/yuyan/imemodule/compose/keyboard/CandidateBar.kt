package com.yuyan.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.ImeUiState
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.inputmethod.RimeEngine

/**
 * 候选栏。
 *
 * 三态互斥，与旧 `CandidatesBar` 一致：
 * 1. **候选态**：上排显示编码，下排候选词（可横滑、滚到尾部自动预取下一页）；
 * 2. **面板态**：符号/表情页、剪贴板页各自暴露自己的菜单项；
 * 3. **空闲态**：显示用户在「定制工具栏」里配置的常驻菜单。
 */
@Composable
fun CandidateBar(
    keyboardType: KeyboardManager.KeyboardType?,
    uiState: ImeUiState,
    controller: ImeController,
    candidateState: DictDecoder.CandidateState,
) {
    val theme = LocalImeTheme.current
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val textColor = Color(theme.keyTextColor)

    val hasContent = candidateState.candidates.isNotEmpty() || candidateState.composingText.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            keyboardType == KeyboardManager.KeyboardType.SYMBOL -> {
                MenuRow(
                    items = listOfNotNull(
                        menuSkbFunsPreset[SkbMenuMode.Emoticon],
                        menuSkbFunsPreset[SkbMenuMode.Emojicon],
                    ),
                    controller = controller,
                )
            }

            keyboardType == KeyboardManager.KeyboardType.ClipBoard -> {
                val items = if (uiState.clipboardMode == SkbMenuMode.ClipBoard) {
                    listOfNotNull(
                        menuSkbFunsPreset[SkbMenuMode.ClearClipBoard],
                        menuSkbFunsPreset[SkbMenuMode.ClipBoard],
                        menuSkbFunsPreset[SkbMenuMode.Phrases],
                        menuSkbFunsPreset[SkbMenuMode.LockClipBoard],
                    )
                } else {
                    listOfNotNull(
                        menuSkbFunsPreset[SkbMenuMode.AddPhrases],
                        menuSkbFunsPreset[SkbMenuMode.ClipBoard],
                        menuSkbFunsPreset[SkbMenuMode.Phrases],
                        menuSkbFunsPreset[SkbMenuMode.LockClipBoard],
                    )
                }
                MenuRow(items = items, controller = controller)
            }

            hasContent -> CandidateRow(candidateState, keyboardType, controller)

            else -> MenuRow(items = rememberBarMenus(controller), controller = controller)
        }

        // 左侧设置菜单入口（候选态与菜单态都在）
        Image(
            painter = painterResource(R.drawable.sdk_level_candidates_menu_left),
            contentDescription = controller.string(R.string.skb_item_settings),
            colorFilter = ColorFilter.tint(textColor),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
                .size(with(density) { (env.heightForCandidatesArea * 0.55f).toDp() })
                .clickable { controller.onMenuAction(SkbMenuMode.SettingsMenu) },
        )
    }
}

@Composable
private fun rememberBarMenus(controller: ImeController): List<SkbFunItem> {
    // 键盘类型变化 / 从定制页返回时重新读一次
    val type = controller.currentKeyboardType
    return remember(type, CustomConstant.lockClipBoardEnable) {
        runCatching {
            DataBaseKT.instance.skbFunDao().getALlBarMenu().mapNotNull { item ->
                val mode = SkbMenuMode.decode(item.name)
                menuSkbFunsPreset[mode]
            }
        }.getOrDefault(emptyList())
    }
}

/** 候选态：编码 + 候选列表 + 右侧箭头 */
@Composable
private fun CandidateRow(
    candidateState: DictDecoder.CandidateState,
    keyboardType: KeyboardManager.KeyboardType?,
    controller: ImeController,
) {
    val theme = LocalImeTheme.current
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val textColor = Color(theme.keyTextColor)
    val accentColor = Color(theme.accentKeyBackgroundColor)
    val listState = rememberLazyListState()

    // 候选换代（新一次输入）时滚回开头，与旧实现 scrollToPosition 一致
    LaunchedEffect(candidateState.generation) {
        if (candidateState.candidates.isNotEmpty()) listState.scrollToItem(0)
    }
    // 滑到尾部附近时预取下一页
    LaunchedEffect(listState.firstVisibleItemIndex, candidateState.candidates.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (keyboardType != KeyboardManager.KeyboardType.CANDIDATES &&
            candidateState.candidates.isNotEmpty() &&
            last >= candidateState.candidates.size - LOAD_MORE_LOOKAHEAD
        ) {
            DictDecoder.nextPageCandidates
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = with(density) { 44.dp })) {
        if (candidateState.composingText.isNotEmpty()) {
            Text(
                text = candidateState.composingText,
                color = textColor,
                style = TextStyle(fontSize = env.composingTextSize.sp),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(candidateState.candidates) { index, item ->
                    val active = controller.state.value.activeCandidate - 1 == index
                    val text: AnnotatedString = remember(item.text, item.comment) {
                        if (item.comment == RimeEngine.CORRECTION_TAG) markCorrection(item.text)
                        else AnnotatedString(item.text)
                    }
                    Text(
                        text = text,
                        color = if (active) accentColor else textColor,
                        style = TextStyle(fontSize = env.candidateTextSize.sp),
                        modifier = Modifier
                            .clickable {
                                DevicesUtils.tryPlayKeyDown()
                                DevicesUtils.tryVibrate()
                                controller.dispatcher.chooseAndUpdate(index)
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            // 右侧箭头：联想 → 关闭；已展开全屏候选 → 收起；否则 → 展开全屏候选
            val levelIcon = when {
                candidateState.isAssociate -> R.drawable.ic_menu_close
                keyboardType == KeyboardManager.KeyboardType.CANDIDATES -> R.drawable.ic_menu_arrow_up
                else -> R.drawable.ic_menu_arrow_down
            }
            Image(
                painter = painterResource(levelIcon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(textColor),
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(with(density) { (env.heightForCandidates * 0.7f).toDp() })
                    .clickable {
                        when {
                            candidateState.isAssociate -> {
                                controller.dispatcher.resetToIdleState()
                                controller.switchToInputKeyboard()
                            }
                            keyboardType == KeyboardManager.KeyboardType.CANDIDATES ->
                                controller.switchToInputKeyboard()

                            else -> controller.onMenuAction(SkbMenuMode.CandidatesMore)
                        }
                    },
            )
        }
    }
}

/** 菜单态：一排水平滚动的功能入口 + 右侧收起键 */
@Composable
private fun MenuRow(items: List<SkbFunItem>, controller: ImeController) {
    val theme = LocalImeTheme.current
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val textColor = Color(theme.keyTextColor)
    val iconSize = with(density) { (env.heightForCandidates * 0.62f).toDp() }
    val menuHeight = with(density) { (env.heightForCandidatesArea * 0.8f).toDp() }

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧被设置入口占用
        Spacer(Modifier.width(with(density) { 44.dp }))
        // 花漾字提示（保持与旧版一致的可见性）
        if (CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled) {
            Text(
                text = flowerTypefaceName(),
                color = textColor,
                style = TextStyle(fontSize = env.candidateTextSize.sp),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        LazyRow(
            modifier = Modifier.weight(1f).height(menuHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items.size) { index ->
                val item = items[index]
                val isLocked = item.skbMenuMode == SkbMenuMode.LockClipBoard && controller.isClipboardLocked
                Box(
                    modifier = Modifier
                        .height(menuHeight)
                        .clickable {
                            DevicesUtils.tryPlayKeyDown()
                            DevicesUtils.tryVibrate()
                            controller.onMenuAction(item.skbMenuMode)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(item.funImgResource),
                        contentDescription = item.funName,
                        colorFilter = ColorFilter.tint(
                            if (isLocked) Color(theme.accentKeyBackgroundColor) else textColor
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp).size(iconSize),
                    )
                }
            }
        }
        Image(
            painter = painterResource(R.drawable.ic_menu_arrow_down),
            contentDescription = controller.string(R.string.keyboard_iv_menu_close),
            colorFilter = ColorFilter.tint(textColor),
            modifier = Modifier
                .padding(end = 10.dp)
                .size(iconSize)
                .clickable { controller.onMenuAction(SkbMenuMode.CloseSKB) },
        )
    }
}

private fun flowerTypefaceName(): String = "焱暒妏"

/** 纠错候选：右上角小上标「?」，与旧 `CandidateMark` 一致 */
private fun markCorrection(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    withStyle(
        SpanStyle(
            fontSize = 9.sp,
            baselineShift = BaselineShift.Superscript,
        )
    ) { append("?") }
}

private const val LOAD_MORE_LOOKAHEAD = 6
