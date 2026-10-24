package com.yuyan.imemodule.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.yuyan.imemodule.compose.HardwareCandidateView
import com.yuyan.imemodule.compose.ImeComposeRoot
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.ManagedPreference
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.isDarkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main class of the Pinyin input method. 输入法服务。
 *
 * Phase 5 起只有一条 UI 路径：Compose 根（[ImeComposeRoot]）+ Compose 候选栏
 * （[HardwareCandidateView]）。旧 `InputView` / `CandidateView` 与灰度开关已删除，
 * 因此这里不再出现「两套 View 二选一」的分支。
 */
class ImeService : InputMethodService() {

    private var isHardwareKeyboard = false
    private var showVirtualKeyboardOnPhysicalKeyboard = false

    /** 当前编辑框信息：判断单行/多行用（上/下方向键是否还有意义） */
    private var currentEditorInfo: EditorInfo? = null

    /** 软键盘根视图：服务存活期间复用，旋屏/换输入框只重排不重建组合 */
    private var composeRoot: ImeComposeRoot? = null

    /** 物理键盘模式下的候选栏（系统独立窗口） */
    private var hardwareCandidateView: HardwareCandidateView? = null

    private val onThemeChangeListener = OnThemeChangeListener {
        // 主题变了：键帽/背景都要重新读取，直接让键盘缓存失效
        composeRoot?.controller?.bumpKeyboardRevision()
    }

    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if (getInstance().clipboard.clipboardSuggestion.getValue() && value.isNotBlank()) {
            composeRoot?.controller?.let { controller ->
                controller.bumpClipboardRevision()
                controller.dispatcher.showSymbols(arrayOf(value))
            }
        }
    }

    private val showVirtualKeyboardOnPhysicalKeyboardListener =
        ManagedPreference.OnChangeListener<Boolean> { _, value ->
            showVirtualKeyboardOnPhysicalKeyboard = value
            handleHardwareKeyboard()
            updateInputViewShown()
            // 开启「物理键盘显示软键盘」后必须立即收起浮动候选栏，否则两条重叠
            updateCandidatesViewShown(isHardwareKeyboard)
        }

    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        // 进程重建时需要先把已保存的开关值读回来，否则会一直按“有实体键盘”处理，软键盘渲染不出来
        showVirtualKeyboardOnPhysicalKeyboard =
            getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard
            .registerOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        getInstance().internal.clipboardUpdateContent
            .registerOnChangeListener(clipboardUpdateContentListener)
        handleHardwareKeyboard()
        // 提前建根并推进到 CREATED，让首帧组合可以在键盘真正显示前预热
        ensureComposeRoot()
    }

    /**
     * 取（必要时创建）Compose 根视图。
     *
     * 与旧 `InputView` 一样缓存单例：旋屏、切换输入框、窗口重建都复用同一个 View 与同一份组合，
     * 避免每次唤起都重建 `Recomposer`（首帧开销远大于常驻）。
     */
    private fun ensureComposeRoot(): ImeComposeRoot {
        composeRoot?.let { return it }
        return ImeComposeRoot(baseContext, this).also {
            it.imeLifecycleOwner.onCreate()
            composeRoot = it
        }
    }

    override fun onCreateInputView(): View = ensureComposeRoot()

    override fun onCreateCandidatesView(): View {
        hardwareCandidateView?.let { return it }
        val root = ensureComposeRoot()
        // owner 统一取自 ImeComposeRoot：SavedStateRegistryOwnerImpl 只能在生命周期
        // 处于 INITIALIZED 时构造，而 onCreate 预热根视图时已把它推到 CREATED。
        return HardwareCandidateView(
            baseContext,
            root.controller,
            root.imeLifecycleOwner,
            root.viewModelStoreOwner,
            root.savedStateRegistryOwner,
        ).also {
            hardwareCandidateView = it
            currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return if (showVirtualKeyboardOnPhysicalKeyboard) true else !isHardwareKeyboard
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        currentEditorInfo = editorInfo
        if (editorInfo != null) {
            YuyanEmojiCompat.setEditorInfo(editorInfo)
            InputModeSwitcher.requestInputWithSkb(editorInfo)
        }
        // 浮动候选栏与软键盘互斥：软键盘自带候选栏，只有「有实体键盘且不显示软键盘」
        // 时才需要浮动候选栏。每次输入开始都重新求值，覆盖偏好/配置变更后的状态。
        updateCandidatesViewShown(isHardwareKeyboard)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        composeRoot?.let { root ->
            // 编辑器类型可能变了（数字框/搜索框），按当前 input 模式重算键盘类型
            KeyboardManager.instance.switchKeyboard()
            root.controller.dispatcher.onStartInputView(restarting)
            root.imeLifecycleOwner.show()
        }
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 键盘收起只降到 CREATED，组合保留（方案 §2.1 第 4 条）
        composeRoot?.imeLifecycleOwner?.hide()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        hardwareCandidateView?.destroy()
        hardwareCandidateView = null
        composeRoot?.destroyComposition()
        composeRoot = null
        super.onDestroy()
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard
            .unregisterOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        getInstance().internal.clipboardUpdateContent
            .unregisterOnChangeListener(clipboardUpdateContentListener)
        removeOnChangedListener(onThemeChangeListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            handleHardwareKeyboard()
            updateCandidatesViewShown(isHardwareKeyboard)
            EnvironmentSingleton.instance.initData(baseContext)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
            // Compose 根的高度是显式指定的，旋屏/改比例后需要重算，否则沿用旧方向的高度
            composeRoot?.applyPreferredHeight()
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed)
            super.onKeyDown(keyCode, event)
        else if (event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)
            super.onKeyDown(keyCode, event)
        else {
            val handled = composeRoot?.controller?.dispatcher
                ?.processHardwareKeyDown(keyCode, event) ?: false
            handled || super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed)
            super.onKeyUp(keyCode, event)
        else if (event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)
            super.onKeyUp(keyCode, event)
        else {
            val handled = composeRoot?.controller?.dispatcher
                ?.processHardwareKeyUp(event) ?: false
            handled || super.onKeyUp(keyCode, event)
        }
    }

    override fun setInputView(view: View) {
        // 输入法窗口重建（切换输入框、分屏/悬浮、旋屏、被系统回收后重新拉起）时，
        // 系统可能把还挂在旧窗口上的同一个视图再传进来。这时 InputMethodService.setInputView
        // 内部的 addView 会抛 "The specified child already has a parent"，
        // 表现就是「点输入框没反应，再点一次才弹出键盘」（第一次把输入法进程打崩了）。
        // 交回系统之前先摘掉旧父容器，让这次 addView 一定能成功。
        (view.parent as? ViewGroup)?.removeView(view)
        super.setInputView(view)
        val layoutParams = view.layoutParams ?: return
        // Compose 根没有可测量的子 View，必须显式给高度；否则 MATCH_PARENT 会撑满整个 IME 窗口，
        // 并让 onComputeInsets 报出覆盖全屏的键盘区域。
        val targetHeight = if (view is ImeComposeRoot) {
            view.preferredKeyboardHeight().takeIf { it > 0 } ?: ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (layoutParams.height != targetHeight) {
            layoutParams.height = targetHeight
            view.layoutParams = layoutParams
        }
    }

    /**
     * 与 [setInputView] 同一原因：旋屏/深色切换等配置变更会走
     * `resetStateForNewConfiguration()` → `initViews()` 重建整个窗口根视图，
     * 而 [HardwareCandidateView] 是跨窗口复用的单例，仍挂在已废弃的旧
     * candidatesArea 上，`setCandidatesView` 里的 addView 会抛
     * "The specified child already has a parent"。
     */
    override fun setCandidatesView(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        super.setCandidatesView(view)
    }

    /** 当前编辑框是否多行：单行框上下没有可移动的行，方向键应被吞掉而不是交给焦点导航 */
    val isMultilineEditor: Boolean
        get() {
            val info = currentEditorInfo ?: return false
            return (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题

    override fun onComputeInsets(outInsets: Insets) {
        val (x, y, width, height) = currentKeyboardRect()
        outInsets.apply {
            if (EnvironmentSingleton.instance.keyboardModeFloat) {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + width, y + height)
            } else {
                contentTopInsets = y
                touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                touchableRegion.setEmpty()
                visibleTopInsets = y
            }
        }
    }

    /** 当前键盘区域：Compose 根自己就是键盘区，直接取它的窗口坐标 */
    private fun currentKeyboardRect(): IntArray {
        composeRoot?.takeIf { it.isAttachedToWindow }?.let { return it.getKeyboardRect() }
        return intArrayOf(0, 0, 0, 0)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        composeRoot?.controller?.dispatcher?.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd
        )
    }

    private val cursorAnchorPosition = FloatArray(2)

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        hardwareCandidateView?.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        // 每次显示都按当前设置重算输入区尺寸并刷新组合，设置页改动后回到键盘立即生效
        composeRoot?.let { root ->
            EnvironmentSingleton.instance.initData(baseContext)
            root.controller.bumpKeyboardRevision()
            root.imeLifecycleOwner.show()
        }
        super.onWindowShown()
        hideSystemImeSwitchButton()
    }

    override fun onWindowHidden() {
        composeRoot?.let { root ->
            root.controller.dispatcher.resetToIdleState()
            KeyboardManager.instance.switchKeyboard()
            root.imeLifecycleOwner.hide()
        }
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = getCurrentInputConnection()
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE ->
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection.sendKeyEvent(
            KeyEvent(
                eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    /** 向输入框提交预选词 */
    fun setComposingText(text: CharSequence) {
        currentInputConnection.setComposingText(text, 1)
    }

    /** 结束提交预选词 */
    fun finishComposingText() {
        currentInputConnection.finishComposingText()
    }

    /** 发送字符串给编辑框 */
    fun commitText(text: String) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /** 发送字符串给编辑框 */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection.commitText(
            StringUtils.converted2FlowerTypeface(text), newCursorPosition
        )
    }

    fun getTextBeforeCursor(length: Int): String {
        return currentInputConnection.getTextBeforeCursor(length, 0).toString()
    }

    fun commitTextEditMenu(id: Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction: Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length: Int) {
        currentInputConnection.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection.setSelection(start, end)
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null): Boolean {
        val hardwareKeyboard = if (showVirtualKeyboardOnPhysicalKeyboard) false
        else if (newConfig != null) (newConfig.keyboard != Configuration.KEYBOARD_NOKEYS)
        else resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        isHardwareKeyboard = hardwareKeyboard
        return hardwareKeyboard
    }

    fun updateCandidatesViewShown(shown: Boolean) {
        setCandidatesViewShown(shown)
    }

    /**
     * Android 13 起，系统会在输入法窗口底部自绘一条导航栏（NavigationBarFrame），
     * 左端是系统的输入法切换（地球）按钮，右端是收起键盘按钮。
     * 语燕的输入法切换统一由设置里的「全面屏键盘优化」提供，这里把系统那个切换按钮隐藏掉，
     * 避免出现两个切换入口（收起键盘按钮保留，不影响返回）。
     */
    private fun hideSystemImeSwitchButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val decor = window?.window?.decorView as? ViewGroup ?: return
        val hide: () -> Unit = {
            val switcherId = resources.getIdentifier("ime_switch_button", "id", "android")
            val switcher = if (switcherId != 0) decor.findViewById<View>(switcherId) else null
            if (switcher != null) {
                switcher.visibility = View.INVISIBLE
            } else {
                // 个别机型按钮 id 不同，退化成整条系统导航栏隐藏
                findSystemNavigationBarFrame(decor)?.visibility = View.INVISIBLE
            }
        }
        decor.post { hide() }
        decor.postDelayed({ hide() }, 120) // 个别机型要等首帧布局后才插入系统导航栏
    }

    private fun findSystemNavigationBarFrame(view: View): View? {
        if (view.javaClass.simpleName == NAVIGATION_BAR_FRAME_CLASS_NAME) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSystemNavigationBarFrame(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    companion object {
        // 系统在输入法窗口里自绘的导航栏容器类名（android.inputmethodservice.navigationbar.NavigationBarFrame）
        private const val NAVIGATION_BAR_FRAME_CLASS_NAME = "NavigationBarFrame"
    }
}
