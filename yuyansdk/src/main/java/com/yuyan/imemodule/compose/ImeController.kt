package com.yuyan.imemodule.compose

import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.ClipboardLayoutMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.service.InputDispatcher
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.inputmethod.CustomEngine
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SETTINGS 容器内的三种页面 */
enum class SettingsPage {
    /** 默认：功能菜单宫格 */
    Menu,

    /** 输入方式选择（T9 / 26 键 / 手写 / 双拼 / 17 键 / 笔画） */
    SelectInputMode,

    /** 菜单项自定义（拖拽排序 + 是否常驻候选栏） */
    Customize,
}

/**
 * Compose 键盘的全部 UI 状态。
 *
 * 这里只放「渲染需要、但不属于引擎」的东西：候选列表本身在
 * `DictDecoder.candidateState`，当前键盘类型在 `KeyboardManager.keyboardType`。
 */
data class ImeUiState(
    val settingsPage: SettingsPage = SettingsPage.Menu,
    val symbolMode: SymbolMode = SymbolMode.Symbol,
    val symbolPage: Int = 0,
    val clipboardMode: SkbMenuMode = SkbMenuMode.ClipBoard,
    /** 0 = 无激活项；n>0 表示激活第 n 个候选（与旧 `activeCandNo` 的 1-based 语义一致） */
    val activeCandidate: Int = 0,
    val addPhrases: Boolean = false,
    val phraseContent: String = "",
    val phraseQuickCode: String = "",
    val phraseEditingQuickCode: Boolean = false,
    val phraseExtra: Phrase? = null,
    /** 键盘高度/悬浮位置的调整浮层 */
    val heightAdjusterVisible: Boolean = false,
    /** 单手模式左侧还是右侧（跟随偏好，但切换需要立即生效） */
    val oneHandedLeft: Boolean = false,
    /** 上一轮的编辑内容是否已提交（防止 e.g. 空内容） */
    val phraseErrorMessage: Int? = null,
    /** 剪贴板内容变化计数：面板据此重新分页加载 */
    val clipboardRevision: Int = 0,
)

/**
 * Compose 键盘的「控制器」：状态 + 菜单动作 + 短语编辑器。
 *
 * 生命周期跟随 [ImeComposeRoot]，一个输入法进程内只有一个实例。
 * 之所以不放进 ViewModel：它强依赖 [ImeService]（提交文本/取光标上下文），
 * 而服务本身的生命周期已经长于组合，再套一层 ViewModel 只会让依赖关系更绕。
 */
class ImeController(val service: ImeService) {

    private val appPrefs = AppPrefs.getInstance()

    private val _state = MutableStateFlow(
        ImeUiState(oneHandedLeft = appPrefs.keyboardSetting.oneHandedMod.getValue() ==
                com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.LEFT)
    )
    val state: StateFlow<ImeUiState> = _state.asStateFlow()

    /**
     * 键帽「可切换状态」的版本号：shift 大小写、enter 动作图标、选区高亮变化时 +1。
     * `KeyGrid` 以此作为绘制的 key，保证 `SoftKeyToggle.stateId` 变更后缓存失效。
     */
    private val _keyboardRevision = MutableStateFlow(0)
    val keyboardRevision: StateFlow<Int> = _keyboardRevision.asStateFlow()

    /**
     * 导航栏高度（insets 实时值）。与 [EnvironmentSingleton.systemNavbarWindowsBottom]
     * 同步更新，但这里是 Compose 状态：底部栏/占位区的组合读到它才会随 insets 变化重组。
     */
    var navbarInset: Int by mutableIntStateOf(0)

    val dispatcher = KeyDispatcher(this)

    val keyboardType: StateFlow<KeyboardManager.KeyboardType?> = KeyboardManager.instance.keyboardType

    fun update(block: (ImeUiState) -> ImeUiState) {
        _state.value = block(_state.value)
    }

    fun bumpKeyboardRevision() {
        _keyboardRevision.value = _keyboardRevision.value + 1
    }

    /** 剪贴板/短语库内容变化：让面板重新分页加载 */
    fun bumpClipboardRevision() = update { it.copy(clipboardRevision = it.clipboardRevision + 1) }

    // ------------------------------------------------------------------
    // 候选
    // ------------------------------------------------------------------

    fun resetActiveCandidate() = update { it.copy(activeCandidate = 0) }

    fun setActiveCandidate(index: Int) = update { it.copy(activeCandidate = index.coerceAtLeast(0)) }

    fun activeCandidateId(): Int = _state.value.activeCandidate.let { if (it > 0) it - 1 else 0 }

    // ------------------------------------------------------------------
    // 键盘类型
    // ------------------------------------------------------------------

    val currentKeyboardType: KeyboardManager.KeyboardType?
        get() = KeyboardManager.instance.keyboardType.value

    /** 与旧 `KeyboardManager.isInputKeyboard` 等价，但在 Compose 路径下不依赖 View 容器 */
    val isInputKeyboard: Boolean
        get() = when (currentKeyboardType) {
            KeyboardManager.KeyboardType.T9,
            KeyboardManager.KeyboardType.QWERTY,
            KeyboardManager.KeyboardType.QWERTYABC,
            KeyboardManager.KeyboardType.LX17,
            KeyboardManager.KeyboardType.NUMBER,
            KeyboardManager.KeyboardType.TEXTEDIT,
            KeyboardManager.KeyboardType.HANDWRITING,
            KeyboardManager.KeyboardType.STROKE -> true
            else -> false
        }

    private fun switchKeyboard(type: KeyboardManager.KeyboardType) {
        KeyboardManager.instance.switchKeyboard(type)
    }

    /** 回到「输入键盘」（按当前输入模式重新计算类型） */
    fun switchToInputKeyboard() {
        KeyboardManager.instance.switchKeyboard()
        bumpKeyboardRevision()
    }

    // ------------------------------------------------------------------
    // 符号面板
    // ------------------------------------------------------------------

    fun openSymbolPanel(mode: SymbolMode) {
        val samePanel = currentKeyboardType == KeyboardManager.KeyboardType.SYMBOL && _state.value.symbolMode == mode
        if (samePanel) {
            switchToInputKeyboard()
        } else {
            update { it.copy(symbolMode = mode, symbolPage = 0) }
            switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
        }
    }

    fun setSymbolPage(page: Int) = update { it.copy(symbolPage = page) }

    // ------------------------------------------------------------------
    // 剪贴板 / 短语
    // ------------------------------------------------------------------

    fun openClipboardPanel(mode: SkbMenuMode) {
        val samePanel = currentKeyboardType == KeyboardManager.KeyboardType.ClipBoard && _state.value.clipboardMode == mode
        if (samePanel) {
            switchToInputKeyboard()
        } else {
            CustomConstant.lockClipBoardEnable = false
            update { it.copy(clipboardMode = mode) }
            switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)
        }
    }

    fun toggleClipboardLock() {
        CustomConstant.lockClipBoardEnable = !CustomConstant.lockClipBoardEnable
        bumpKeyboardRevision()
    }

    val isClipboardLocked: Boolean get() = CustomConstant.lockClipBoardEnable

    // ------------------------------------------------------------------
    // 菜单动作（旧 `SettingsMenuClick.kt` + `InputView.onSettingsMenuClick` 的合体）
    // ------------------------------------------------------------------

    fun onMenuAction(mode: SkbMenuMode) {
        when (mode) {
            SkbMenuMode.Emojicon, SkbMenuMode.Emoticon -> {
                openSymbolPanel(if (mode == SkbMenuMode.Emoticon) SymbolMode.Emoticon else SymbolMode.Emojicon)
            }

            SkbMenuMode.SwitchKeyboard -> {
                update { it.copy(settingsPage = SettingsPage.SelectInputMode) }
                switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
            }

            SkbMenuMode.KeyboardHeight -> {
                switchToInputKeyboard()
                update { it.copy(heightAdjusterVisible = true) }
            }

            SkbMenuMode.DarkTheme -> {
                val theme = (if (ThemeManager.activeTheme.isDark) ThemeManager.prefs.lightModeTheme
                else ThemeManager.prefs.darkModeTheme).getValue()
                ThemeManager.setNormalModeTheme(theme)
                KeyboardManager.instance.clearKeyboard()
                switchToInputKeyboard()
            }

            SkbMenuMode.Feedback -> AppUtil.launchSettingsToKeyboard(Launcher.instance.context)

            SkbMenuMode.NumberRow -> {
                val abcNumberLine = appPrefs.keyboardSetting.abcNumberLine.getValue()
                appPrefs.keyboardSetting.abcNumberLine.setValue(!abcNumberLine)
                KeyboardLoaderUtil.instance.changeSKBNumberRow()
                KeyboardManager.instance.clearKeyboard()
                switchToInputKeyboard()
            }

            SkbMenuMode.JianFan -> {
                val chineseFanTi = appPrefs.input.chineseFanTi.getValue()
                appPrefs.input.chineseFanTi.setValue(!chineseFanTi)
                InputDispatcher.nativeUpdateImeOption()
                switchToInputKeyboard()
            }

            SkbMenuMode.LockEnglish -> {
                val locked = appPrefs.keyboardSetting.keyboardLockEnglish.getValue()
                appPrefs.keyboardSetting.keyboardLockEnglish.setValue(!locked)
                switchToInputKeyboard()
            }

            SkbMenuMode.SymbolShow -> {
                val symbol = ThemeManager.prefs.keyboardSymbol.getValue()
                ThemeManager.prefs.keyboardSymbol.setValue(!symbol)
                KeyboardManager.instance.clearKeyboard()
                switchToInputKeyboard()
            }

            SkbMenuMode.Mnemonic -> {
                val mnemonic = appPrefs.keyboardSetting.keyboardMnemonic.getValue()
                appPrefs.keyboardSetting.keyboardMnemonic.setValue(!mnemonic)
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.clearKeyboard()
                switchToInputKeyboard()
            }

            SkbMenuMode.EmojiInput -> {
                val emojiInput = appPrefs.input.emojiInput.getValue()
                appPrefs.input.emojiInput.setValue(!emojiInput)
                InputDispatcher.nativeUpdateImeOption()
                switchToInputKeyboard()
            }

            SkbMenuMode.Handwriting -> AppUtil.launchSettingsToHandwriting(Launcher.instance.context)

            SkbMenuMode.Settings -> AppUtil.launchSettings(Launcher.instance.context)

            SkbMenuMode.OneHanded -> {
                val switch = appPrefs.keyboardSetting.oneHandedModSwitch
                switch.setValue(!switch.getValue())
                refreshEnvironment()
                update {
                    it.copy(
                        oneHandedLeft = appPrefs.keyboardSetting.oneHandedMod.getValue() ==
                                com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod.LEFT
                    )
                }
            }

            SkbMenuMode.FlowerTypeface -> {
                CustomConstant.flowerTypeface =
                    if (CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) FlowerTypefaceMode.Mars
                    else FlowerTypefaceMode.Disabled
                switchToInputKeyboard()
            }

            SkbMenuMode.FloatKeyboard -> {
                EnvironmentSingleton.instance.keyboardModeFloat = !EnvironmentSingleton.instance.keyboardModeFloat
                refreshEnvironment()
            }

            SkbMenuMode.ClipBoard, SkbMenuMode.Phrases -> openClipboardPanel(mode)

            SkbMenuMode.Custom -> {
                update { it.copy(settingsPage = SettingsPage.Customize) }
                switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
            }

            SkbMenuMode.CloseSKB -> service.requestHideSelf(0)

            SkbMenuMode.SettingsMenu -> {
                if (isInputKeyboard) {
                    update { it.copy(settingsPage = SettingsPage.Menu) }
                    switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                } else {
                    switchToInputKeyboard()
                }
            }

            SkbMenuMode.CandidatesMore -> switchKeyboard(KeyboardManager.KeyboardType.CANDIDATES)

            SkbMenuMode.LockClipBoard -> toggleClipboardLock()

            SkbMenuMode.TextEdit -> InputModeSwitcher.switchModeForUserKey(
                if (InputModeSwitcher.isTextEditSkb) InputModeSwitcher.USER_KEYCODE_RETURN
                else InputModeSwitcher.USER_KEYCODE_TEXTEDIT
            )

            SkbMenuMode.AddPhrases -> startAddPhrases(extra = null)

            else -> {}
        }
    }

    /** 旧 `InputView.onSettingsMenuClick(SkbMenuMode.AddPhrases, extra)` */
    fun startAddPhrases(extra: Phrase?) {
        update {
            it.copy(
                addPhrases = true,
                phraseContent = extra?.content ?: "",
                phraseQuickCode = extra?.qwerty ?: "",
                phraseEditingQuickCode = false,
                phraseExtra = extra,
                phraseErrorMessage = null,
            )
        }
        if (extra != null) {
            DataBaseKT.instance.phraseDao().deleteByContent(extra.content)
            CustomEngine.invalidatePhraseCache()
        }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    fun clearPhraseContent() = update { it.copy(phraseContent = "", phraseQuickCode = "") }

    fun setPhraseEditingQuickCode(editing: Boolean) = update { it.copy(phraseEditingQuickCode = editing) }

    /** 短语编辑框收到一个字符/按键 */
    fun editPhrase(keyCode: Int) {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                update {
                    if (it.phraseEditingQuickCode) it.copy(phraseQuickCode = it.phraseQuickCode.dropLast(1))
                    else it.copy(phraseContent = it.phraseContent.dropLast(1))
                }
            }
            android.view.KeyEvent.KEYCODE_ENTER -> finishAddPhrases()
            else -> {
                val ch = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode).unicodeChar.toChar()
                if (ch != Char.MIN_VALUE) appendPhraseText(ch.toString())
            }
        }
    }

    fun appendPhraseText(text: String) {
        update {
            if (it.phraseEditingQuickCode) it.copy(phraseQuickCode = it.phraseQuickCode + text)
            else it.copy(phraseContent = it.phraseContent + text)
        }
        updateQuickCodeFromContent()
    }

    /** 与旧 `EditPhrasesView` 一致：内容变化时按前 4 个字重算拼音首字母 */
    private fun updateQuickCodeFromContent() {
        val content = _state.value.phraseContent
        val head = runCatching { PinyinHelper.getPinYinHeadChar(content.take(4)) }.getOrDefault("")
        if (head.isNotBlank() && !_state.value.phraseEditingQuickCode) {
            update { it.copy(phraseQuickCode = head) }
        }
    }

    /** 提交短语（等价旧 `EditPhrasesView.addPhrasesHandle`） */
    fun finishAddPhrases() {
        val s = _state.value
        val content = s.phraseContent
        if (content.isNotBlank()) {
            val head = s.phraseQuickCode
            val t9 = head.map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
            val lx17 = head.map { LX17PinYinUtils.pinyin2Lx17Key(it) }.joinToString("")
            DataBaseKT.instance.phraseDao().insert(Phrase(content = content, t9 = t9, qwerty = head, lx17 = lx17))
            CustomEngine.invalidatePhraseCache()
        }
        update { it.copy(addPhrases = false, phraseExtra = null, phraseContent = "", phraseQuickCode = "") }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    fun cancelAddPhrases() {
        update { it.copy(addPhrases = false, phraseExtra = null) }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    // ------------------------------------------------------------------
    // 输入方式选择（旧 `SettingsContainer.showSkbSelelctModeView`）
    // ------------------------------------------------------------------

    fun selectInputMode(mode: SkbMenuMode) {
        val value = when (mode) {
            SkbMenuMode.Pinyin26Jian -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to
                    CustomConstant.SCHEMA_ZH_QWERTY

            SkbMenuMode.PinyinHandWriting -> {
                // 16KB 页设备上手写库不可用，入口已过滤，这里再兜一层
                if (!com.yuyan.inputmethod.HWEngine.isAvailable) return
                InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING to CustomConstant.SCHEMA_ZH_HANDWRITING
            }

            SkbMenuMode.PinyinLx17 -> InputModeSwitcher.MASK_SKB_LAYOUT_LX17 to
                    CustomConstant.SCHEMA_ZH_DOUBLE_LX17

            SkbMenuMode.PinyinStroke -> InputModeSwitcher.MASK_SKB_LAYOUT_STROKE to
                    CustomConstant.SCHEMA_ZH_STROKE

            SkbMenuMode.Pinyin26Double -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to
                    (CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + appPrefs.input.doublePYSchemaMode.getValue())

            else -> InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN to CustomConstant.SCHEMA_ZH_T9
        }
        InputModeSwitcher.switchModeForSetting(value)
        dispatcher.resetToIdleState()
        bumpKeyboardRevision()
    }

    // ------------------------------------------------------------------
    // 环境尺寸
    // ------------------------------------------------------------------

    /** 键盘尺寸相关设置变化后统一刷新（与旧 `InputView.onClick` / 菜单里的序列一致） */
    fun refreshEnvironment() {
        EnvironmentSingleton.instance.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        switchToInputKeyboard()
        bumpKeyboardRevision()
    }

    fun hideHeightAdjuster() = update { it.copy(heightAdjusterVisible = false) }

    fun keyLabelOf(key: SoftKey): String = key.keyLabel

    /** 全面屏键盘优化栏的动作 */
    fun onFullDisplayAction(mode: com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode) {
        when (mode) {
            com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode.SwitchIme ->
                com.yuyan.imemodule.utils.InputMethodUtil.showPicker()

            com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode.SwitchLanguage ->
                InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)

            com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode.Clipboard -> openClipboardPanel(SkbMenuMode.ClipBoard)
            com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode.Phrases -> openClipboardPanel(SkbMenuMode.Phrases)
            else -> {}
        }
    }

    /** 剪贴板布局模式（渲染用） */
    val clipboardLayoutMode: ClipboardLayoutMode
        get() = appPrefs.clipboard.clipboardLayoutCompact.getValue()

    val string: (Int) -> String = { service.getString(it) }

    companion object {
        const val EMPTY_TEXT_HINT = R.string.add_phrases_input_hint
    }
}
