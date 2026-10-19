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
import androidx.lifecycle.Observer
import com.yuyan.imemodule.callback.IKeyboardView
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
    private var isHardwareKeyboard = false
    private var showVirtualKeyboardOnPhysicalKeyboard = false
    private lateinit var  candidateView:CandidateView
    private lateinit var  inputView:InputView
    private lateinit var mInputView:IKeyboardView
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? -> if (::mInputView.isInitialized) mInputView.updateTheme()}
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    if (::mInputView.isInitialized) mInputView.showSymbols(arrayOf(value))
                }
            }
        }
    }
    private val showVirtualKeyboardOnPhysicalKeyboardListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        showVirtualKeyboardOnPhysicalKeyboard = value
        handleHardwareKeyboard()
        updateInputViewShown()
    }
    private val candidatesObserver = Observer<Any?> { _ ->
        if (::mInputView.isInitialized) mInputView.onCandidateChanged()
    }

    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        // 进程重建时需要先把已保存的开关值读回来，否则会一直按“有实体键盘”处理，软键盘渲染不出来
        showVirtualKeyboardOnPhysicalKeyboard = getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.registerOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        handleHardwareKeyboard()
        DictDecoder.candidatesLiveData.observeForever(candidatesObserver)
    }

    override fun onCreateInputView(): View {
        if(!::inputView.isInitialized)inputView = InputView(baseContext, this)
        if(!isHardwareKeyboard)mInputView = inputView
        return inputView
    }

    override fun onCreateCandidatesView(): View {
        if(!::candidateView.isInitialized)candidateView = CandidateView(baseContext, this)
        if(isHardwareKeyboard) mInputView = candidateView
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        return candidateView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return if(showVirtualKeyboardOnPhysicalKeyboard) true else !isHardwareKeyboard
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        if(editorInfo != null) {
            YuyanEmojiCompat.setEditorInfo(editorInfo)
            InputModeSwitcher.requestInputWithSkb(editorInfo)
        }
        if(isHardwareKeyboard)updateCandidatesViewShown(true)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (::mInputView.isInitialized) mInputView.onStartInputView(editorInfo, restarting)
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onDestroy() {
        super.onDestroy()
        DictDecoder.candidatesLiveData.removeObserver(candidatesObserver)
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.unregisterOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        removeOnChangedListener(onThemeChangeListener)
        getInstance().internal.clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            handleHardwareKeyboard()
            EnvironmentSingleton.instance.initData(baseContext)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
            if (::mInputView.isInitialized) mInputView.setConfiguration(newConfig)
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (::mInputView.isInitialized) mInputView.processKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyUp(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyUp(keyCode, event)
        else if (::mInputView.isInitialized) mInputView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else super.onKeyUp(keyCode, event)
    }

    override fun setInputView(view: View) {
        // 输入法窗口重建（切换输入框、分屏/悬浮、旋屏、被系统回收后重新拉起）时，
        // 系统可能把还挂在旧窗口上的同一个视图再传进来。这时 InputMethodService.setInputView
        // 内部的 addView 会抛 "The specified child already has a parent"，
        // 表现就是「点输入框没反应，再点一次才弹出键盘」（第一次把输入法进程打崩了）。
        // 交回系统之前先摘掉旧父容器，让这次 addView 一定能成功。
        (view.parent as? ViewGroup)?.removeView(view)
        super.setInputView(view)
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题


    override fun onComputeInsets(outInsets: Insets) {
        val (x, y, width, height) = if (::mInputView.isInitialized) mInputView.getKeyboardRect() else intArrayOf(0, 0, 0,0)
        outInsets.apply {
            if(EnvironmentSingleton.instance.keyboardModeFloat) {
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

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
       super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::mInputView.isInitialized) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
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
        if (::mInputView.isInitialized) mInputView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        // 物理键盘模式下只创建候选视图，此时 mInputView 可能还没赋值，直接调用会 UninitializedPropertyAccessException
        if (::mInputView.isInitialized) mInputView.onWindowShown()
        super.onWindowShown()
        hideSystemImeSwitchButton()
    }

    override fun onWindowHidden() {
        if (::mInputView.isInitialized) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = getCurrentInputConnection()
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
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
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        currentInputConnection.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection.getTextBeforeCursor(length, 0).toString()
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
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
