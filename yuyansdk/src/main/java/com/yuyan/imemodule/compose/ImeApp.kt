package com.yuyan.imemodule.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.keyboard.CandidateBar
import com.yuyan.imemodule.compose.keyboard.ClipboardPanel
import com.yuyan.imemodule.compose.keyboard.HandwritingSession
import com.yuyan.imemodule.compose.keyboard.KeyGrid
import com.yuyan.imemodule.compose.keyboard.PhraseEditorBar
import com.yuyan.imemodule.compose.keyboard.SettingsMenuPanel
import com.yuyan.imemodule.compose.keyboard.SymbolPanel
import com.yuyan.imemodule.compose.keyboard.T9PrefixColumn
import com.yuyan.imemodule.compose.keyboard.CandidatesPanel
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.FullDisplayCenterMode
import com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.inputmethod.HWEngine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compose 键盘根内容。
 *
 * 结构（与旧 `sdk_skb_container.xml` 的层级一一对应）：
 * ```
 * Column
 *  ├─ 候选区（候选栏 / 短语编辑栏 / 面板专属菜单）
 *  ├─ 候选区与键盘的间隔（点一下上屏正上方候选）
 *  ├─ 键盘区（KeyGrid / 符号 / 设置 / 剪贴板 / 全屏候选 / 手写）
 *  └─ 底部区（全面屏优化条 + 导航栏占位 / 悬浮键盘移动条）
 * ```
 * 单手模式把整块内容改成 Row：一侧是键盘，另一侧是占位区与切换按钮。
 */
@Composable
fun ImeApp() {
    ImeTheme {
        val controller = LocalImeController.current
        val type by controller.keyboardType.collectAsStateWithLifecycle()
        val uiState by controller.state.collectAsStateWithLifecycle()
        val revision by controller.keyboardRevision.collectAsStateWithLifecycle()
        // KeyboardManager.clearKeyboard() 的失效计数也并进来：设置页/主题/尺寸改动都走它
        val managerRevision by KeyboardManager.instance.revision.collectAsStateWithLifecycle()
        val kbRevision = revision + managerRevision
        val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
        val theme = LocalImeTheme.current
        val env = remember { EnvironmentSingleton.instance }
        val density = LocalDensity.current

        val keyboardSetting = AppPrefs.getInstance().keyboardSetting
        val oneHanded = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedLeft = keyboardSetting.oneHandedMod.getValue() ==
                com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.LEFT
        val floatMode = env.keyboardModeFloat

        val background = rememberKeyboardBackground(
            theme = theme,
            width = env.inputAreaWidth,
            height = env.inputAreaHeight,
            keyBorder = ThemeManager.prefs.keyBorder.getValue(),
        )

        val content: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { env.inputAreaHeight.toDp() }),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { env.heightForCandidatesArea.toDp() }),
                ) {
                    if (uiState.addPhrases) {
                        PhraseEditorBar(controller, uiState)
                    } else {
                        CandidateBar(
                            keyboardType = type,
                            uiState = uiState,
                            controller = controller,
                            candidateState = candidateState,
                        )
                    }
                }
                // 候选栏与键盘之间的间隔：视觉上留白，点击时上屏正上方的候选
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { env.candidatesBottomGap.toDp() })
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) {
                            // 旧实现按横坐标命中候选词本身；Compose 版用「最近一个候选」兜底
                            if (candidateState.candidates.isNotEmpty()) {
                                DevicesUtils.tryVibrate()
                                controller.dispatcher.chooseAndUpdate(0)
                            }
                        },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    KeyboardArea(
                        type = type,
                        uiState = uiState,
                        controller = controller,
                        revision = kbRevision,
                    )
                    if (uiState.heightAdjusterVisible) {
                        HeightAdjuster(controller)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // 键盘背景：优先主题背景图，其次纯色
            if (background != null) {
                Image(
                    bitmap = background,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(theme.keyboardColor)))
            }

            if (floatMode) {
                // 悬浮模式：内容贴右下角，位置由拖动条改写 internal 的 padding 偏好
                val floatBottom = floatBottomPadding(env.isLandscape)
                val floatRight = floatRightPadding(env.isLandscape)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            bottom = with(density) { floatBottom.toDp() },
                            end = with(density) { floatRight.toDp() },
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .width(with(density) { env.skbWidth.toDp() }),
                    ) {
                        content()
                        FloatMoveBar(controller)
                    }
                }
            } else if (oneHanded && !oneHandedLeft) {
                Row(Modifier.fillMaxSize()) {
                    OneHandPlaceholder(controller, leftSide = true)
                    Box(Modifier.weight(1f)) { content() }
                }
            } else if (oneHanded) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { content() }
                    OneHandPlaceholder(controller, leftSide = false)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { content() }
                    KeyboardBottomBar(controller)
                }
            }
        }
    }
}

/** 键盘区：按当前 [KeyboardManager.KeyboardType] 决定画哪一屏 */
@Composable
private fun KeyboardArea(
    type: KeyboardManager.KeyboardType?,
    uiState: ImeUiState,
    controller: ImeController,
    revision: Int,
) {
    if (type == null) return
    when (type) {
        KeyboardManager.KeyboardType.SYMBOL -> SymbolPanel(uiState, controller)
        KeyboardManager.KeyboardType.SETTINGS -> SettingsMenuPanel(uiState, controller)
        KeyboardManager.KeyboardType.ClipBoard -> ClipboardPanel(uiState, controller)
        KeyboardManager.KeyboardType.CANDIDATES -> CandidatesPanel(controller)
        else -> InputKeyboardArea(type, uiState, controller, revision)
    }
}

@Composable
private fun InputKeyboardArea(
    type: KeyboardManager.KeyboardType,
    uiState: ImeUiState,
    controller: ImeController,
    revision: Int,
) {
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val skbValue = type.toSkbValue()
    val keyboard = remember(skbValue, env.skbWidth, env.skbHeight, revision) {
        runCatching { KeyboardLoaderUtil.instance.getSoftKeyboard(skbValue) }.getOrNull()
    } ?: return

    val dispatcher = controller.dispatcher

    val handwriting = remember(type) {
        if (type == KeyboardManager.KeyboardType.HANDWRITING) {
            HandwritingSession(
                onRecognize = { points ->
                    if (HWEngine.isAvailable) {
                        HWEngine.recognitionData(points) { items ->
                            dispatcher.responseHandwritingResultEvent(items)
                        }
                    }
                },
                onCommitCurrentCandidate = {
                    // 停笔超时后再落笔 = 确认当前高亮候选
                    dispatcher.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_SELECTED))
                },
            )
        } else null
    }

    Box(Modifier.fillMaxSize()) {
        KeyGrid(
            keyboard = keyboard,
            modifier = Modifier.fillMaxSize(),
            revision = revision,
            addPhrases = uiState.addPhrases,
            handwriting = handwriting,
            onKeyTap = { key ->
                dispatcher.responseKeyEvent(key)
            },
            onLongResult = { result -> dispatcher.responseLongKeyEvent(result) },
            onKeyFeedback = { key ->
                DevicesUtils.tryPlayKeyDown(key.code)
                DevicesUtils.tryVibrate()
            },
            onRepeat = { key, dx, dy, longPressed -> dispatchRepeat(dispatcher, key, dx, dy, longPressed) },
        )

        // T9 家族左侧符号/拼音栏、手写右侧符号栏
        val isT9Family = type == KeyboardManager.KeyboardType.T9 ||
                type == KeyboardManager.KeyboardType.LX17 ||
                type == KeyboardManager.KeyboardType.NUMBER
        val isHandwriting = type == KeyboardManager.KeyboardType.HANDWRITING
        if (isT9Family || isHandwriting) {
            val holder = keyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL)
            if (holder != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = with(density) { holder.mLeft.toDp() },
                            y = with(density) { holder.mTop.toDp() },
                        )
                        .width(with(density) { holder.width().toDp() })
                        .height(with(density) { (holder.mBottom - holder.mTop).toDp() }),
                ) {
                    T9PrefixColumn(
                        numberMode = type == KeyboardManager.KeyboardType.NUMBER,
                        onSelectPrefix = { position -> dispatcher.selectPrefix(position) },
                        onSymbol = { symbol -> dispatcher.responseKeyEvent(SoftKey(label = symbol)) },
                        onAddSymbol = {
                            val args = android.os.Bundle()
                            args.putInt("type", if (type == KeyboardManager.KeyboardType.NUMBER) 1 else 0)
                            com.yuyan.imemodule.utils.AppUtil.launchSettingsToPrefix(
                                com.yuyan.imemodule.application.Launcher.instance.context, args
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 长按连发：数值与旧 `BaseKeyboardView.repeatKey` 一致 */
private fun dispatchRepeat(
    dispatcher: KeyDispatcher,
    key: SoftKey,
    dx: Float,
    dy: Float,
    longPressed: Boolean,
) {
    if (key.code == android.view.KeyEvent.KEYCODE_DEL && longPressed) {
        if (abs(dx) >= abs(dy)) {
            if (dx >= -2) dispatcher.responseKeyEvent(SoftKey(android.view.KeyEvent.KEYCODE_DEL))
            else dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Revertl, ""))
        } else {
            if (dy > 0) dispatcher.responseKeyEvent(SoftKey(android.view.KeyEvent.KEYCODE_DEL))
            else if (dy < 0) dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Revertl, ""))
        }
        return
    }
    if (key.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION) {
        val code = if (abs(dx) >= abs(dy)) {
            if (dx > 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            if (dy < 0) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP
        }
        dispatcher.responseKeyEvent(SoftKey(code))
        return
    }
    dispatcher.responseKeyEvent(key)
}

/** 底部：全面屏键盘优化条 + 导航栏占位 */
@Composable
private fun KeyboardBottomBar(controller: ImeController) {
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val internal = AppPrefs.getInstance().internal
    val fullDisplay = internal.fullDisplayKeyboardEnable.getValue()
    val bottomPadding = internal.keyboardBottomPadding.getValue()
    val navbar = env.systemNavbarWindowsBottom
    val barHeight = if (fullDisplay && !env.isLandscape) env.heightForFullDisplayBar else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { (navbar + bottomPadding + barHeight).toDp() }),
    ) {
        if (barHeight > 0) {
            FullDisplayBar(controller)
        }
        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun FullDisplayBar(controller: ImeController) {
    val env = remember { EnvironmentSingleton.instance }
    val theme = LocalImeTheme.current
    val density = LocalDensity.current
    val internal = AppPrefs.getInstance().internal
    val leftMode = FullDisplayKeyMode.decode(internal.fullDisplayKeyModeLeft.getValue())
    val rightMode = FullDisplayKeyMode.decode(internal.fullDisplayKeyModeRight.getValue())
    val centerMove = FullDisplayCenterMode.decode(internal.fullDisplayCenterMode.getValue()) ==
            FullDisplayCenterMode.MoveCursor
    var lastX by remember { mutableFloatStateOf(-1f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { env.heightForFullDisplayBar.toDp() })
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FullDisplayIcon(leftMode, tint = Color(theme.keyTextColor)) { controller.onFullDisplayAction(leftMode) }
        // 中间区域：左右滑动移动光标
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(centerMove) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { lastX = -1f },
                        onDrag = { change, _ ->
                            if (!centerMove) return@detectDragGestures
                            val x = change.position.x
                            if (lastX >= 0) {
                                val diff = x - lastX
                                if (abs(diff) > GestureTuning.spaceSwipeSpeed) {
                                    controller.dispatcher.responseKeyEvent(
                                        SoftKey(if (diff < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                        else android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                                    )
                                    lastX = x
                                }
                            } else {
                                lastX = x
                            }
                        },
                    )
                },
        )
        FullDisplayIcon(rightMode, tint = Color(theme.keyTextColor)) { controller.onFullDisplayAction(rightMode) }
    }
}

@Composable
private fun FullDisplayIcon(mode: FullDisplayKeyMode, tint: Color, onClick: () -> Unit) {
    val res = when (mode) {
        FullDisplayKeyMode.SwitchIme -> R.drawable.ic_menu_language
        FullDisplayKeyMode.SwitchLanguage -> R.drawable.ic_menu_keyboard_switcher
        FullDisplayKeyMode.Clipboard -> R.drawable.ic_menu_clipboard
        FullDisplayKeyMode.Phrases -> R.drawable.ic_menu_phrases
        else -> 0
    }
    if (res == 0) {
        Spacer(Modifier.width(0.dp))
        return
    }
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
        modifier = Modifier.size(28.dp).clickable { onClick() },
    )
}

/** 单手模式的占位区 + 两个切换按钮（原 `sdk_skb_holder_*.xml`） */
@Composable
private fun OneHandPlaceholder(controller: ImeController, leftSide: Boolean) {
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val theme = LocalImeTheme.current

    Column(
        modifier = Modifier
            .width(with(density) { env.holderWidth.toDp() })
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_menu_one_hand),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(theme.keyTextColor)),
                modifier = Modifier.size(32.dp).clickable { toggleOneHanded() },
            )
            Image(
                painter = painterResource(
                    if (controller.state.value.oneHandedLeft) R.drawable.ic_menu_one_hand_right
                    else R.drawable.ic_menu_one_hand
                ),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(theme.keyTextColor)),
                modifier = Modifier.size(32.dp).clickable { switchOneHandedSide(controller) },
            )
        }
        Spacer(Modifier.height(with(density) { env.systemNavbarWindowsBottom.toDp() }))
    }
}

private fun toggleOneHanded() {
    val pref = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch
    pref.setValue(!pref.getValue())
    EnvironmentSingleton.instance.initData()
    KeyboardLoaderUtil.instance.clearKeyboardMap()
    KeyboardManager.instance.clearKeyboard()
    KeyboardManager.instance.switchKeyboard()
}

private fun switchOneHandedSide(controller: ImeController) {
    val pref = AppPrefs.getInstance().keyboardSetting.oneHandedMod
    val next = if (pref.getValue() == com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.LEFT)
        com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.RIGHT
    else com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.LEFT
    pref.setValue(next)
    controller.update { it.copy(oneHandedLeft = next == com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.LEFT) }
    controller.refreshEnvironment()
}

/** 悬浮键盘的拖动条 */
@Composable
private fun FloatMoveBar(controller: ImeController) {
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val theme = LocalImeTheme.current
    var startX by remember { mutableFloatStateOf(0f) }
    var startY by remember { mutableFloatStateOf(0f) }
    var startRight by remember { mutableFloatStateOf(0f) }
    var startBottom by remember { mutableFloatStateOf(0f) }
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { env.heightForKeyboardMove.toDp() })
            .pointerInput(env.isLandscape) {
                detectDragGestures(
                    onDragStart = {
                        startRight = floatRightPadding(env.isLandscape).toFloat()
                        startBottom = floatBottomPadding(env.isLandscape).toFloat()
                        accX = 0f
                        accY = 0f
                    },
                    onDragEnd = {
                        val internal = AppPrefs.getInstance().internal
                        val maxRight = (size.width - env.skbWidth).coerceAtLeast(0).toFloat()
                        val maxBottom = (size.height - env.inputAreaHeight).coerceAtLeast(0).toFloat()
                        val finalRight = (startRight - accX).coerceIn(0f, maxRight)
                        val finalBottom = (startBottom - accY).coerceIn(0f, maxBottom)
                        if (env.isLandscape) {
                            internal.keyboardRightPaddingLandscapeFloat.setValue(finalRight.roundToInt())
                            internal.keyboardBottomPaddingLandscapeFloat.setValue(finalBottom.roundToInt())
                        } else {
                            internal.keyboardRightPaddingFloat.setValue(finalRight.roundToInt())
                            internal.keyboardBottomPaddingFloat.setValue(finalBottom.roundToInt())
                        }
                        controller.bumpKeyboardRevision()
                    },
                    onDrag = { _, drag ->
                        accX += drag.x
                        accY += drag.y
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_horizontal_line),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(theme.keyTextColor)),
            modifier = Modifier.size(width = 40.dp, height = 12.dp),
        )
    }
}

/** 键盘高度调整浮层（键盘菜单 →「键盘高度」） */
@Composable
private fun HeightAdjuster(controller: ImeController) {
    val env = remember { EnvironmentSingleton.instance }
    val theme = LocalImeTheme.current
    var ratio by remember { mutableFloatStateOf(env.keyBoardHeightRatio) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(theme.keyboardColor).copy(alpha = 0.92f)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部拖动条：上下拖动 10dp 改 1%
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { accumulated = 0f },
                            onDrag = { _, drag ->
                                accumulated -= drag.y
                                val step = 10f * density
                                if (abs(accumulated) >= step) {
                                    val delta = (accumulated / step).toInt() * 0.01f
                                    accumulated -= delta / 0.01f * step
                                    ratio = (ratio + delta).coerceIn(0.15f, 0.6f)
                                    env.keyBoardHeightRatio = ratio
                                    env.initData()
                                    KeyboardLoaderUtil.instance.clearKeyboardMap()
                                    KeyboardManager.instance.clearKeyboard()
                                    controller.bumpKeyboardRevision()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_horizontal_line),
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(theme.keyTextColor)),
                    modifier = Modifier.size(width = 64.dp, height = 16.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = controller.string(R.string.setting_ime_keyboard_height_reset),
                    color = Color(theme.keyTextColor),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.clickable {
                        env.keyBoardHeightRatio = 0.3f
                        env.initData()
                        KeyboardLoaderUtil.instance.clearKeyboardMap()
                        KeyboardManager.instance.clearKeyboard()
                        controller.bumpKeyboardRevision()
                    },
                )
                Text(
                    text = controller.string(R.string.setting_ime_keyboard_height_sure),
                    color = Color(theme.keyTextColor),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.clickable { controller.hideHeightAdjuster() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 主题背景
// ---------------------------------------------------------------------------

@Composable
private fun rememberKeyboardBackground(
    theme: Theme,
    width: Int,
    height: Int,
    keyBorder: Boolean,
): ImageBitmap? = remember(theme, width, height, keyBorder) {
    if (width <= 0 || height <= 0) return@remember null
    runCatching {
        val drawable = theme.backgroundDrawable(keyBorder)
        drawable.toSizedImageBitmap(width, height)
    }.getOrNull()
}

private fun Drawable.toSizedImageBitmap(width: Int, height: Int): ImageBitmap? = runCatching {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

private fun floatBottomPadding(landscape: Boolean): Int {
    val internal = AppPrefs.getInstance().internal
    return if (landscape) internal.keyboardBottomPaddingLandscapeFloat.getValue()
    else internal.keyboardBottomPaddingFloat.getValue()
}

private fun floatRightPadding(landscape: Boolean): Int {
    val internal = AppPrefs.getInstance().internal
    return if (landscape) internal.keyboardRightPaddingLandscapeFloat.getValue()
    else internal.keyboardRightPaddingFloat.getValue()
}

private fun KeyboardManager.KeyboardType.toSkbValue(): Int = when (this) {
    KeyboardManager.KeyboardType.QWERTY -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN
    KeyboardManager.KeyboardType.QWERTYABC -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC
    KeyboardManager.KeyboardType.T9 -> InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN
    KeyboardManager.KeyboardType.NUMBER -> InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER
    KeyboardManager.KeyboardType.LX17 -> InputModeSwitcher.MASK_SKB_LAYOUT_LX17
    KeyboardManager.KeyboardType.TEXTEDIT -> InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT
    KeyboardManager.KeyboardType.HANDWRITING -> InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING
    else -> AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT
}
