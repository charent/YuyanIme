package com.yuyan.imemodule.compose

import android.view.KeyEvent
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.callback.IResponseKeyEvent
import com.yuyan.imemodule.data.emojicon.EmojiconData.SymbolPreset
import com.yuyan.imemodule.entity.StringQueue
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.KeyPreset
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.service.InputDispatcher
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.CustomEngine
import com.yuyan.inputmethod.RimeEngine
import com.yuyan.inputmethod.core.CandidateListItem
import android.os.Handler
import android.os.Looper

/**
 * 键盘事件派发器：UI ↔ 引擎的**唯一**咽喉点（方案 §7「明确不变的部分」）。
 *
 * 这个类是从旧 `InputView` 里把 `responseKeyEvent` / `processKeyUp` 那一整套分支逻辑
 * **原样**抽出来的结果，唯一的变化是：
 * - 不再持有 `Context` / `View`，只依赖 [ImeService] 的公开方法；
 * - 原先直接操作 `CandidatesBar` / 容器的地方，改成写 [ImeController] 的状态
 *   （候选列表由 `DictDecoder.candidateState` 驱动，UI 自己响应）；
 * - 原先 `EditPhrasesView` 的文本编辑状态，改成 [ImeUiState] 里的纯字符串。
 *
 * 这样 Compose 与旧 View 两条路径可以共用同一份「按键 → 引擎」的判定，
 * 键值序列等价回归（方案 Phase 2 验收项）才有意义。
 */
class KeyDispatcher(private val controller: ImeController) : IResponseKeyEvent {

    private val service: ImeService get() = controller.service
    private val appPrefs = getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 记录删除内容，供「上滑清空 → 上滑恢复」使用 */
    private val textBeforeCursors = StringQueue(50)

    var hasSelection = false
    var hasSelectionAll = false

    private var chinesePrediction = true
    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0

    fun onStartInputView(restarting: Boolean) {
        chinesePrediction = appPrefs.input.chinesePrediction.getValue()
        if (restarting) return
        resetToIdleState()
        val clipboard = appPrefs.clipboard
        if (clipboard.clipboardSuggestion.getValue()) {
            val internal = appPrefs.internal
            val timeoutMs = appPrefs.clipboard.clipboardItemTimeout.getValue() * 1000L
            val lastTime = internal.clipboardUpdateTime.getValue()
            if (System.currentTimeMillis() - lastTime <= timeoutMs) {
                val content = internal.clipboardUpdateContent.getValue()
                if (content.isNotBlank()) {
                    showSymbols(arrayOf(content))
                    internal.clipboardUpdateTime.setValue(0L)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // IResponseKeyEvent
    // ---------------------------------------------------------------------

    override fun responseKeyEvent(sKey: SoftKey) {
        val keyCode = sKey.code
        if (sKey.isUserDefKey) {
            processUserDefKey(keyCode, sKey.keyLabel)
        } else if (sKey.isUniStrKey) {
            if (!DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty) chooseAndUpdate()
            sKey.label.takeIf(String::isNotEmpty)?.let {
                if (SymbolPreset.containsKey(it)) commitPairSymbol(it) else commitText(it)
            }
        } else {
            val metaState = when (InputDispatcher.getCurrentRimeSchema()) {
                CustomConstant.SCHEMA_ZH_T9,
                CustomConstant.SCHEMA_ZH_STROKE,
                CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> KeyEvent.META_CAPS_LOCK_ON
                else -> InputModeSwitcher.mToggleStates.modifiers
            }
            processKeyUp(
                KeyEvent(
                    0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD
                )
            )
        }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        if (mode != PopupMenuMode.None && !DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty) {
            if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
        }

        when (mode) {
            PopupMenuMode.Text -> if (SymbolPreset.containsKey(value)) commitPairSymbol(value) else commitText(value)
            PopupMenuMode.SwitchIME -> com.yuyan.imemodule.utils.InputMethodUtil.showPicker()
            PopupMenuMode.EMOJI -> controller.onMenuAction(SkbMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
                controller.bumpKeyboardRevision()
            }
            PopupMenuMode.Clear -> {
                if (controller.state.value.addPhrases) {
                    controller.clearPhraseContent()
                } else {
                    service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                        textBeforeCursors.push(it)
                        service.deleteSurroundingText(1000)
                    }
                }
            }
            PopupMenuMode.Revertl -> textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }
                ?.let { commitText(it) }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseHandwritingResultEvent(words: Array<CandidateListItem>) {
        DictDecoder.cacheCandidates(words)
    }

    // ---------------------------------------------------------------------
    // 硬件键
    // ---------------------------------------------------------------------

    /**
     * 物理键盘模式下的按下判定（旧 `CandidateView.processKeyDown`）。
     *
     * 与软键盘路径的区别：英文模式一律放行给宿主应用，数字键用于选候选。
     */
    fun processHardwareKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (InputModeSwitcher.isEnglish) return false
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return true
        if (keyCode == KeyEvent.KEYCODE_SPACE) return true
        if (keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON) return true
        if (keyCode == KeyEvent.KEYCODE_DEL) return true
        if (keyCode == KeyEvent.KEYCODE_ENTER) return true
        if (keyCode == KeyEvent.KEYCODE_TAB) return true
        if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return true
        return false
    }

    fun processHardwareKeyUp(event: KeyEvent): Boolean {
        InputModeSwitcher.resetCharCase()
        return if (processHardwareFunctionKeys(event)) true
        else if (InputModeSwitcher.isChinese) processHardwareInput(event)
        else false
    }

    private fun processHardwareFunctionKeys(event: KeyEvent): Boolean {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                when {
                    DictDecoder.isAssociate && controller.state.value.activeCandidate == 0 -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                    !DictDecoder.isCandidatesEmpty -> chooseAndUpdate()
                    // 有编码但没候选（i / u / v 这类）：按编码上屏
                    DictDecoder.isComposing -> {
                        commitDecInfoText(DictDecoder.composingStrForCommit)
                        resetToIdleState()
                    }
                    else -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
                    InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
                    resetToIdleState()
                    android.widget.Toast.makeText(
                        service,
                        if (InputModeSwitcher.isEnglish) "语燕输入法-英文" else "语燕输入法-拼音",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return true
            }
            KeyEvent.KEYCODE_CLEAR -> {
                resetToIdleState()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                resetToIdleState()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!DictDecoder.isCandidatesEmpty) updateActiveCandidateNo(keyCode) else sendKeyEvent(keyCode)
                return true
            }
            else -> return false
        }
    }

    private fun processHardwareInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    if (!InputDispatcher.isFinish) InputDispatcher.deleteAction() else InputDispatcher.reset()
                    updateCandidate()
                }
                true
            }
            label.isNotEmpty() && label.all(Char::isDigit) -> {
                // 数字键选候选
                chooseAndUpdate(label.toInt() - 1)
                true
            }
            Character.isLetter(keyChar) || keyCode == KeyEvent.KEYCODE_APOSTROPHE ||
                    keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                InputDispatcher.inputKeyCode(event)
                updateCandidate()
                true
            }
            else -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                false
            }
        }
    }

    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL -> return true
            KeyEvent.KEYCODE_BACK -> return service.isInputViewShown
        }
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        if (event.isSystem) return processSystemKeys(event)
        if (isFunctionKey(event.keyCode)) {
            processFunctionKey(event)
            return true
        }
        InputModeSwitcher.resetCharCase()
        val englishCellDisable = InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue()
        return when {
            englishCellDisable -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
    }

    // ---------------------------------------------------------------------
    // 具体分支
    // ---------------------------------------------------------------------

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                textBeforeCursors.clear()
                commitText(label)
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) {
                service.requestHideSelf(0)
                true
            } else false
            else -> false
        }
    }

    fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                when {
                    DictDecoder.isAssociate -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                    !DictDecoder.isCandidatesEmpty -> chooseAndUpdate()
                    // 有编码但没候选（i / u / v 这类）：像回车一样把编码上屏，别让编码被空格吃掉
                    DictDecoder.isComposing -> {
                        commitDecInfoText(DictDecoder.composingStrForCommit)
                        resetToIdleState()
                    }
                    else -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                }
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                resetToIdleState()
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (InputModeSwitcher.isChinese && !InputDispatcher.isFinish) {
                    processInput(
                        KeyEvent(
                            0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0,
                            KeyEvent.FLAG_SOFT_KEYBOARD
                        )
                    )
                } else {
                    InputModeSwitcher.processShiftKey(keyCode)
                }
            }
        }
        // 键帽状态（shift 大写/分词、enter 动作图标）跟着变了，通知 UI 重绘
        controller.bumpKeyboardRevision()
    }

    private fun processUserDefKey(keyCode: Int, label: String) {
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            !DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_SYMBOL ->
                controller.openSymbolPanel(com.yuyan.imemodule.prefs.behavior.SymbolMode.Symbol)

            InputModeSwitcher.USER_KEYCODE_EMOJI ->
                controller.openSymbolPanel(com.yuyan.imemodule.prefs.behavior.SymbolMode.Emojicon)
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG ->
                InputModeSwitcher.switchModeForUserKey(keyCode)
            in InputModeSwitcher.USER_KEYCODE_PASTE..InputModeSwitcher.USER_KEYCODE_CUT ->
                commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            InputModeSwitcher.USER_KEYCODE_MOVE_START ->
                service.setSelection(0, if (hasSelection) selEnd else 0)
            InputModeSwitcher.USER_KEYCODE_MOVE_END -> {
                if (hasSelection) {
                    val start = selStart
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    mainHandler.postDelayed({ service.setSelection(start, selEnd) }, 100)
                } else {
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    mainHandler.postDelayed({ service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT) }, 100)
                }
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE -> {
                hasSelection = !hasSelection
                if (!hasSelection) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                controller.bumpKeyboardRevision()
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL -> {
                hasSelectionAll = !hasSelectionAll
                if (!hasSelectionAll) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                else commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            else -> {
                if (label.isNotEmpty()) {
                    if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                    sendKeyEvent(keyCode)
                } else {
                    InputDispatcher.deleteAction()
                    updateCandidate()
                }
                true
            }
            (Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) ||
                    keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                textBeforeCursors.clear()
                InputDispatcher.inputKeyCode(event)
                updateCandidate()
                true
            }
            keyCode != 0 -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                else if (DictDecoder.isComposing && !DictDecoder.isAssociate) {
                    // 有编码没候选（i/u/v）：先把编码上屏，再把按键交给宿主
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                sendKeyEvent(keyCode)
                resetToIdleState()
                true
            }
            label.isNotEmpty() -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                else if (DictDecoder.isComposing && !DictDecoder.isAssociate) {
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------------
    // 候选与状态
    // ---------------------------------------------------------------------

    fun resetToIdleState() {
        resetCandidateWindow()
        if (hasSelectionAll) hasSelectionAll = false
        controller.resetActiveCandidate()
    }

    fun chooseAndUpdate(candId: Int = controller.activeCandidateId()) {
        val candidate = DictDecoder.getCandidate(candId)
        val tag = candidate?.comment
        if (candidate != null && (tag == RimeEngine.CUSTOM_TAG || tag == RimeEngine.CORRECTION_TAG)) {
            // 纠错候选的编码和 Rime 组合串不同，只能直接提交文本；顺手把这次纠正学进用户词库
            if (tag == RimeEngine.CORRECTION_TAG) RimeEngine.learnCorrection(candidate.text)
            commitDecInfoText(candidate.text)
            // 自定义候选不经过 Rime 选词，提交后清掉组合串，否则拼音会留在输入框上方
            resetToIdleState()
        } else {
            val choice = DictDecoder.chooseDecodingCandidate(candId)
            if (DictDecoder.isCandidatesEmpty || DictDecoder.isAssociate) {
                KeyboardManager.instance.switchKeyboard()
                commitDecInfoText(choice)
            } else {
                if (!DictDecoder.isCandidatesEmpty) {
                    if (InputModeSwitcher.isEnglish) service.setComposingText(DictDecoder.composingStrForCommit)
                } else {
                    resetToIdleState()
                }
            }
        }
        controller.resetActiveCandidate()
    }

    fun updateCandidate() {
        DictDecoder.updateDecodingCandidate()
        if (!DictDecoder.isCandidatesEmpty || DictDecoder.isComposing) {
            // 候选/前缀变化已通过 StateFlow 通知 UI
        } else {
            resetToIdleState()
        }
        if (InputModeSwitcher.isEnglish) service.setComposingText(DictDecoder.composingStrForCommit)
    }

    fun updateActiveCandidateNo(keyCode: Int): Int {
        var active = controller.state.value.activeCandidate
        if (!DictDecoder.isCandidatesEmpty) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (--active <= 0) active = 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (++active > DictDecoder.candidateSize) active = DictDecoder.candidateSize
            }
            controller.setActiveCandidate(active)
        }
        return active
    }

    private fun resetCandidateWindow() {
        DictDecoder.reset()
        InputDispatcher.reset()
    }

    // ---------------------------------------------------------------------
    // 提交与宿主交互
    // ---------------------------------------------------------------------

    fun selectPrefix(position: Int) {
        InputDispatcher.selectPrefix(position)
        updateCandidate()
    }

    fun showSymbols(symbols: Array<String>) {
        val list = symbols.map { CandidateListItem("📋", it) }.toTypedArray()
        DictDecoder.cacheCandidates(list, true)
    }

    private fun sendKeyEvent(keyCode: Int) {
        if (controller.state.value.addPhrases) {
            controller.editPhrase(keyCode)
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
            in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 单行编辑框上下没有可移动的行：吞掉，事件交给宿主会走焦点导航，
                // 焦点移出输入框就会把当前编辑界面切走（表现为「按上下键退出」）
                val vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (!vertical || service.isMultilineEditor) {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                    if (hasSelectionAll) hasSelectionAll = false
                }
            }
            else -> service.sendCombinationKeyEvents(keyCode)
        }
    }

    fun setComposingText(text: CharSequence) {
        if (!controller.state.value.addPhrases) service.setComposingText(text)
    }

    fun commitText(text: String) {
        if (controller.state.value.addPhrases) {
            controller.appendPhraseText(text)
        } else {
            service.commitText(StringUtils.converted2FlowerTypeface(text))
        }
    }

    fun commitPairSymbol(text: String) {
        if (controller.state.value.addPhrases) {
            controller.appendPhraseText(text)
        } else {
            if (appPrefs.input.symbolPairInput.getValue()) {
                service.commitText(text + SymbolPreset[text]!!)
                mainHandler.postDelayed({ service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }, 300)
            } else {
                service.commitText(text)
            }
        }
    }

    fun commitTextEditMenu(id: Int?) {
        id?.let { service.commitTextEditMenu(it) }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        if (controller.state.value.addPhrases) {
            controller.appendPhraseText(resultText)
        } else {
            RimeEngine.clearPendingLearning()
            service.commitText(StringUtils.converted2FlowerTypeface(resultText))
            if (InputModeSwitcher.isEnglish) {
                service.finishComposingText()
                if (appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
                resetToIdleState()
            }
        }
    }

    // ---------------------------------------------------------------------
    // 光标与联想
    // ---------------------------------------------------------------------

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesEnd: Int,
    ) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (InputModeSwitcher.isEnglish) {
            if (oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            return
        }
        if (oldSelStart == newSelStart) return
        when {
            InputModeSwitcher.isNumberSkb -> {
                val textBeforeCursor = service.getTextBeforeCursor(500)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else CustomEngine.parseExpressionAtEnd(textBeforeCursor).let {
                    CustomEngine.expressionCalculator(textBeforeCursor, it).let { result -> showSymbols(result) }
                }
            }
            chinesePrediction && InputModeSwitcher.isChinese -> {
                val textBeforeCursor = service.getTextBeforeCursor(10)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else {
                    InputDispatcher.predictAssociationWords(textBeforeCursor)
                    updateCandidate()
                }
            }
            else -> resetCandidateWindow()
        }
    }

    /** 供候选栏「点击间隔区上屏正上方候选」使用 */
    fun candidateAt(index: Int): CandidateListItem? = DictDecoder.getCandidate(index)

    /** 手动折行：给「全面屏键盘优化」的换行键复用 */
    fun commitNewLine() = commitText("\n")

    /** 字符串资源便捷入口（避免 UI 层到处 import R） */
    fun string(resId: Int): String = service.getString(resId)

    companion object {
        /** 与 `R.string.handwriting_unavailable_title` 对应的提示，手写不可用时使用 */
        const val HANDWRITING_UNAVAILABLE_HINT = R.string.handwriting_unavailable_title
    }
}
