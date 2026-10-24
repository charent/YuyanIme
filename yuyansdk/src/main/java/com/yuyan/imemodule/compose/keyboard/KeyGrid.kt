package com.yuyan.imemodule.compose.keyboard

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.GestureTuning
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.service.InputDispatcher
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 键帽绘制 + 手势。
 *
 * 设计要点（对应方案 §2.2 的硬性规则）：
 * - **数据层零改动**：布局仍由 `KeyboardLoaderUtil` / `SoftKeyboard` / `SoftKey` 产出
 *   （百分比几何已由 `setSkbCoreSize` 换算成像素），这里只负责渲染与命中；
 * - **命中测试复用 [SoftKeyboard.mapToKey]**：把指针坐标换算回 skb 坐标系后直接调用，
 *   与旧实现判定逻辑完全一致，不引入第二套几何；
 * - **按压态 deferred read**：按压索引、弹层、气泡都只存在 `MutableState`，
 *   且在 `onDrawBehind` 内读取，按下只触发重绘、不触发重组；
 * - **文本测量进缓存块**：`TextLayoutResult` 在 `drawWithCache` 的缓存块里测量，
 *   几何/主题不变时不会重复 measure；`rememberTextMeasurer(cacheSize = 64)` 再做一层缓存。
 *
 * 手势顺序与旧 `BaseKeyboardView` 对齐：
 * 按下（气泡/震动/长按计时）→ 滑动（上滑小标点 / 左滑清空 / 空格移光标 / 弹层选字）
 * → 抬起（点击 或 弹层选中项 或 菜单动作）。
 */
@Composable
fun KeyGrid(
    keyboard: SoftKeyboard,
    modifier: Modifier = Modifier,
    /** 键帽状态版本（shift/enter/选区），变化时重建绘制缓存 */
    revision: Int = 0,
    /** 短语编辑态：enter 键切到「完成」图标 */
    addPhrases: Boolean = false,
    /** 手写会话：非空时，按在空白区的手势用于采集笔迹 */
    handwriting: HandwritingSession? = null,
    onKeyTap: (SoftKey) -> Unit = {},
    onLongResult: (Pair<PopupMenuMode, String>) -> Unit = {},
    onKeyFeedback: (SoftKey) -> Unit = {},
    onRepeat: (key: SoftKey, dx: Float, dy: Float, longPressed: Boolean) -> Unit = { _, _, _, _ -> },
) {
    val theme = LocalImeTheme.current
    val env = remember { EnvironmentSingleton.instance }
    val keys = remember(keyboard) { keyboard.mKeyRows.flatten() }
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)

    val pressState = remember(keyboard) { mutableStateOf(-1) }
    val popupState = remember(keyboard) { mutableStateOf<KeyPopup?>(null) }
    val balloonState = remember(keyboard) { mutableStateOf<KeyBalloon?>(null) }

    val skbWidth = env.skbWidth
    val skbHeight = env.skbHeight
    val keyTextSizePx = env.keyTextSize
    val smallTextSizePx = env.keyTextSmallSize

    // 键帽可切换状态（shift / enter）必须在绘制前落到 SoftKeyToggle 上，
    // 否则图标与文字仍是上一轮的状态
    applyToggleStates(keyboard, addPhrases)

    val controller = remember(keyboard, skbWidth, skbHeight) {
        KeyTouchController(
            keyboard = keyboard,
            skbWidth = skbWidth,
            skbHeight = skbHeight,
            pressState = pressState,
            popupState = popupState,
            balloonState = balloonState,
        )
    }
    // 每次组合都刷新回调，避免用 remember 捕获到旧的 lambda
    controller.onKeyFeedback = onKeyFeedback
    controller.onKeyTap = onKeyTap
    controller.onLongResult = onLongResult
    controller.onRepeat = onRepeat
    controller.handwriting = handwriting
    controller.addPhrases = addPhrases

    Spacer(
        modifier = modifier
            .pointerInput(keyboard, skbWidth, skbHeight, handwriting, addPhrases, revision) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!controller.onDown(down.position, size.width, size.height)) {
                        // 按在空白区：手写模式开始采笔迹，否则等抬起后丢弃
                        if (handwriting == null) {
                            waitForUpOrCancellationCompat()
                            return@awaitEachGesture
                        }
                        controller.onStrokeDown(down.position)
                        var change: PointerInputChange? = down
                        while (change != null) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.pressed) {
                                controller.onStrokeMove(pointer.position)
                                change = pointer
                                // 手写采集期间不要把事件让给上层父容器（ImeService 窗口）
                                pointer.consume()
                            } else {
                                controller.onStrokeUp(pointer.position)
                                change = null
                            }
                        }
                        if (controller.isStroking) controller.onStrokeUp(controller.lastStrokePoint)
                        return@awaitEachGesture
                    }

                    var up: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed) {
                            controller.onMove(pointer.position)
                        } else {
                            up = pointer
                            controller.onMove(pointer.position)
                            break
                        }
                    }
                    controller.onUp(up?.position ?: controller.lastPoint)
                }
            }
            .drawWithCache {
                val scaleX = if (skbWidth > 0) size.width / skbWidth else 1f
                val scaleY = if (skbHeight > 0) size.height / skbHeight else 1f
                val painter = KeyPainter(
                    theme = theme,
                    textMeasurer = textMeasurer,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    keyTextSizePx = keyTextSizePx,
                    smallTextSizePx = smallTextSizePx,
                    density = density,
                    fontScale = fontScale,
                )
                val rendered = painter.buildKeys(keyboard, keys)
                val strokeColor = Color(theme.keyTextColor)

                onDrawBehind {
                    with(painter) {
                        rendered.forEachIndexed { index, r ->
                            drawKey(r, pressState.value == index)
                        }
                    }
                    // 笔迹画在按键之上、弹层之下
                    handwriting?.let { session ->
                        drawHandwritingStrokes(session.allStrokes(), session::strokeWidthFor, strokeColor)
                    }
                    with(painter) {
                        balloonState.value?.let { drawBalloon(it) }
                        popupState.value?.let { drawPopup(it) }
                    }
                }
            }
    )
}

// ---------------------------------------------------------------------------
// 可切换状态
// ---------------------------------------------------------------------------

/**
 * 把 shift / enter 的当前状态写回 [SoftKeyToggle]。
 *
 * 这一步是「可变」的：`SoftKeyToggle.keyLabel/keyIcon` 完全由 `stateId` 决定，
 * 不刷新就会出现「切了大写但键帽还是小写」。
 */
private fun applyToggleStates(keyboard: SoftKeyboard, addPhrases: Boolean) {
    val enter = keyboard.getKeyByCode(KeyEvent.KEYCODE_ENTER) as? SoftKeyToggle
    enter?.enableToggleState(if (addPhrases) 4 else InputModeSwitcher.mToggleStates.imeAction)
    val shift = keyboard.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT) as? SoftKeyToggle
    val isEnglishCell = AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()
    shift?.enableToggleState(InputModeSwitcher.mToggleStates.modifiers + if (isEnglishCell) 3 else 0)
}

// ---------------------------------------------------------------------------
// 弹层 / 气泡的模型
// ---------------------------------------------------------------------------

/** 长按弹层：一格里一个候选字符，拖着选、松手上屏 */
internal data class KeyPopup(
    val keys: List<String>,
    val focus: Int,
    val rect: androidx.compose.ui.geometry.Rect,
    val cellWidth: Float,
    val cellHeight: Float,
    val isMenu: Boolean,
    val selected: Boolean,
    val menuPair: Pair<PopupMenuMode, String>?,
    val columns: Int,
    val rows: Int,
    /** Clear 菜单用红色高亮，与旧实现一致 */
    val clearMenu: Boolean = false,
)

/** 按键气泡（短按预览） */
internal data class KeyBalloon(
    val text: String,
    val rect: androidx.compose.ui.geometry.Rect,
)

// ---------------------------------------------------------------------------
// 手势
// ---------------------------------------------------------------------------

private class KeyTouchController(
    private val keyboard: SoftKeyboard,
    private val skbWidth: Int,
    private val skbHeight: Int,
    private val pressState: MutableState<Int>,
    private val popupState: MutableState<KeyPopup?>,
    private val balloonState: MutableState<KeyBalloon?>,
) {
    var onKeyTap: (SoftKey) -> Unit = {}
    var onKeyFeedback: (SoftKey) -> Unit = {}
    var onLongResult: (Pair<PopupMenuMode, String>) -> Unit = {}
    var onRepeat: (key: SoftKey, dx: Float, dy: Float, longPressed: Boolean) -> Unit = { _, _, _, _ -> }
    var handwriting: HandwritingSession? = null
    var addPhrases: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val keys = keyboard.mKeyRows.flatten()

    private var currentKey: SoftKey? = null
    private var longPressed = false
    private var abortKey = false
    private var committed = false
    private var viewWidth = 0
    private var viewHeight = 0

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var deltaX = 0f
    private var deltaY = 0f
    private var lastEventX = -1f
    private var lastEventY = -1f
    private var lastEventTime = 0L

    var lastPoint: Offset = Offset.Zero
        private set

    var isStroking = false
        private set
    var lastStrokePoint: Offset = Offset.Zero
        private set

    private val longPressRunnable = Runnable {
        currentKey?.let { openPopupIfRequired(it) }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = currentKey
            if (key == null || !key.repeatable() || committed) return
            onRepeat(key, deltaX, deltaY, longPressed)
            handler.postDelayed(this, GestureTuning.REPEAT_INTERVAL_MS)
        }
    }

    private fun toSkb(x: Float, y: Float): Pair<Int, Int> {
        if (viewWidth <= 0 || viewHeight <= 0) return 0 to 0
        return (x * skbWidth / viewWidth).toInt() to (y * skbHeight / viewHeight).toInt()
    }

    fun hitTest(x: Float, y: Float): SoftKey? {
        val (sx, sy) = toSkb(x, y)
        return keyboard.mapToKey(sx, sy)
    }

    /** @return false 表示按在空白区（手写采集或直接丢弃） */
    fun onDown(position: Offset, width: Int, height: Int): Boolean {
        viewWidth = width
        viewHeight = height
        lastPoint = position
        lastX = position.x
        lastY = position.y
        downX = position.x
        downY = position.y
        deltaX = 0f
        deltaY = 0f
        lastEventX = -1f
        lastEventY = -1f
        lastEventTime = 0L
        longPressed = false
        abortKey = false
        committed = false

        val key = hitTest(position.x, position.y)
        currentKey = key
        if (key == null) {
            pressState.value = -1
            balloonState.value = null
            popupState.value = null
            return false
        }
        pressState.value = keys.indexOfFirst { it === key }
        onKeyFeedback(key)
        showBalloon(key)
        handler.postDelayed(longPressRunnable, GestureTuning.longPressTimeoutMs)
        if (key.repeatable()) {
            handler.postDelayed(repeatRunnable, GestureTuning.REPEAT_START_DELAY_MS)
        }
        return true
    }

    fun onMove(position: Offset) {
        lastPoint = position
        val key = currentKey ?: return
        val dx = lastX - position.x   // 正数 = 手指向左
        val dy = lastY - position.y   // 正数 = 手指向上
        val relDiffX = abs(position.x - lastX)
        val relDiffY = abs(position.y - lastY)

        // 弹层已弹出的情况：只做「选字」，不再判定其它手势
        popupState.value?.let { popup ->
            popupState.value = updatePopupFocus(popup, position)
            lastX = position.x
            lastY = position.y
            return
        }

        if (lastEventX < 0f) {
            lastEventX = position.x
            lastEventY = position.y
            lastX = position.x
            lastY = position.y
            return
        }

        val isVertical = relDiffX * GestureTuning.VERTICAL_DOMINANCE < relDiffY
        val symbolSlideUp = GestureTuning.symbolSlideUpThreshold
        val smallLabel = key.getmKeyLabelSmall()

        if (!isVertical && relDiffX > GestureTuning.spaceSwipeSpeed) {
            // 左右滑动
            val isSwipeKey = key.code == KeyEvent.KEYCODE_SPACE || key.code == KeyEvent.KEYCODE_0
            if (key.code == KeyEvent.KEYCODE_DEL && dx > GestureTuning.DELETE_SWIPE_MIN_PX) {
                // 左滑删除 = 清空
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatRunnable)
                abortKey = true
                committed = true
                onKeyTap(SoftKey(KeyEvent.KEYCODE_CLEAR))
            } else if (isSwipeKey && GestureTuning.spaceSwipeMoveCursor) {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatRunnable)
                abortKey = true
                committed = true
                onKeyTap(
                    SoftKey(code = if (dx > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                )
            }
        } else if (!TextUtils.isEmpty(smallLabel)) {
            if (isVertical && dy > 0 && relDiffY > symbolSlideUp && GestureTuning.symbolSlideUpEnabled) {
                // 上滑上屏小标点
                lastEventX = position.x
                lastEventY = position.y
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatRunnable)
                longPressed = true
                abortKey = true
                committed = true
                onLongResult(Pair(PopupMenuMode.Text, smallLabel))
            }
        } else {
            // 无小标点的键：上滑进入菜单弹层（需要在弹层弹出后继续滑动来选中）
            if (isVertical && relDiffY > symbolSlideUp * 2) {
                if (popupState.value == null) openMenuPopup(key, selected = true)
            }
        }

        deltaX = dx
        deltaY = dy
        lastEventX = position.x
        lastEventY = position.y
        lastX = position.x
        lastY = position.y
    }

    fun onUp(position: Offset) {
        lastPoint = position
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        balloonState.value = null
        val key = currentKey
        pressState.value = -1
        currentKey = null

        val popup = popupState.value
        popupState.value = null
        if (popup != null) {
            // 菜单弹层：只有「上滑选中」才触发，与旧实现 isSelect 语义一致
            if (popup.isMenu) {
                if (popup.selected && popup.menuPair != null) onLongResult(popup.menuPair)
            } else {
                val value = popup.keys.getOrNull(popup.focus)
                if (!value.isNullOrEmpty()) onLongResult(Pair(PopupMenuMode.Text, value))
            }
            return
        }
        if (key == null || abortKey || longPressed || committed) return
        // 抬手仍在同一键内才算点击
        val released = hitTest(position.x, position.y)
        if (released === key) onKeyTap(key)
    }

    fun onCancel() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        balloonState.value = null
        popupState.value = null
        pressState.value = -1
        currentKey = null
    }

    // ------------------------------------------------------------------
    // 手写
    // ------------------------------------------------------------------

    fun onStrokeDown(position: Offset) {
        isStroking = true
        lastStrokePoint = position
        handwriting?.onDown(position)
    }

    fun onStrokeMove(position: Offset) {
        lastStrokePoint = position
        handwriting?.onMove(position)
    }

    fun onStrokeUp(position: Offset) {
        lastStrokePoint = position
        isStroking = false
        handwriting?.onUp(position)
    }

    // ------------------------------------------------------------------
    // 弹层
    // ------------------------------------------------------------------

    private fun showBalloon(key: SoftKey) {
        if (!GestureTuning.balloonEnabled) return
        if (TextUtils.isEmpty(key.getkeyLabel())) return
        val r = keyRectInView(key)
        balloonState.value = KeyBalloon(
            text = key.getkeyLabel(),
            rect = androidx.compose.ui.geometry.Rect(
                r.left,
                r.top - r.height,
                r.right,
                r.top,
            ),
        )
    }

    private fun keyRectInView(key: SoftKey): androidx.compose.ui.geometry.Rect {
        val sx = if (skbWidth > 0) viewWidth.toFloat() / skbWidth else 1f
        val sy = if (skbHeight > 0) viewHeight.toFloat() / skbHeight else 1f
        return androidx.compose.ui.geometry.Rect(
            key.mLeft * sx, key.mTop * sy, key.mRight * sx, key.mBottom * sy
        )
    }

    /**
     * 长按触发点。判定顺序对齐旧 `BaseKeyboardView.openPopupIfRequired`：
     * 有 label 的普通键 → 选字弹层；特定功能键 → 菜单弹层；其它 → 直接取消本次点击。
     */
    private fun openPopupIfRequired(key: SoftKey) {
        longPressed = true
        handler.removeCallbacks(repeatRunnable)
        val symbolEnabled = ThemeManager.prefs.keyboardSymbol.getValue()
        val label = key.keyLabel.ifEmpty { "" }
        if (label.isNotBlank() && key.code != InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI) {
            val keyLabel = if (InputModeSwitcher.isLower) key.keyLabel.lowercase() else key.keyLabel
            val designPreset = setOf("，", "。", ",", ".")
            val smallLabel = if (designPreset.any { it == keyLabel } || !symbolEnabled) "" else key.getmKeyLabelSmall()
            val candidates = buildTextPopupKeys(keyLabel, smallLabel)
            if (candidates.isEmpty()) {
                popupState.value = null
                balloonState.value = null
                return
            }
            balloonState.value = null
            popupState.value = buildPopup(key, candidates, isMenu = false, selected = true, menuPair = null)
            return
        }
        if (key.code == InputModeSwitcher.USER_KEYCODE_LANG ||
            key.code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
            key.code == KeyEvent.KEYCODE_SHIFT_LEFT ||
            key.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION ||
            key.code == KeyEvent.KEYCODE_DEL ||
            key.code == KeyEvent.KEYCODE_ENTER
        ) {
            openMenuPopup(key, selected = false)
            return
        }
        // 其它键：长按即取消本次点击
        abortKey = true
        balloonState.value = null
    }

    private fun openMenuPopup(key: SoftKey, selected: Boolean) {
        val pair = menuPairOf(key) ?: return
        balloonState.value = null
        popupState.value = buildPopup(
            key = key,
            keys = listOf(pair.second),
            isMenu = true,
            selected = selected,
            menuPair = pair,
        )
    }

    private fun menuPairOf(key: SoftKey): Pair<PopupMenuMode, String>? = when (key.code) {
        InputModeSwitcher.USER_KEYCODE_LANG -> Pair(PopupMenuMode.SwitchIME, "🌐")
        InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI -> Pair(PopupMenuMode.EMOJI, "😆")
        KeyEvent.KEYCODE_SHIFT_LEFT -> Pair(
            PopupMenuMode.EnglishCell,
            if (AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()) "直输模式" else "拼写模式"
        )
        KeyEvent.KEYCODE_DEL -> Pair(PopupMenuMode.Clear, "🔙 上滑清空")
        InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> Pair(PopupMenuMode.Move, "")
        KeyEvent.KEYCODE_ENTER -> Pair(PopupMenuMode.Enter, "↩️ 换行")
        else -> null
    }

    private fun buildTextPopupKeys(label: String, smallLabel: String): List<String> {
        val fromSmall = PopupSmallPreset[smallLabel] ?: emptyArray()
        val fromLabel = PopupPreset[label] ?: emptyArray()
        if (fromSmall.isEmpty() && fromLabel.isEmpty()) {
            return PopupPreset[smallLabel]?.toList() ?: emptyList()
        }
        return (fromSmall + fromLabel).toList()
    }

    private fun buildPopup(
        key: SoftKey,
        keys: List<String>,
        isMenu: Boolean,
        selected: Boolean,
        menuPair: Pair<PopupMenuMode, String>?,
    ): KeyPopup {
        val anchor = keyRectInView(key)
        val cellWidth = if (isMenu) {
            // 菜单项文字比键帽宽，一格里放不下时按内容放宽
            max(viewWidth / 10f, anchor.width)
        } else {
            viewWidth / 10f
        }
        val cellHeight = anchor.height
        val count = keys.size.toFloat().coerceAtLeast(1f)
        val rows = max(1, ceil(count / 5f).toInt())
        val columns = max(1, (count / rows).roundToInt())

        val totalWidth = cellWidth * columns
        var left = anchor.center.x - totalWidth / 2f
        left = left.coerceIn(0f, max(0f, viewWidth - totalWidth))
        val bottom = anchor.top
        val top = bottom - cellHeight * rows
        return KeyPopup(
            keys = keys,
            focus = if (isMenu) 0 else 0,
            rect = androidx.compose.ui.geometry.Rect(left, top, left + totalWidth, bottom),
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            isMenu = isMenu,
            selected = selected,
            menuPair = menuPair,
            columns = columns,
            rows = rows,
            clearMenu = menuPair?.first == PopupMenuMode.Clear,
        )
    }

    /**
     * 按指针位置更新弹层焦点。
     *
     * 与旧实现「按增量位移换行换列」相比，这里直接按绝对位置取格子：
     * 手指落在哪一格就选中哪一格，回退时不会因为增量累计误差选错。
     */
    private fun updatePopupFocus(popup: KeyPopup, position: Offset): KeyPopup {
        if (popup.keys.size == 1) {
            val moved = abs(position.y - downY) > 5f
            return if (popup.isMenu) popup.copy(selected = popup.selected || moved) else popup
        }
        val col = ((position.x - popup.rect.left) / popup.cellWidth).toInt()
            .coerceIn(0, popup.columns - 1)
        val row = ((position.y - popup.rect.top) / popup.cellHeight).toInt()
            .coerceIn(0, popup.rows - 1)
        val index = (row * popup.columns + col).coerceIn(0, popup.keys.lastIndex)
        return popup.copy(focus = index, selected = true)
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancellationCompat(): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: return null
        if (!change.pressed) return change
    }
}

// ---------------------------------------------------------------------------
// 绘制
// ---------------------------------------------------------------------------

private class RenderedKey(
    val key: SoftKey,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val corners: CornerRadius,
    val label: TextLayoutResult?,
    val labelOffset: Offset,
    val icon: ImageBitmap?,
    val iconOffset: Offset,
    val smallLabel: TextLayoutResult?,
    val smallOffset: Offset,
    val mnemonic: TextLayoutResult?,
    val mnemonicOffset: Offset,
    val langMain: TextLayoutResult?,
    val langSub: TextLayoutResult?,
    val langMainOffset: Offset,
    val langSubOffset: Offset,
    val oval: Boolean,
)

private class KeyPainter(
    private val theme: com.yuyan.imemodule.data.theme.Theme,
    private val textMeasurer: androidx.compose.ui.text.TextMeasurer,
    private val scaleX: Float,
    private val scaleY: Float,
    private val keyTextSizePx: Int,
    private val smallTextSizePx: Int,
    private val density: Float,
    private val fontScale: Float,
) {
    private val textColor = Color(theme.keyTextColor)
    private val keyColor = Color(theme.keyBackgroundColor)
    private val pressedColor = Color(theme.keyPressHighlightColor)
    private val functionColor = Color(theme.functionKeyBackgroundColor)
    private val accentColor = Color(theme.accentKeyBackgroundColor)
    private val popupColor = Color(theme.popupBackgroundColor)

    private val keyBorder = ThemeManager.prefs.keyBorder.getValue()
    private val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
    private val showSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
    private val fontBold = ThemeManager.prefs.keyboardFontBold.getValue()
    private val mnemonicEnabled = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
    private val styleMode = ThemeManager.prefs.skbStyleMode.getValue()

    /** EnvironmentSingleton 的字号是像素值（旧实现直接赋给 Paint.textSize），按密度换算成等价 sp */
    private fun pxToSp(px: Float): TextUnit = (px / (density * fontScale)).sp

    private val keyTextStyle = TextStyle(
        color = textColor,
        fontSize = pxToSp(keyTextSizePx.toFloat()),
        fontWeight = if (fontBold) FontWeight.Bold else FontWeight.Medium,
    )
    private val functionTextStyle = TextStyle(
        color = textColor,
        fontSize = pxToSp(keyTextSizePx.toFloat()),
        fontWeight = if (fontBold) FontWeight.Bold else FontWeight.Normal,
    )
    private val smallTextStyle = TextStyle(
        color = textColor,
        fontSize = pxToSp(smallTextSizePx * SYMBOL_SIZE_RATIO),
    )
    private val mnemonicTextStyle = TextStyle(
        color = textColor,
        fontSize = pxToSp(smallTextSizePx * 0.7f),
    )

    fun buildKeys(keyboard: SoftKeyboard, keys: List<SoftKey>): List<RenderedKey> {
        val keyXMargin = keyboard.keyXMargin
        val keyYMarginBase = keyboard.keyYMargin
        val googleQwerty = styleMode == SkbStyleMode.Google && InputModeSwitcher.isQwert
        val keyYMargin = if (googleQwerty) (keyYMarginBase * 1.5f).toInt() else keyYMarginBase

        return keys.map { key ->
            val left = (key.mLeft + keyXMargin) * scaleX
            val top = (key.mTop + keyYMargin) * scaleY
            val right = (key.mRight - keyXMargin) * scaleX
            val bottom = (key.mBottom - keyYMargin) * scaleY
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            val constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1))
            val keyH = key.height().toFloat()

            val isLangKey = key.code == InputModeSwitcher.USER_KEYCODE_LANG && key.stateId != 2
            val isSplitKey = key.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese && !InputDispatcher.isFinish

            var label: TextLayoutResult? = null
            var labelOffset = Offset.Zero
            var smallLabel: TextLayoutResult? = null
            var smallOffset = Offset.Zero
            var mnemonic: TextLayoutResult? = null
            var mnemonicOffset = Offset.Zero
            var icon: ImageBitmap? = null
            var iconOffset = Offset.Zero
            var langMain: TextLayoutResult? = null
            var langSub: TextLayoutResult? = null
            var langMainOffset = Offset.Zero
            var langSubOffset = Offset.Zero

            val displayLabel = when {
                isLangKey -> ""
                isSplitKey -> "分词"
                styleMode == SkbStyleMode.Google ->
                    if (InputModeSwitcher.isLower) key.keyLabel.lowercase() else key.keyLabel
                else ->
                    if (InputModeSwitcher.isLower && InputModeSwitcher.isEnglish) key.keyLabel.lowercase()
                    else key.keyLabel
            }
            val rawSmall = if (showSymbol) key.getmKeyLabelSmall() else ""
            val rawMnemonic = if (mnemonicEnabled) key.keyMnemonic else ""

            val drawable = when {
                isLangKey -> null
                isSplitKey -> null
                styleMode == SkbStyleMode.Google && key.code == KeyEvent.KEYCODE_SPACE -> null
                styleMode == SkbStyleMode.Google && key.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION &&
                        !DictDecoder.isCandidatesEmpty -> null
                isLangKey -> null
                else -> key.keyIcon
            }

            val textStyle = if (isStandaloneLabel(displayLabel)) functionTextStyle else keyTextStyle

            // 垂直布局沿用旧 TextKeyboard 的固定比例：所有按键共用同一条字母基线；
            // 上方小标点按「字母帽顶 - 间距 - 符号帽高」定位，放不下时整体下压字母。
            val keyTextSizeF = keyTextSizePx.toFloat()
            val smallTextSizeF = smallTextSizePx * SYMBOL_SIZE_RATIO
            val letterCap = keyTextSizeF * CAP_HEIGHT_RATIO
            val symbolCap = smallTextSizeF * CAP_HEIGHT_RATIO
            val symbolGap = smallTextSizeF * SYMBOL_GAP_RATIO
            val stackedSymbol = showSymbol && rawSmall.isNotEmpty() &&
                    styleMode == SkbStyleMode.Yuyan && displayLabel.isNotEmpty()
            val mnemonicShown = mnemonicEnabled && rawMnemonic.isNotEmpty()

            var letterCenterY = if (mnemonicShown) top + height * 0.42f
            else (top + bottom) / 2f + height * KEY_CENTER_OFFSET_RATIO
            var symbolInkTop = letterCenterY - letterCap / 2f - symbolGap - symbolCap
            val minSymbolTop = top + height * SYMBOL_MIN_TOP_RATIO
            if (stackedSymbol && symbolInkTop < minSymbolTop) {
                letterCenterY += minSymbolTop - symbolInkTop
                symbolInkTop = minSymbolTop
            }
            val letterBaseline = letterCenterY + letterCap / 2f

            if (drawable != null) {
                runCatching { drawable.setTint(theme.keyTextColor) }
                val sizePx = min(width, height) * ICON_SIZE_RATIO
                icon = drawable.toImageBitmap(sizePx)
                icon?.let {
                    iconOffset = Offset((left + right) / 2f - it.width / 2f, (top + bottom) / 2f - it.height / 2f)
                }
            } else if (displayLabel.isNotEmpty()) {
                label = textMeasurer.measure(
                    displayLabel, textStyle, overflow = TextOverflow.Clip, maxLines = 1, constraints = constraints
                )
                val labelY = if (isStandaloneLabel(displayLabel)) {
                    val box = runCatching { label.getBoundingBox(displayLabel.lastIndex) }.getOrNull()
                    val centerY = (top + bottom) / 2f + height * KEY_CENTER_OFFSET_RATIO
                    if (box != null) centerY - (box.top + box.bottom) / 2f
                    else centerY - label.size.height / 2f
                } else {
                    letterBaseline - label.firstBaseline
                }
                labelOffset = Offset(
                    computeLabelX(left, right, key, displayLabel, label),
                    labelY,
                )
            }

            if (rawSmall.isNotEmpty()) {
                smallLabel = textMeasurer.measure(
                    rawSmall, smallTextStyle, overflow = TextOverflow.Clip, maxLines = 1, constraints = constraints
                )
                smallOffset = if (stackedSymbol) {
                    val box = runCatching { smallLabel.getBoundingBox(0) }.getOrNull()
                    val symbolTop = if (box != null) symbolInkTop + symbolCap - box.bottom
                    else symbolInkTop + symbolCap - smallLabel.size.height
                    Offset(
                        computeInkCenterX(left, right, rawSmall, smallLabel),
                        symbolTop,
                    )
                } else {
                    Offset(
                        right - smallLabel.size.width - keyXMargin * 0.6f * scaleX,
                        top + height * 0.275f - smallLabel.firstBaseline,
                    )
                }
            }

            if (rawMnemonic.isNotEmpty()) {
                mnemonic = textMeasurer.measure(
                    rawMnemonic, mnemonicTextStyle, overflow = TextOverflow.Clip, maxLines = 1, constraints = constraints
                )
                mnemonicOffset = Offset(
                    (left + right) / 2f - mnemonic.size.width / 2f,
                    top + height * 0.875f - mnemonic.firstBaseline,
                )
            }

            if (isLangKey) {
                val mainText = if (InputModeSwitcher.isEnglish) "EN" else "中"
                val subText = if (InputModeSwitcher.isEnglish) "/中" else "/EN"
                langMain = textMeasurer.measure(
                    mainText, functionTextStyle.copy(fontWeight = FontWeight.Bold), maxLines = 1, constraints = constraints
                )
                langSub = textMeasurer.measure(subText, smallTextStyle, maxLines = 1, constraints = constraints)
                val total = langMain.size.width + langSub.size.width
                val startX = (left + right) / 2f - total / 2f
                langMainOffset = Offset(startX, (top + bottom) / 2f - langMain.size.height / 2f)
                langSubOffset = Offset(
                    startX + langMain.size.width,
                    (top + bottom) / 2f + langMain.size.height / 2f - langSub.size.height,
                )
            }

            RenderedKey(
                key = key,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                corners = CornerRadius(keyRadius * scaleX),
                label = label,
                labelOffset = labelOffset,
                icon = icon,
                iconOffset = iconOffset,
                smallLabel = smallLabel,
                smallOffset = smallOffset,
                mnemonic = mnemonic,
                mnemonicOffset = mnemonicOffset,
                langMain = langMain,
                langSub = langSub,
                langMainOffset = langMainOffset,
                langSubOffset = langSubOffset,
                oval = !keyBorder && key.code == KeyEvent.KEYCODE_ENTER,
            )
        }
    }

    fun DrawScope.drawKey(r: RenderedKey, pressed: Boolean) {
        // 键帽底部投影：先铺两层下移的深色圆角矩形，键帽盖在上面，只在下方露出阴影
        if (keyBorder && !theme.isDark) {
            val dy = max(1f, scaleY)
            drawRoundRect(
                color = Color(KEY_SHADOW_FAR_COLOR),
                topLeft = Offset(r.left, r.top + dy * 2),
                size = Size(r.right - r.left, r.bottom - r.top),
                cornerRadius = r.corners,
            )
            drawRoundRect(
                color = Color(KEY_SHADOW_NEAR_COLOR),
                topLeft = Offset(r.left, r.top + dy),
                size = Size(r.right - r.left, r.bottom - r.top),
                cornerRadius = r.corners,
            )
        }

        when {
            pressed -> drawRoundRect(
                color = pressedColor,
                topLeft = Offset(r.left, r.top),
                size = Size(r.right - r.left, r.bottom - r.top),
                cornerRadius = r.corners,
            )
            keyBorder -> drawRoundRect(
                color = when (r.key.code) {
                    KeyEvent.KEYCODE_ENTER -> accentColor
                    InputModeSwitcher.USER_KEYCODE_SYMBOL,
                    InputModeSwitcher.USER_KEYCODE_NUMBER,
                    InputModeSwitcher.USER_KEYCODE_LANG -> functionColor
                    else -> keyColor
                },
                topLeft = Offset(r.left, r.top),
                size = Size(r.right - r.left, r.bottom - r.top),
                cornerRadius = r.corners,
            )
            r.oval -> {
                // 无边框风格：回车键画一个胶囊
                val inset = min(r.right - r.left, r.bottom - r.top) * 0.12f
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(r.left + inset, r.top + inset),
                    size = Size(r.right - r.left - inset * 2, r.bottom - r.top - inset * 2),
                    cornerRadius = CornerRadius((r.bottom - r.top) / 2f),
                )
            }
        }

        r.icon?.let { bitmap ->
            drawImage(bitmap, topLeft = r.iconOffset)
        }
        r.label?.let { drawText(it, topLeft = r.labelOffset) }
        r.smallLabel?.let { drawText(it, topLeft = r.smallOffset) }
        r.mnemonic?.let { drawText(it, topLeft = r.mnemonicOffset) }
        r.langMain?.let { drawText(it, topLeft = r.langMainOffset) }
        r.langSub?.let { drawText(it, topLeft = r.langSubOffset) }
    }

    fun DrawScope.drawBalloon(balloon: KeyBalloon) {
        val radius = CornerRadius(keyRadius * scaleX)
        drawRoundRect(
            color = popupColor,
            topLeft = Offset(balloon.rect.left, balloon.rect.top),
            size = Size(balloon.rect.width, balloon.rect.height),
            cornerRadius = radius,
        )
        val layout = textMeasurer.measure(balloon.text, keyTextStyle, maxLines = 1)
        drawText(
            layout,
            topLeft = Offset(
                balloon.rect.center.x - layout.size.width / 2f,
                balloon.rect.center.y - layout.size.height / 2f,
            ),
        )
    }

    fun DrawScope.drawPopup(popup: KeyPopup) {
        val radius = CornerRadius(keyRadius * scaleX)
        drawRoundRect(
            color = popupColor,
            topLeft = Offset(popup.rect.left, popup.rect.top),
            size = Size(popup.rect.width, popup.rect.height),
            cornerRadius = radius,
        )
        popup.keys.forEachIndexed { index, text ->
            val col = index % popup.columns
            val row = index / popup.columns
            val cellLeft = popup.rect.left + col * popup.cellWidth
            val cellTop = popup.rect.top + row * popup.cellHeight
            val focused = index == popup.focus && popup.selected
            if (focused) {
                drawRoundRect(
                    color = if (popup.clearMenu) Color(0xFFEF5350) else accentColor,
                    topLeft = Offset(cellLeft, cellTop),
                    size = Size(popup.cellWidth, popup.cellHeight),
                    cornerRadius = radius,
                )
            }
            if (text.isEmpty()) return@forEachIndexed
            val layout = textMeasurer.measure(text, keyTextStyle, maxLines = 1)
            drawText(
                layout,
                topLeft = Offset(
                    cellLeft + popup.cellWidth / 2f - layout.size.width / 2f,
                    cellTop + popup.cellHeight / 2f - layout.size.height / 2f,
                ),
            )
        }
    }

    /** 水平位置：单字符按墨迹居中（全角标点墨迹偏左），多字符按步进居中 */
    private fun computeLabelX(
        left: Float,
        right: Float,
        key: SoftKey,
        text: String,
        layout: TextLayoutResult,
    ): Float {
        return if (text.length == 1) {
            computeInkCenterX(left, right, text, layout)
        } else {
            (left + right) / 2f - layout.size.width / 2f
        }
    }

    private fun computeInkCenterX(
        left: Float,
        right: Float,
        text: String,
        layout: TextLayoutResult,
    ): Float {
        if (text.length == 1) {
            val box = runCatching { layout.getBoundingBox(0) }.getOrNull()
            if (box != null) {
                val inkWidth = box.right - box.left
                return (left + right) / 2f - inkWidth / 2f - box.left
            }
        }
        return (left + right) / 2f - layout.size.width / 2f
    }

    companion object {
        private const val ICON_SIZE_RATIO = 0.42f
        private const val SYMBOL_SIZE_RATIO = 0.75f
        private const val SYMBOL_GAP_RATIO = 1.0f
        private const val CAP_HEIGHT_RATIO = 0.72f
        private const val SYMBOL_MIN_TOP_RATIO = 0.05f
        private const val KEY_CENTER_OFFSET_RATIO = 0.06f
        private const val KEY_SHADOW_NEAR_COLOR = 0x3D000000
        private const val KEY_SHADOW_FAR_COLOR = 0x1A000000

        /** 汉字/多字符标签按墨迹居中；单个字母、数字、标点沿用统一字母基线 */
        private fun isStandaloneLabel(label: String): Boolean {
            if (label.length <= 1) return false
            val hasHan = label.any { it.code in 0x4E00..0x9FFF }
            val hasDigit = label.any { it.isDigit() }
            return hasHan || hasDigit
        }
    }
}

private fun Drawable.toImageBitmap(sizePx: Float): ImageBitmap? {
    val px = sizePx.toInt()
    if (px <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, px, px)
        draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
